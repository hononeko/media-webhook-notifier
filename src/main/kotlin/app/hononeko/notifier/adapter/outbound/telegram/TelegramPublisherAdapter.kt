package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramCallbackData
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap

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
        private const val NETWORK_ERROR_MSG = "Network error"
        private const val MAX_REGISTRY_SIZE = 2000
        private const val CAPTION_LIMIT = 1024
        private const val TEXT_LIMIT = 4096

        const val CALLBACK_TTL_SECONDS = CallbackRefRegistry.DEFAULT_TTL_SECONDS
        private const val SEND_RETRY_AFTER_SECONDS = 30
        private const val EDIT_RETRY_AFTER_SECONDS = 5
    }

    override val providerId: String = "telegram"
    override val defaultChannelOrChatId: String get() = config.chatId
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    // Tracks if a message was posted as photo or text so edits use the matching endpoint. Keyed by chat and message
    // ([photoKey]): message ids are only unique within a chat, and cards also go to admins' private chats.
    // Bounded with LRU eviction to prevent memory leaks during continuous 24/7 uptime.
    private val photoMessageRegistry =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > MAX_REGISTRY_SIZE
            }
        )

    // Serializes outbound deliveries to avoid socket contention, rate-limiting drops, and out-of-order race conditions
    private val sendMutex = Mutex()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val httpClient =
        if (engine != null) {
            HttpClient(engine) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                }
            }
        } else {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                }
            }
        }

    private val apiBaseUrl: String
        get() = "https://api.telegram.org/bot${config.botToken}"

    @Serializable
    private data class TelegramResponse<T>(
        val ok: Boolean,
        val result: T? = null,
        val description: String? = null,
        @SerialName("error_code")
        val errorCode: Int? = null,
        val parameters: ResponseParameters? = null
    )

    @Serializable
    private data class ResponseParameters(
        @SerialName("retry_after")
        val retryAfter: Int? = null
    )

    @Serializable
    private data class MessageResult(
        @SerialName("message_id")
        val messageId: Long
    )

    @Serializable
    private data class SendMessageRequest(
        @SerialName("chat_id")
        val chatId: String,
        @SerialName("message_thread_id")
        val messageThreadId: Long? = null,
        val text: String,
        @SerialName("parse_mode")
        val parseMode: String = "HTML",
        @SerialName("disable_notification")
        val disableNotification: Boolean? = null,
        @SerialName("reply_markup")
        val replyMarkup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class SendPhotoRequest(
        @SerialName("chat_id")
        val chatId: String,
        @SerialName("message_thread_id")
        val messageThreadId: Long? = null,
        val photo: String,
        val caption: String? = null,
        @SerialName("parse_mode")
        val parseMode: String = "HTML",
        @SerialName("disable_notification")
        val disableNotification: Boolean? = null,
        @SerialName("reply_markup")
        val replyMarkup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class EditMessageTextRequest(
        @SerialName("chat_id")
        val chatId: String,
        @SerialName("message_id")
        val messageId: Long,
        val text: String,
        @SerialName("parse_mode")
        val parseMode: String = "HTML",
        @SerialName("reply_markup")
        val replyMarkup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class EditMessageCaptionRequest(
        @SerialName("chat_id")
        val chatId: String,
        @SerialName("message_id")
        val messageId: Long,
        val caption: String,
        @SerialName("parse_mode")
        val parseMode: String = "HTML",
        @SerialName("reply_markup")
        val replyMarkup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class InlineKeyboardMarkup(
        @SerialName("inline_keyboard")
        val inlineKeyboard: List<List<InlineKeyboardButton>>
    )

    @Serializable
    private data class InlineKeyboardButton(
        val text: String,
        val url: String? = null,
        @SerialName("callback_data")
        val callbackData: String? = null
    )

    /** Where a message goes: the configured channel (and forum topic), or a user's private chat. */
    private data class Destination(
        val chatId: String,
        val topicId: Long? = null
    )

    private val channel: Destination get() = Destination(config.chatId, config.topicId)

    private fun photoKey(handle: NotificationHandle): String =
        "${handle.channelOrChatId.ifBlank { config.chatId }}:${handle.messageReferenceId}"

    override suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> =
        deliverCard(card)

    override suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle> = deliverCard(initialCard)

    /** One ref for all copies, so a button pressed on one copy can find, and update, every other one. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun sendDirect(
        card: NotificationCard,
        recipients: List<String>
    ): Map<String, Either<DomainError.NotificationError, NotificationHandle>> {
        val ref = registerRef(card)
        val markup = keyboardFor(card, ref)
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

    private suspend fun deliverCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> =
        // Registered outside the mutex so a slow state store never holds up other deliveries.
        deliver(card, keyboardFor(card), channel)

    private suspend fun deliver(
        card: NotificationCard,
        markup: InlineKeyboardMarkup?,
        destination: Destination
    ): Either<DomainError.NotificationError, NotificationHandle> =
        sendMutex.withLock {
            val textContent = buildCardHtml(card, TEXT_LIMIT)

            if (config.sendPhotos) {
                val caption = buildCardHtml(card, CAPTION_LIMIT)
                if (card.artworkBytes != null && card.artworkBytes.isNotEmpty()) {
                    val photoResult = sendPhotoBytes(destination, card.artworkBytes, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessageRegistry[photoKey(photoHandle)] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo binary upload failed, falling back to HTML text message")
                } else if (!card.artworkUrl.isNullOrBlank() &&
                    (card.artworkUrl.startsWith("http://") || card.artworkUrl.startsWith("https://"))
                ) {
                    val photoResult = sendPhoto(destination, card.artworkUrl, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessageRegistry[photoKey(photoHandle)] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo send failed, falling back to HTML text message")
                }
            }

            val textResult = sendMessage(destination, textContent, markup, card.silent)
            if (textResult is Either.Right) {
                val textHandle = textResult.value.copy(isPhoto = false)
                photoMessageRegistry[photoKey(textHandle)] = false
                return@withLock Either.Right(textHandle)
            }
            textResult
        }

    override suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = buildKeyboard(update.actions)
        val html = buildProgressHtml(update)

        val isPhoto = handle.isPhoto || (photoMessageRegistry[photoKey(handle)] ?: false)
        if (handle.isPhoto) {
            photoMessageRegistry[photoKey(handle)] = true
        }
        val targetChatId = handle.channelOrChatId.ifBlank { config.chatId }

        return if (isPhoto) {
            editCaption(targetChatId, msgId, truncateToLimit(html, 1024), markup)
        } else {
            editText(targetChatId, msgId, html, markup)
        }
    }

    override suspend fun completeProgress(
        handle: NotificationHandle,
        finalCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> =
        editWithCard(handle, finalCard).also { photoMessageRegistry.remove(photoKey(handle)) }

    override suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> =
        editWithCard(handle, reasonCard).also { photoMessageRegistry.remove(photoKey(handle)) }

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
        val markup = if (callbackRef != null) keyboardFor(card, callbackRef) else keyboardFor(card)

        val isPhoto = handle.isPhoto || (photoMessageRegistry[photoKey(handle)] ?: false)
        val targetChatId = handle.channelOrChatId.ifBlank { config.chatId }

        if (isPhoto) return editCaption(targetChatId, msgId, buildCardHtml(card, CAPTION_LIMIT), markup)

        val textResult = editText(targetChatId, msgId, buildCardHtml(card, TEXT_LIMIT), markup)
        // Callbacks on old or inaccessible messages don't say the message is a photo; Telegram then rejects the text
        // edit, and the caption is the part to edit.
        return if (textResult.isNoTextToEdit()) {
            editCaption(targetChatId, msgId, buildCardHtml(card, CAPTION_LIMIT), markup)
        } else {
            textResult
        }
    }

    private fun Either<DomainError.NotificationError, Unit>.isNoTextToEdit(): Boolean {
        val failure = (this as? Either.Left)?.value as? DomainError.NotificationError.DeliveryFailed ?: return false
        return failure.message.contains("there is no text in the message to edit", ignoreCase = true)
    }

    private val telegramResponseSerializer = TelegramResponse.serializer(MessageResult.serializer())

    private suspend fun sendPhotoBytes(
        destination: Destination,
        photoBytes: ByteArray,
        caption: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> =
        try {
            val response =
                httpClient.submitFormWithBinaryData(
                    url = "$apiBaseUrl/sendPhoto",
                    formData =
                        formData {
                            append("chat_id", destination.chatId)
                            destination.topicId?.let { append("message_thread_id", it.toString()) }
                            append("caption", caption)
                            append("parse_mode", "HTML")
                            if (silent) {
                                append("disable_notification", "true")
                            }
                            markup?.let {
                                append("reply_markup", jsonConfig.encodeToString(InlineKeyboardMarkup.serializer(), it))
                            }
                            append(
                                "photo",
                                photoBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/jpeg")
                                    append(HttpHeaders.ContentDisposition, "filename=\"poster.jpg\"")
                                }
                            )
                        }
                )
            handleSendResponse(response, destination)
        } catch (e: IOException) {
            handleDeliveryException("photo binary", e)
        }

    private suspend fun sendPhoto(
        destination: Destination,
        photoUrl: String,
        caption: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendPhotoRequest(
                chatId = destination.chatId,
                messageThreadId = destination.topicId,
                photo = photoUrl,
                caption = caption,
                disableNotification = if (silent) true else null,
                replyMarkup = markup
            )

        return try {
            val payload = jsonConfig.encodeToString(SendPhotoRequest.serializer(), request)
            val response =
                httpClient.post("$apiBaseUrl/sendPhoto") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            handleSendResponse(response, destination)
        } catch (e: IOException) {
            handleDeliveryException("photo", e)
        }
    }

    private suspend fun sendMessage(
        destination: Destination,
        text: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendMessageRequest(
                chatId = destination.chatId,
                messageThreadId = destination.topicId,
                text = truncateToLimit(text, 4096),
                disableNotification = if (silent) true else null,
                replyMarkup = markup
            )

        return try {
            val payload = jsonConfig.encodeToString(SendMessageRequest.serializer(), request)
            val response =
                httpClient.post("$apiBaseUrl/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            handleSendResponse(response, destination)
        } catch (e: IOException) {
            handleDeliveryException("text", e)
        }
    }

    private suspend fun editText(
        chatId: String,
        messageId: Long,
        text: String,
        markup: InlineKeyboardMarkup?
    ): Either<DomainError.NotificationError, Unit> {
        val request =
            EditMessageTextRequest(
                chatId = chatId,
                messageId = messageId,
                text = truncateToLimit(text, 4096),
                replyMarkup = markup
            )

        return try {
            val payload = jsonConfig.encodeToString(EditMessageTextRequest.serializer(), request)
            val response =
                httpClient.post("$apiBaseUrl/editMessageText") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            handleEditResponse(response)
        } catch (e: IOException) {
            handleDeliveryException("edit text", e)
        }
    }

    private suspend fun editCaption(
        chatId: String,
        messageId: Long,
        caption: String,
        markup: InlineKeyboardMarkup?
    ): Either<DomainError.NotificationError, Unit> {
        val request =
            EditMessageCaptionRequest(
                chatId = chatId,
                messageId = messageId,
                caption = caption,
                replyMarkup = markup
            )

        return try {
            val payload = jsonConfig.encodeToString(EditMessageCaptionRequest.serializer(), request)
            val response =
                httpClient.post("$apiBaseUrl/editMessageCaption") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            handleEditResponse(response)
        } catch (e: IOException) {
            handleDeliveryException("edit caption", e)
        }
    }

    private fun <T> handleDeliveryException(
        operation: String,
        e: Throwable
    ): Either<DomainError.NotificationError, T> {
        // Ktor's timeout messages include the request URL, which contains the bot token.
        val reason =
            (e.message ?: NETWORK_ERROR_MSG).let { message ->
                if (config.botToken.isBlank()) message else message.replace(config.botToken, "<redacted>")
            }
        logger.warn("Error sending Telegram {}: {}", operation, reason)
        return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
    }

    private suspend fun handleSendResponse(
        response: HttpResponse,
        destination: Destination
    ): Either<DomainError.NotificationError, NotificationHandle> =
        either {
            val raw = response.bodyAsText()
            ensureSuccess(response.status, raw, SEND_RETRY_AFTER_SECONDS)
            val parsed =
                Either
                    .catch { jsonConfig.decodeFromString(telegramResponseSerializer, raw) }
                    .mapLeft {
                        DomainError.NotificationError.DeliveryFailed(
                            providerId,
                            "Failed to parse Telegram response: ${it.message}"
                        )
                    }.bind()
            val msgId =
                ensureNotNull(parsed.result?.messageId) {
                    DomainError.NotificationError.DeliveryFailed(providerId, "No messageId in response")
                }
            NotificationHandle(providerId, destination.chatId, msgId.toString())
        }

    private suspend fun handleEditResponse(response: HttpResponse): Either<DomainError.NotificationError, Unit> =
        either {
            val raw = response.bodyAsText()
            // An edit that changes nothing gets 400 "message is not modified", which is fine.
            val unchanged =
                response.status != HttpStatusCode.TooManyRequests &&
                    raw.contains("message is not modified", ignoreCase = true)
            if (!unchanged) ensureSuccess(response.status, raw, EDIT_RETRY_AFTER_SECONDS)
        }

    /**
     * Raises `RateLimited` for 429 (Telegram's `retry_after`, else [defaultRetryAfter]) and `DeliveryFailed` for any
     * other non-2xx status.
     */
    private fun Raise<DomainError.NotificationError>.ensureSuccess(
        status: HttpStatusCode,
        raw: String,
        defaultRetryAfter: Int
    ) {
        if (status == HttpStatusCode.TooManyRequests) {
            val parsed = runCatching { jsonConfig.decodeFromString(telegramResponseSerializer, raw) }.getOrNull()
            raise(
                DomainError.NotificationError.RateLimited(
                    providerId,
                    parsed?.parameters?.retryAfter ?: defaultRetryAfter
                )
            )
        }
        ensure(
            status.isSuccess()
        ) { DomainError.NotificationError.DeliveryFailed(providerId, "HTTP ${status.value}: $raw") }
    }

    private fun buildCardHtml(
        card: NotificationCard,
        maxChars: Int
    ): String = TelegramHtmlFormatter.buildCardHtml(card, maxChars)

    private fun buildProgressHtml(update: ProgressUpdate): String = TelegramHtmlFormatter.buildProgressHtml(update)

    private val allowedUrlSchemes = setOf("https", "http", "tg")

    private fun isValidUrlScheme(url: String): Boolean {
        val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme in allowedUrlSchemes
    }

    private fun buildKeyboard(actions: List<ActionLink>): InlineKeyboardMarkup? =
        urlButtons(actions).takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(inlineKeyboard = listOf(it)) }

    private fun urlButtons(actions: List<ActionLink>): List<InlineKeyboardButton> =
        actions
            .filter { isValidUrlScheme(it.url) }
            .map { action -> InlineKeyboardButton(text = action.label, url = action.url) }

    /** URL buttons in the first row, callback buttons in the second. Either row is left out when empty. */
    private suspend fun keyboardFor(card: NotificationCard): InlineKeyboardMarkup? =
        keyboardFor(card, registerRef(card))

    private fun keyboardFor(
        card: NotificationCard,
        ref: String?
    ): InlineKeyboardMarkup? {
        val rows = listOf(urlButtons(card.actions), callbackButtons(card, ref)).filter { it.isNotEmpty() }
        return rows.takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(inlineKeyboard = it) }
    }

    /** A ref to [card] for its callback buttons, or `null` when it has none or no ref could be stored. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun registerRef(card: NotificationCard): String? {
        val registry = callbackRefs ?: return null
        if (card.interactions.isEmpty()) return null
        val ref =
            try {
                registry.register(CallbackTarget(card.copy(artworkBytes = null)), CALLBACK_TTL_SECONDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Sending '{}' without its callback buttons: {}", card.title, e.message)
                return null
            }
        if (ref == null) {
            logger.warn("Sending '{}' without its callback buttons: no callback ref available", card.title)
        }
        return ref
    }

    private fun callbackButtons(
        card: NotificationCard,
        ref: String?
    ): List<InlineKeyboardButton> {
        if (ref == null) return emptyList()
        return card.interactions.mapNotNull { interaction ->
            TelegramCallbackData.encode(interaction.operation, ref)?.let { data ->
                InlineKeyboardButton(text = interaction.label, callbackData = data)
            }
        }
    }

    private fun truncateToLimit(
        text: String,
        maxChars: Int
    ): String = TelegramHtmlFormatter.truncateHtml(text, maxChars)
}
