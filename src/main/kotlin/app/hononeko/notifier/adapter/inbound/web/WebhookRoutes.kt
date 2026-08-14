package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.JellyfinWebhookController
import app.hononeko.notifier.adapter.inbound.web.controller.PlexWebhookController
import app.hononeko.notifier.adapter.inbound.web.controller.SchemaController
import app.hononeko.notifier.adapter.inbound.web.controller.ServarrWebhookController
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.config.ServerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
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
    schemaController: SchemaController = SchemaController()
) {
    routing {
        // Public endpoints
        get("/health") { schemaController.handleHealth(call) }
        get("/metrics") { schemaController.handleHealth(call) }

        route("/schema") {
            get("/sonarr") { schemaController.handleSonarrSchema(call) }
            get("/radarr") { schemaController.handleRadarrSchema(call) }
            get("/plex") { schemaController.handlePlexSchema(call) }
            get("/jellyfin") { schemaController.handleJellyfinSchema(call) }
        }

        // Webhook ingestion endpoints with AuthGuard
        route("/api/v1/webhook") {
            post("/sonarr") {
                withAuth(call, serverConfig.authToken) {
                    servarrController.handleSonarr(call)
                }
            }
            post("/radarr") {
                withAuth(call, serverConfig.authToken) {
                    servarrController.handleRadarr(call)
                }
            }
            post("/servarr") {
                withAuth(call, serverConfig.authToken) {
                    servarrController.handleServarr(call)
                }
            }
            post("/plex") {
                withAuth(call, serverConfig.authToken) {
                    plexController.handlePlex(call)
                }
            }
            post("/jellyfin") {
                withAuth(call, serverConfig.authToken) {
                    jellyfinController.handleJellyfin(call)
                }
            }
        }
    }
}

private suspend inline fun withAuth(
    call: ApplicationCall,
    expectedToken: String?,
    crossinline block: suspend () -> Unit
) {
    if (!AuthGuard.isAuthorized(call, expectedToken)) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WebhookReceiptDto(status = "unauthorized", message = "Invalid or missing authentication token")
        )
        return
    }
    block()
}
