package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.JellyfinWebhookController
import app.hononeko.notifier.adapter.inbound.web.controller.PlexWebhookController
import app.hononeko.notifier.adapter.inbound.web.controller.SchemaController
import app.hononeko.notifier.adapter.inbound.web.controller.ServarrWebhookController
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.config.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureWebhookRouting(
    eventRail: EventRail,
    serverConfig: ServerConfig,
    servarrController: ServarrWebhookController = ServarrWebhookController(eventRail),
    plexController: PlexWebhookController = PlexWebhookController(eventRail),
    jellyfinController: JellyfinWebhookController = JellyfinWebhookController(eventRail),
    schemaController: SchemaController = SchemaController(),
    rateLimiter: InboundRateLimiter = InboundRateLimiter(serverConfig.rateLimitPerMinute)
) {
    routing {
        // Public health & schema endpoints
        get("/health") { schemaController.handleHealth(call) }
        get("/metrics") { schemaController.handleHealth(call) }

        route("/schema") {
            get("/sonarr") { schemaController.handleSonarrSchema(call) }
            get("/radarr") { schemaController.handleRadarrSchema(call) }
            get("/plex") { schemaController.handlePlexSchema(call) }
            get("/jellyfin") { schemaController.handleJellyfinSchema(call) }
        }

        // Standard webhook ingestion endpoints
        route("/api/v1/webhook") {
            registerWebhookEndpoints(
                serverConfig = serverConfig,
                rateLimiter = rateLimiter,
                servarrController = servarrController,
                plexController = plexController,
                jellyfinController = jellyfinController
            )

            // Flexible path-based token endpoints: /api/v1/webhook/{token}/*
            route("/{token}") {
                registerWebhookEndpoints(
                    serverConfig = serverConfig,
                    rateLimiter = rateLimiter,
                    servarrController = servarrController,
                    plexController = plexController,
                    jellyfinController = jellyfinController
                )
            }
        }
    }
}

private fun Route.registerWebhookEndpoints(
    serverConfig: ServerConfig,
    rateLimiter: InboundRateLimiter,
    servarrController: ServarrWebhookController,
    plexController: PlexWebhookController,
    jellyfinController: JellyfinWebhookController
) {
    post("/sonarr") {
        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            servarrController.handleSonarr(call)
        }
    }
    post("/radarr") {
        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            servarrController.handleRadarr(call)
        }
    }
    post("/servarr") {
        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            servarrController.handleServarr(call)
        }
    }
    post("/plex") {
        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            plexController.handlePlex(call)
        }
    }
    post("/jellyfin") {
        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            jellyfinController.handleJellyfin(call)
        }
    }
}

private suspend inline fun withAuthAndRateLimit(
    call: ApplicationCall,
    expectedToken: String?,
    rateLimiter: InboundRateLimiter,
    crossinline block: suspend () -> Unit
) {
    if (!rateLimiter.tryAcquire(call)) {
        call.response.header(HttpHeaders.RetryAfter, "60")
        call.respond(
            HttpStatusCode.TooManyRequests,
            WebhookReceiptDto(
                status = "rate_limited",
                message = "Rate limit exceeded. Please try again later."
            )
        )
        return
    }

    if (!AuthGuard.isAuthorized(call, expectedToken)) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WebhookReceiptDto(
                status = "unauthorized",
                message = "Invalid or missing authentication token"
            )
        )
        return
    }

    block()
}
