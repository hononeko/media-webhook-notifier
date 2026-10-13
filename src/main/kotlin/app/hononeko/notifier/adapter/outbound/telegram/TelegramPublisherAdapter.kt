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

        /** Buttons stay usable for 30 days; a health card may be acknowledged long after it was sent. */
        const val CALLBACK_TTL_SECONDS = 2_592_000L
    }

    override val providerId: String = "telegram"
    override val defaultChannelOrChatId: String get() = config.chatId
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    // Tracks if a message was posted as photo or text so edits use the matching endpoint.
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

    override suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> =
        deliverCard(card)

    override suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle> = deliverCard(initialCard)

    private suspend fun deliverCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> {
        // Registered outside the mutex so a slow state store never holds up other deliveries.
        val markup = keyboardFor(card)
        return sendMutex.withLock {
            val textContent = buildCardHtml(card, TEXT_LIMIT)

            if (config.sendPhotos) {
                val caption = buildCardHtml(card, CAPTION_LIMIT)
                if (card.artworkBytes != null && card.artworkBytes.isNotEmpty()) {
                    val photoResult = sendPhotoBytes(card.artworkBytes, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessageRegistry[photoHandle.messageReferenceId] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo binary upload failed, falling back to HTML text message")
                } else if (!card.artworkUrl.isNullOrBlank() &&
                    (card.artworkUrl.startsWith("http://") || card.artworkUrl.startsWith("https://"))
                ) {
                    val photoResult = sendPhoto(card.artworkUrl, caption, markup, card.silent)
                    if (photoResult is Either.Right) {
                        val photoHandle = photoResult.value.copy(isPhoto = true)
                        photoMessageRegistry[photoHandle.messageReferenceId] = true
                        return@withLock Either.Right(photoHandle)
                    }
                    logger.warn("Telegram photo send failed, falling back to HTML text message")
                }
            }

            val textResult = sendMessage(textContent, markup, card.silent)
            if (textResult is Either.Right) {
                val textHandle = textResult.value.copy(isPhoto = false)
                photoMessageRegistry[textHandle.messageReferenceId] = false
                return@withLock Either.Right(textHandle)
            }
            textResult
        }
    }

    override suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = buildKeyboard(update.actions)
        val html = buildProgressHtml(update)

        val isPhoto = handle.isPhoto || (photoMessageRegistry[handle.messageReferenceId] ?: false)
        if (handle.isPhoto) {
            photoMessageRegistry[handle.messageReferenceId] = true
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
        editWithCard(handle, finalCard).also { photoMessageRegistry.remove(handle.messageReferenceId) }

    override suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> =
        editWithCard(handle, reasonCard).also { photoMessageRegistry.remove(handle.messageReferenceId) }

    override suspend fun editCard(
        handle: NotificationHandle,
        card: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        // Unlike the progress paths, a requested edit that can't happen must not read as done.
        if (handle.messageReferenceId.toLongOrNull() == null) {
            val reason = "cannot edit '${handle.messageReferenceId}': not a Telegram message id"
            return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
        }
        return editWithCard(handle, card)
    }

    private suspend fun editWithCard(
        handle: NotificationHandle,
        card: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = keyboardFor(card)

        val isPhoto = handle.isPhoto || (photoMessageRegistry[handle.messageReferenceId] ?: false)
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
                            append("chat_id", config.chatId)
                            config.topicId?.let { append("message_thread_id", it.toString()) }
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
            handleSendResponse(response)
        } catch (e: IOException) {
            handleDeliveryException("photo binary", e)
        }

    private suspend fun sendPhoto(
        photoUrl: String,
        caption: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendPhotoRequest(
                chatId = config.chatId,
                messageThreadId = config.topicId,
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
            handleSendResponse(response)
        } catch (e: IOException) {
            handleDeliveryException("photo", e)
        }
    }

    private suspend fun sendMessage(
        text: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendMessageRequest(
                chatId = config.chatId,
                messageThreadId = config.topicId,
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
            handleSendResponse(response)
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
        response: HttpResponse
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val raw: String = response.bodyAsText()
        if (response.status == HttpStatusCode.TooManyRequests) {
            val parsed: TelegramResponse<MessageResult>? =
                runCatching {
                    jsonConfig.decodeFromString(telegramResponseSerializer, raw)
                }.getOrNull()
            val retryAfter = parsed?.parameters?.retryAfter ?: 30
            return Either.Left(DomainError.NotificationError.RateLimited(providerId, retryAfter))
        }

        if (!response.status.value
                .toString()
                .startsWith("2")
        ) {
            return Either.Left(
                DomainError.NotificationError.DeliveryFailed(providerId, "HTTP ${response.status.value}: $raw")
            )
        }

        val parsed: TelegramResponse<MessageResult> =
            runCatching {
                jsonConfig.decodeFromString(telegramResponseSerializer, raw)
            }.getOrElse { e ->
                return Either.Left(
                    DomainError.NotificationError.DeliveryFailed(
                        providerId,
                        "Failed to parse Telegram response: ${e.message}"
                    )
                )
            }
        val msgId =
            parsed.result?.messageId
                ?: return Either.Left(
                    DomainError.NotificationError.DeliveryFailed(providerId, "No messageId in response")
                )
        return Either.Right(NotificationHandle(providerId, config.chatId, msgId.toString()))
    }

    private suspend fun handleEditResponse(response: HttpResponse): Either<DomainError.NotificationError, Unit> {
        val raw: String = response.bodyAsText()
        if (response.status == HttpStatusCode.TooManyRequests) {
            val parsed: TelegramResponse<MessageResult>? =
                runCatching {
                    jsonConfig.decodeFromString(telegramResponseSerializer, raw)
                }.getOrNull()
            val retryAfter = parsed?.parameters?.retryAfter ?: 5
            return Either.Left(DomainError.NotificationError.RateLimited(providerId, retryAfter))
        }

        if (!response.status.value
                .toString()
                .startsWith("2")
        ) {
            // If message was not modified, Telegram returns 400 Bad Request
            // ("message is not modified"), which is safe to ignore
            if (raw.contains("message is not modified", ignoreCase = true)) {
                return Either.Right(Unit)
            }
            return Either.Left(
                DomainError.NotificationError.DeliveryFailed(providerId, "HTTP ${response.status.value}: $raw")
            )
        }

        return Either.Right(Unit)
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
    private suspend fun keyboardFor(card: NotificationCard): InlineKeyboardMarkup? {
        val rows = listOf(urlButtons(card.actions), callbackButtons(card)).filter { it.isNotEmpty() }
        return rows.takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(inlineKeyboard = it) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun callbackButtons(card: NotificationCard): List<InlineKeyboardButton> {
        val registry = callbackRefs ?: return emptyList()
        if (card.interactions.isEmpty()) return emptyList()
        val ref =
            try {
                registry.register(CallbackTarget(card.copy(artworkBytes = null)), CALLBACK_TTL_SECONDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Sending '{}' without its callback buttons: {}", card.title, e.message)
                return emptyList()
            }
        if (ref == null) {
            logger.warn("Sending '{}' without its callback buttons: no callback ref available", card.title)
            return emptyList()
        }
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
