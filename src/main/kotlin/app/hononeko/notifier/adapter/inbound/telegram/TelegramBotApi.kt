package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/**
 * The Bot API calls the inbound side needs: long polling, callback answers and webhook registration.
 * Failures map to [DomainError.NotificationError]; [DomainError.NotificationError.DeliveryFailed.httpStatus] lets
 * callers tell a 409 conflict or a rejected token apart from network trouble.
 */
internal class TelegramBotApi(
    private val botToken: String,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = "https://api.telegram.org"
) : AutoCloseable {
    companion object {
        private const val PROVIDER = "telegram"
        private const val MAX_ANSWER_CHARS = 200
        private const val DEFAULT_RETRY_AFTER_SECONDS = 5
        private const val LONG_POLL_MARGIN_SECONDS = 10
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val SOCKET_TIMEOUT_MILLIS = 10_000L
        private val CALLBACK_UPDATES_ONLY = listOf("callback_query")
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val httpClient =
        if (engine != null) HttpClient(engine) { installTimeouts() } else HttpClient(CIO) { installTimeouts() }

    private fun HttpClientConfig<*>.installTimeouts() {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
    }

    @Serializable
    private data class Response<T>(
        val ok: Boolean,
        val result: T? = null,
        val description: String? = null,
        @SerialName("error_code")
        val errorCode: Int? = null,
        val parameters: Parameters? = null
    )

    @Serializable
    private data class Parameters(
        @SerialName("retry_after")
        val retryAfter: Int? = null
    )

    @Serializable
    private data class GetUpdatesRequest(
        val offset: Long?,
        val timeout: Int,
        @SerialName("allowed_updates")
        val allowedUpdates: List<String>
    )

    @Serializable
    private data class AnswerCallbackQueryRequest(
        @SerialName("callback_query_id")
        val callbackQueryId: String,
        val text: String,
        @SerialName("show_alert")
        val showAlert: Boolean
    )

    @Serializable
    private data class SetWebhookRequest(
        val url: String,
        @SerialName("secret_token")
        val secretToken: String,
        @SerialName("allowed_updates")
        val allowedUpdates: List<String>
    )

    @Serializable
    private data class DeleteWebhookRequest(
        @SerialName("drop_pending_updates")
        val dropPendingUpdates: Boolean
    )

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int
    ): Either<DomainError.NotificationError, List<TelegramUpdate>> {
        val longPollMillis = (timeoutSeconds + LONG_POLL_MARGIN_SECONDS) * 1_000L
        return call(
            method = "getUpdates",
            body =
                json.encodeToString(
                    GetUpdatesRequest.serializer(),
                    GetUpdatesRequest(offset, timeoutSeconds, CALLBACK_UPDATES_ONLY)
                ),
            resultSerializer = ListSerializer(TelegramUpdate.serializer())
        ) {
            timeout {
                requestTimeoutMillis = longPollMillis
                socketTimeoutMillis = longPollMillis
            }
        }.map { it.orEmpty() }
    }

    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String,
        showAlert: Boolean
    ): Either<DomainError.NotificationError, Unit> =
        call(
            method = "answerCallbackQuery",
            body =
                json.encodeToString(
                    AnswerCallbackQueryRequest.serializer(),
                    AnswerCallbackQueryRequest(callbackQueryId, text.take(MAX_ANSWER_CHARS), showAlert)
                ),
            resultSerializer = JsonElement.serializer()
        ).map { }

    suspend fun setWebhook(
        url: String,
        secretToken: String
    ): Either<DomainError.NotificationError, Unit> =
        call(
            method = "setWebhook",
            body =
                json.encodeToString(
                    SetWebhookRequest.serializer(),
                    SetWebhookRequest(url, secretToken, CALLBACK_UPDATES_ONLY)
                ),
            resultSerializer = JsonElement.serializer()
        ).map { }

    /** Removes any registered webhook but keeps queued updates, so clicks made during a mode switch are not lost. */
    suspend fun deleteWebhook(): Either<DomainError.NotificationError, Unit> =
        call(
            method = "deleteWebhook",
            body =
                json.encodeToString(
                    DeleteWebhookRequest.serializer(),
                    DeleteWebhookRequest(dropPendingUpdates = false)
                ),
            resultSerializer = JsonElement.serializer()
        ).map { }

    override fun close() = httpClient.close()

    private suspend fun <T> call(
        method: String,
        body: String,
        resultSerializer: KSerializer<T>,
        configure: HttpRequestBuilder.() -> Unit = {}
    ): Either<DomainError.NotificationError, T?> {
        val (status, raw) =
            try {
                val response =
                    httpClient.post("$baseUrl/bot$botToken/$method") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                        configure()
                    }
                response.status to response.bodyAsText()
            } catch (e: UnresolvedAddressException) {
                // CIO reports DNS failures with this IllegalArgumentException, not an IOException.
                // Safe to keep as the cause: it carries no message, so no URL and no bot token.
                return Either.Left(
                    DomainError.NotificationError.DeliveryFailed(
                        PROVIDER,
                        "$method: cannot resolve the Bot API host",
                        cause = e
                    )
                )
            } catch (e: IOException) {
                // Ktor puts the request URL, and with it the bot token, into exception messages. Drop the cause.
                return Either.Left(
                    DomainError.NotificationError.DeliveryFailed(PROVIDER, "$method: ${redact(e.message)}")
                )
            }

        val parsed = runCatching { json.decodeFromString(Response.serializer(resultSerializer), raw) }.getOrNull()
        if (status == HttpStatusCode.TooManyRequests) {
            val retryAfter = parsed?.parameters?.retryAfter ?: DEFAULT_RETRY_AFTER_SECONDS
            return Either.Left(DomainError.NotificationError.RateLimited(PROVIDER, retryAfter))
        }
        if (!status.isSuccess() || parsed?.ok != true) {
            val code = parsed?.errorCode ?: status.value.takeIf { !status.isSuccess() }
            val description = parsed?.description ?: redact(raw.take(200))
            return Either.Left(
                DomainError.NotificationError.DeliveryFailed(
                    PROVIDER,
                    "$method: HTTP ${status.value}: $description",
                    httpStatus = code
                )
            )
        }
        return Either.Right(parsed.result)
    }

    private fun redact(text: String?): String = text.orEmpty().replace(botToken, "<redacted>")
}
