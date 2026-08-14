package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.dto.PlexWebhookDto
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PlexWebhookController(
    private val eventRail: EventRail,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val logger = LoggerFactory.getLogger(PlexWebhookController::class.java)

    suspend fun handlePlex(call: ApplicationCall) {
        val dto =
            try {
                val contentType = call.request.contentType()
                if (contentType.match(ContentType.MultiPart.FormData)) {
                    parseMultipartPayload(call)
                } else {
                    call.receive<PlexWebhookDto>()
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse Plex webhook payload: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    WebhookReceiptDto(status = "error", message = "Invalid Plex payload: ${e.message}")
                )
                return
            }

        if (dto == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                WebhookReceiptDto(status = "error", message = "Missing payload in multipart request")
            )
            return
        }

        val event = dto.event?.trim()
        if (event.equals("library.new", ignoreCase = true)) {
            val payload = mapToPlexLibraryNew(dto)
            val published = eventRail.publish(payload)
            if (published) {
                call.respond(
                    HttpStatusCode.Accepted,
                    WebhookReceiptDto(
                        status = "accepted",
                        message = "Plex library item queued for processing",
                        eventType = event
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    WebhookReceiptDto(status = "error", message = "Event rail queue buffer full")
                )
            }
        } else {
            logger.debug("Ignoring unsupported Plex event: {}", event)
            call.respond(
                HttpStatusCode.OK,
                WebhookReceiptDto(
                    status = "ignored",
                    message = "Plex event '$event' is ignored",
                    eventType = event
                )
            )
        }
    }

    private suspend fun parseMultipartPayload(call: ApplicationCall): PlexWebhookDto? {
        val multipart = call.receiveMultipart()
        var payloadJson: String? = null

        multipart.forEachPart { part ->
            if (part is PartData.FormItem && part.name == "payload") {
                payloadJson = part.value
            }
            part.dispose()
        }

        return payloadJson?.let { json.decodeFromString<PlexWebhookDto>(it) }
    }

    private fun mapToPlexLibraryNew(dto: PlexWebhookDto): MediaPayload.PlexLibraryNew {
        val meta = dto.Metadata
        val stream = meta?.Media?.firstOrNull()
        val durationSec = meta?.duration?.let { it / 1000 }

        return MediaPayload.PlexLibraryNew(
            source = AppSource.PLEX,
            eventType = EventType.MEDIA_AVAILABLE,
            title = meta?.title ?: "Unknown Media",
            grandParentTitle = meta?.grandparentTitle,
            parentTitle = meta?.parentTitle,
            year = meta?.year,
            summary = meta?.summary,
            rating = meta?.rating,
            durationSeconds = durationSec,
            videoCodec = stream?.videoCodec,
            audioCodec = stream?.audioCodec,
            resolution = stream?.videoResolution,
            posterUrl = meta?.thumb,
            ratingKey = meta?.ratingKey,
            serverMachineIdentifier = dto.Server?.uuid
        )
    }
}
