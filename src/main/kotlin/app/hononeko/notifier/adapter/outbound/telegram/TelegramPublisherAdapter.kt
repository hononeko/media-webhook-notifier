package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import arrow.core.Either
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Telegram Bot API publisher. With [callbackRefs] set (`TELEGRAM_INTERACTIVE_ENABLED=true`), card interactions are
 * rendered as callback buttons backed by a ref to the card; without it they are ignored.
 */
class TelegramPublisherAdapter(
    private val config: NotificationConfig,
    engine: HttpClientEngine? = null,
    private val callbackRefs: CallbackRefRegistry? = null
) : NotificationPublisherPort {
    companion object {
        private const val CAPTION_LIMIT = 1024
        private const val TEXT_LIMIT = 4096

        const val CALLBACK_TTL_SECONDS = CallbackRefRegistry.DEFAULT_TTL_SECONDS
    }

    override val providerId: String = "telegram"
    override val defaultChannelOrChatId: String get() = config.chatId
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    private val api = TelegramBotApi(config.botToken, providerId, engine)
    private val keyboards = TelegramKeyboards(callbackRefs)
    private val photoMessages = PhotoMessageRegistry(config.chatId)

    // Serializes outbound deliveries to avoid socket contention, rate-limiting drops, and out-of-order race conditions
    private val sendMutex = Mutex()

    private val channel: Destination get() = Destination(config.chatId, config.topicId)

    // The keyboard (and its callback ref) is built outside the mutex so a slow state store never holds up other
    // deliveries.
    override suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> =
        deliver(card, keyboards.forCard(card), channel)

    override suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle> =
        deliver(initialCard, keyboards.forCard(initialCard), channel)

    /** One ref for all copies, so a button pressed on one copy can find, and update, every other one. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun sendDirect(
        card: NotificationCard,
        recipients: List<String>
    ): Map<String, Either<DomainError.NotificationError, NotificationHandle>> {
        val ref = keyboards.registerRef(card)
        val markup = keyboards.forCard(card, ref)
        val results = recipients.associateWith { deliver(card, markup, Destination(it)) }
        val registry = callbackRefs
        if (ref != null && registry != null) {
            val delivered = results.values.mapNotNull { it.getOrNull() }
            try {
                if (delivered.isEmpty()) {
                    registry.release(ref)
                } else {
                    registry.update(
                        ref,
                        CallbackTarget(card.copy(artworkBytes = null), delivered),
                        CALLBACK_TTL_SECONDS
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The buttons still work; a decision then only updates the copy it was made on.
                logger.warn("Could not record the copies of '{}': {}", card.title, e.message)
            }
        }
        return results
    }

    private suspend fun deliver(
        card: NotificationCard,
        markup: InlineKeyboardMarkup?,
        destination: Destination
    ): Either<DomainError.NotificationError, NotificationHandle> =
        sendMutex.withLock {
            val textContent = TelegramHtmlFormatter.buildCardHtml(card, TEXT_LIMIT)

            if (config.sendPhotos) {
                val caption = TelegramHtmlFormatter.buildCardHtml(card, CAPTION_LIMIT)
                if (card.artworkBytes != null && card.artworkBytes.isNotEmpty()) {
                    val photoResult = api.sendPhotoBytes(destination, card.artworkBytes, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessages[photoHandle] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo binary upload failed, falling back to HTML text message")
                } else if (!card.artworkUrl.isNullOrBlank() &&
                    (card.artworkUrl.startsWith("http://") || card.artworkUrl.startsWith("https://"))
                ) {
                    val photoResult = api.sendPhoto(destination, card.artworkUrl, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessages[photoHandle] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo send failed, falling back to HTML text message")
                }
            }

            val textResult = api.sendMessage(destination, textContent, markup, card.silent)
            if (textResult is Either.Right) {
                val textHandle = textResult.value.copy(isPhoto = false)
                photoMessages[textHandle] = false
                return@withLock Either.Right(textHandle)
            }
            textResult
        }

    override suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = keyboards.forActions(update.actions)
        val html = TelegramHtmlFormatter.buildProgressHtml(update)

        val isPhoto = handle.isPhoto || (photoMessages[handle] ?: false)
        if (handle.isPhoto) {
            photoMessages[handle] = true
        }
        val targetChatId = handle.channelOrChatId.ifBlank { config.chatId }

        return if (isPhoto) {
            api.editCaption(targetChatId, msgId, TelegramHtmlFormatter.truncateHtml(html, CAPTION_LIMIT), markup)
        } else {
            api.editText(targetChatId, msgId, html, markup)
        }
    }

    override suspend fun completeProgress(
        handle: NotificationHandle,
        finalCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> =
        editWithCard(handle, finalCard).also { photoMessages.remove(handle) }

    override suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> =
        editWithCard(handle, reasonCard).also { photoMessages.remove(handle) }

    override suspend fun editCard(
        handle: NotificationHandle,
        card: NotificationCard,
        callbackRef: String?
    ): Either<DomainError.NotificationError, Unit> {
        // Unlike the progress paths, a requested edit that can't happen must not read as done.
        if (handle.messageReferenceId.toLongOrNull() == null) {
            val reason = "cannot edit '${handle.messageReferenceId}': not a Telegram message id"
            return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
        }
        return editWithCard(handle, card, callbackRef)
    }

    private suspend fun editWithCard(
        handle: NotificationHandle,
        card: NotificationCard,
        callbackRef: String? = null
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = if (callbackRef != null) keyboards.forCard(card, callbackRef) else keyboards.forCard(card)

        val isPhoto = handle.isPhoto || (photoMessages[handle] ?: false)
        val targetChatId = handle.channelOrChatId.ifBlank { config.chatId }

        if (isPhoto) {
            return api.editCaption(
                targetChatId,
                msgId,
                TelegramHtmlFormatter.buildCardHtml(card, CAPTION_LIMIT),
                markup
            )
        }

        val textResult =
            api.editText(
                targetChatId,
                msgId,
                TelegramHtmlFormatter.buildCardHtml(card, TEXT_LIMIT),
                markup
            )
        // Callbacks on old or inaccessible messages don't say the message is a photo; Telegram then rejects the text
        // edit, and the caption is the part to edit.
        return if (textResult.isNoTextToEdit()) {
            api.editCaption(targetChatId, msgId, TelegramHtmlFormatter.buildCardHtml(card, CAPTION_LIMIT), markup)
        } else {
            textResult
        }
    }

    private fun Either<DomainError.NotificationError, Unit>.isNoTextToEdit(): Boolean {
        val failure = (this as? Either.Left)?.value as? DomainError.NotificationError.DeliveryFailed ?: return false
        return failure.message.contains("there is no text in the message to edit", ignoreCase = true)
    }
}
