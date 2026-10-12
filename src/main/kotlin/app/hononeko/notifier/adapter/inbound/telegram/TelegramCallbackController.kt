package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.config.TelegramInteractionConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Receives Telegram webhook deliveries. Telegram cannot send `SERVER_AUTH_TOKEN`, so requests are authenticated by
 * the `secret_token` registered with `setWebhook`. Everything after that check answers 200 at once and is processed
 * in [scope]: Telegram delivers updates one at a time and retries any non-2xx response for up to a day.
 */
internal class TelegramCallbackController(
    webhookSecret: String,
    private val processor: TelegramCallbackProcessor,
    private val scope: CoroutineScope
) {
    companion object {
        const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
    }

    private val logger = LoggerFactory.getLogger(TelegramCallbackController::class.java)
    private val expectedSecret = webhookSecret.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(call: ApplicationCall) {
        val presented = call.request.header(SECRET_HEADER)?.toByteArray(Charsets.UTF_8)
        if (presented == null || !MessageDigest.isEqual(presented, expectedSecret)) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val body = call.receiveText()
        val update =
            runCatching { json.decodeFromString(TelegramUpdate.serializer(), body) }
                .onFailure { logger.warn("Ignoring unreadable Telegram webhook update: {}", it.message) }
                .getOrNull()
        update?.callbackQuery?.let { query -> scope.launch { processSafely(query) } }
        call.respond(HttpStatusCode.OK)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processSafely(query: TelegramCallbackQuery) {
        try {
            processor.process(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Processing Telegram callback {} failed: {}", query.id, e.message, e)
        }
    }
}

/** `POST /api/v1/telegram/webhook`, rate limited like the other inbound endpoints. */
internal fun Route.registerTelegramWebhookRoute(
    controller: TelegramCallbackController,
    rateLimiter: InboundRateLimiter
) {
    post(TelegramInteractionConfig.WEBHOOK_PATH) {
        if (!rateLimiter.tryAcquire(call)) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }
        controller.handle(call)
    }
}
