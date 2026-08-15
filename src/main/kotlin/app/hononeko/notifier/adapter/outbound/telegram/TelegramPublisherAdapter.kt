package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TelegramPublisherAdapter(
    private val config: NotificationConfig,
    engine: HttpClientEngine? = null
) : NotificationPublisherPort {
    companion object {
        private const val NETWORK_ERROR_MSG = "Network error"
    }

    override val providerId: String = "telegram"
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    // Tracks if a message was posted as photo or text so edits use the matching endpoint
    private val photoMessageRegistry = ConcurrentHashMap<String, Boolean>()

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private val httpClient =
        if (engine != null) {
            HttpClient(engine) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
                }
            }
        } else {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
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
        val url: String? = null
    )

    override suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> =
        deliverCard(card)

    override suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle> = deliverCard(initialCard)

    private suspend fun deliverCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> {
        val markup = buildKeyboard(card.actions)
        val textContent = buildCardHtml(card)

        if (config.sendPhotos && !card.artworkUrl.isNullOrBlank()) {
            val caption = truncateToLimit(textContent, 1024)
            val photoResult = sendPhoto(card.artworkUrl, caption, markup)
            if (photoResult is Either.Right) {
                photoMessageRegistry[photoResult.value.messageReferenceId] = true
                return photoResult
            }
            logger.warn("Telegram photo send failed, falling back to HTML text message")
        }

        val textResult = sendMessage(textContent, markup)
        if (textResult is Either.Right) {
            photoMessageRegistry[textResult.value.messageReferenceId] = false
        }
        return textResult
    }

    override suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = buildKeyboard(update.actions)
        val html = buildProgressHtml(update)

        val isPhoto = photoMessageRegistry[handle.messageReferenceId] ?: false
        return if (isPhoto) {
            editCaption(handle.channelOrChatId, msgId, truncateToLimit(html, 1024), markup)
        } else {
            editText(handle.channelOrChatId, msgId, html, markup)
        }
    }

    override suspend fun completeProgress(
        handle: NotificationHandle,
        finalCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = buildKeyboard(finalCard.actions)
        val html = buildCardHtml(finalCard)

        val isPhoto = photoMessageRegistry[handle.messageReferenceId] ?: false
        val result =
            if (isPhoto) {
                editCaption(handle.channelOrChatId, msgId, truncateToLimit(html, 1024), markup)
            } else {
                editText(handle.channelOrChatId, msgId, html, markup)
            }
        photoMessageRegistry.remove(handle.messageReferenceId)
        return result
    }

    override suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        val msgId = handle.messageReferenceId.toLongOrNull() ?: return Either.Right(Unit)
        val markup = buildKeyboard(reasonCard.actions)
        val html = buildCardHtml(reasonCard)

        val isPhoto = photoMessageRegistry[handle.messageReferenceId] ?: false
        val result =
            if (isPhoto) {
                editCaption(handle.channelOrChatId, msgId, truncateToLimit(html, 1024), markup)
            } else {
                editText(handle.channelOrChatId, msgId, html, markup)
            }
        photoMessageRegistry.remove(handle.messageReferenceId)
        return result
    }

    private val telegramResponseSerializer = TelegramResponse.serializer(MessageResult.serializer())

    private suspend fun sendPhoto(
        photoUrl: String,
        caption: String,
        markup: InlineKeyboardMarkup?
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendPhotoRequest(
                chatId = config.chatId,
                messageThreadId = config.topicId,
                photo = photoUrl,
                caption = caption,
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Network error sending Telegram photo: {}", e.message)
            Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, e.message ?: NETWORK_ERROR_MSG))
        }
    }

    private suspend fun sendMessage(
        text: String,
        markup: InlineKeyboardMarkup?
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendMessageRequest(
                chatId = config.chatId,
                messageThreadId = config.topicId,
                text = text,
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Network error sending Telegram text: {}", e.message)
            Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, e.message ?: NETWORK_ERROR_MSG))
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
                text = text,
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, e.message ?: NETWORK_ERROR_MSG))
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, e.message ?: NETWORK_ERROR_MSG))
        }
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
            // If message was not modified, Telegram returns 400 Bad Request ("message is not modified"), which is safe to ignore
            if (raw.contains("message is not modified", ignoreCase = true)) {
                return Either.Right(Unit)
            }
            return Either.Left(
                DomainError.NotificationError.DeliveryFailed(providerId, "HTTP ${response.status.value}: $raw")
            )
        }

        return Either.Right(Unit)
    }

    private fun buildCardHtml(card: NotificationCard): String {
        val sb = StringBuilder()
        sb.append("<b>").append(escapeHtml(card.title)).append("</b>\n")
        if (!card.subtitle.isNullOrBlank()) {
            sb.append("<i>").append(escapeHtml(card.subtitle)).append("</i>\n")
        }

        if (card.fields.isNotEmpty()) {
            sb.append("\n")
            for (field in card.fields) {
                sb
                    .append("▪ <b>")
                    .append(escapeHtml(field.name))
                    .append(":</b> ")
                    .append(escapeHtml(field.value))
                    .append("\n")
            }
        }

        card.mediaSpecs?.let { specs ->
            val summary = formatSpecs(specs)
            if (summary.isNotBlank()) {
                sb.append("▪ <b>Specs:</b> ").append(escapeHtml(summary)).append("\n")
            }
        }

        if (!card.overview.isNullOrBlank()) {
            sb.append("\n<i>").append(escapeHtml(card.overview)).append("</i>\n")
        }

        return sb.toString().trimEnd()
    }

    private fun formatSpecs(specs: MediaSpecs): String {
        val items = mutableListOf<String>()
        specs.resolution?.let { items.add(it) }
        specs.video?.let { items.add(it) }
        specs.audio?.let { items.add(it) }
        specs.score?.let { items.add("⭐ $it") }
        specs.duration?.let { items.add(it) }
        specs.sizeFormatted?.let { items.add(it) }
        return items.joinToString(" • ")
    }

    private fun buildProgressHtml(update: ProgressUpdate): String {
        val sb = StringBuilder()
        sb.append("<b>⏳ Downloading: ").append(escapeHtml(update.title)).append("</b>\n")
        if (!update.subtitle.isNullOrBlank()) {
            sb.append("<i>").append(escapeHtml(update.subtitle)).append("</i>\n")
        }
        sb
            .append("\n<code>")
            .append(update.progressBar)
            .append("</code> <b>")
            .append(update.percent)
            .append("%</b>\n\n")

        sb.append("▪ <b>Speed:</b> ").append(escapeHtml(update.speedFormatted))
        if (update.etaFormatted.isNotBlank()) {
            sb.append(" (ETA: ").append(escapeHtml(update.etaFormatted)).append(")")
        }
        sb.append("\n")
        sb.append("▪ <b>Transferred:</b> ").append(escapeHtml(update.sizeFormatted)).append("\n")
        sb.append("▪ <b>Peers:</b> ").append(escapeHtml(update.peersInfo)).append("\n")
        sb.append("▪ <b>Status:</b> ").append(escapeHtml(update.stateText))

        return sb.toString()
    }

    private val allowedUrlSchemes = setOf("https", "http", "tg")

    private fun isValidUrlScheme(url: String): Boolean {
        val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme in allowedUrlSchemes
    }

    private fun buildKeyboard(actions: List<ActionLink>): InlineKeyboardMarkup? {
        val validActions = actions.filter { isValidUrlScheme(it.url) }
        if (validActions.isEmpty()) return null
        val buttons =
            validActions.map { action ->
                InlineKeyboardButton(text = action.label, url = action.url)
            }
        return InlineKeyboardMarkup(inlineKeyboard = listOf(buttons))
    }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun truncateToLimit(
        text: String,
        maxChars: Int
    ): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 3) + "..."
    }
}
