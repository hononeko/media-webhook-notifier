package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.DeadLetterController
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.config.ServerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Dead letter queue management: `/api/v1/dlq`, `/api/v1/dlq/{id}`, `/api/v1/dlq/{id}/replay`. */
internal fun Route.registerDeadLetterRoutes(
    controller: DeadLetterController,
    serverConfig: ServerConfig,
    rateLimiter: InboundRateLimiter
) {
    route("/api/v1/dlq") {
        get {
            withAdminAuth(call, serverConfig, rateLimiter) { controller.handleList(call) }
        }
        delete {
            withAdminAuth(call, serverConfig, rateLimiter) { controller.handleClear(call) }
        }
        get("/{id}") {
            withAdminAuth(call, serverConfig, rateLimiter) { controller.handleGet(call) }
        }
        post("/{id}/replay") {
            withAdminAuth(call, serverConfig, rateLimiter) { controller.handleReplay(call) }
        }
    }
}

/**
 * Unlike webhook ingestion, the DLQ exposes raw payloads and can trigger notifications, so it is never served
 * in unauthenticated mode: without a configured `SERVER_AUTH_TOKEN` every request is refused.
 */
private suspend inline fun withAdminAuth(
    call: ApplicationCall,
    serverConfig: ServerConfig,
    rateLimiter: InboundRateLimiter,
    crossinline block: suspend () -> Unit
) {
    // AuthGuard treats a token list with no usable entries (e.g. "" or ",") as unauthenticated mode.
    if (serverConfig.authToken.split(',').none { it.isNotBlank() }) {
        call.respond<WebhookReceiptDto>(
            HttpStatusCode.Forbidden,
            WebhookReceiptDto(
                status = "forbidden",
                message = "Dead letter management requires SERVER_AUTH_TOKEN to be configured"
            )
        )
        return
    }
    withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter, block)
}
