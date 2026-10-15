package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationHandle
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException

/** Where a message goes: the configured channel (and forum topic), or a user's private chat. */
internal data class Destination(
    val chatId: String,
    val topicId: Long? = null
)

@Serializable
internal data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>>
)

@Serializable
internal data class InlineKeyboardButton(
    val text: String,
    val url: String? = null,
    @SerialName("callback_data")
    val callbackData: String? = null
)

/**
 * The Telegram Bot API calls the publisher makes: sending a card as a photo or a message, and editing its text or
 * caption. Failures come back as [DomainError.NotificationError], with the bot token redacted from network errors.
 */
internal class TelegramBotApi(
    private val botToken: String,
    private val providerId: String,
    engine: HttpClientEngine? = null
) {
    companion object {
        private const val NETWORK_ERROR_MSG = "Network error"
        private const val SEND_RETRY_AFTER_SECONDS = 30
        private const val EDIT_RETRY_AFTER_SECONDS = 5
    }

    // Logged under the publisher's name, where these calls used to live.
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val clientConfig: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) { json(jsonConfig) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 5000
            socketTimeoutMillis = 10000
        }
    }

    private val httpClient = engine?.let { HttpClient(it, clientConfig) } ?: HttpClient(CIO, clientConfig)

    private val apiBaseUrl: String
        get() = "https://api.telegram.org/bot$botToken"

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

    private val telegramResponseSerializer = TelegramResponse.serializer(MessageResult.serializer())

    suspend fun sendPhotoBytes(
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

    suspend fun sendPhoto(
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

    suspend fun sendMessage(
        destination: Destination,
        text: String,
        markup: InlineKeyboardMarkup?,
        silent: Boolean = false
    ): Either<DomainError.NotificationError, NotificationHandle> {
        val request =
            SendMessageRequest(
                chatId = destination.chatId,
                messageThreadId = destination.topicId,
                text = TelegramHtmlFormatter.truncateHtml(text, 4096),
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

    suspend fun editText(
        chatId: String,
        messageId: Long,
        text: String,
        markup: InlineKeyboardMarkup?
    ): Either<DomainError.NotificationError, Unit> {
        val request =
            EditMessageTextRequest(
                chatId = chatId,
                messageId = messageId,
                text = TelegramHtmlFormatter.truncateHtml(text, 4096),
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

    suspend fun editCaption(
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
                if (botToken.isBlank()) message else message.replace(botToken, "<redacted>")
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
}
