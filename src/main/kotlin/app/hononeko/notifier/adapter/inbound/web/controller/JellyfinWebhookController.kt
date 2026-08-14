package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.AuthGuard
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.dto.JellyfinWebhookDto
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

class JellyfinWebhookController(
    private val eventRail: EventRail
) {
    private val logger = LoggerFactory.getLogger(JellyfinWebhookController::class.java)

    suspend fun handleJellyfin(call: ApplicationCall) {
        val dto =
            try {
                call.receive<JellyfinWebhookDto>()
            } catch (e: Exception) {
                logger.warn("Failed to parse Jellyfin webhook payload: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    WebhookReceiptDto(status = "error", message = "Invalid Jellyfin payload: ${e.message}")
                )
                return
            }

        val callerName = AuthGuard.extractCallerName(call)
        val notificationType = dto.notificationType?.trim()
        logger.info(
            "Ingesting Jellyfin webhook event: {} (server: {}, caller: {})",
            notificationType,
            dto.serverName ?: dto.serverId ?: "unknown",
            callerName ?: "default"
        )

        if (notificationType.equals("ItemAdded", ignoreCase = true)) {
            val payload = mapToJellyfinItemAdded(dto)
            val published = eventRail.publish(payload)
            if (published) {
                call.respond(
                    HttpStatusCode.Accepted,
                    WebhookReceiptDto(
                        status = "accepted",
                        message = "Jellyfin item queued for processing",
                        eventType = notificationType
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    WebhookReceiptDto(status = "error", message = "Event rail queue buffer full")
                )
            }
        } else {
            logger.debug("Ignoring unsupported Jellyfin notification type: {}", notificationType)
            call.respond(
                HttpStatusCode.OK,
                WebhookReceiptDto(
                    status = "ignored",
                    message = "Jellyfin notification type '$notificationType' is ignored",
                    eventType = notificationType
                )
            )
        }
    }

    private fun mapToJellyfinItemAdded(dto: JellyfinWebhookDto): MediaPayload.JellyfinItemAdded =
        MediaPayload.JellyfinItemAdded(
            source = AppSource.JELLYFIN,
            eventType = EventType.MEDIA_AVAILABLE,
            itemId = dto.itemId ?: "",
            serverId = dto.serverId,
            title = dto.name ?: "Unknown Media",
            seriesName = dto.seriesName,
            seasonNumber = dto.seasonNumber,
            episodeNumber = dto.episodeNumber,
            year = dto.year,
            overview = dto.overview,
            videoCodec = dto.videoCodec,
            audioCodec = dto.audioCodec,
            resolution = dto.resolution,
            posterUrl = dto.posterUrl
        )
}
