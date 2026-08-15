package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.PlexWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PlexWebhookProvider(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(PlexWebhookProvider::class.java)

    override val providerKeys: Set<String> = setOf("plex")

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val dto =
            try {
                val contentType = call.request.contentType()
                if (contentType.match(ContentType.MultiPart.FormData)) {
                    parseMultipartPayload(call)
                } else {
                    val rawText = call.receiveText()
                    json.decodeFromString(PlexWebhookDto.serializer(), rawText)
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse Plex webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid Plex payload: ${e.message}")
            }

        if (dto == null) {
            return WebhookProcessResult.InvalidPayload("Missing payload in multipart request")
        }

        val event = dto.event?.trim()
        logger.info(
            "Ingesting Plex webhook event: {} (server: {}, caller: {})",
            event,
            dto.server?.title ?: "unknown",
            callerName ?: "default"
        )

        return if (event.equals("library.new", ignoreCase = true)) {
            val payload = mapToPlexLibraryNew(dto)
            WebhookProcessResult.Queued(payload, event)
        } else {
            logger.debug("Ignoring unsupported Plex event: {}", event)
            WebhookProcessResult.Ignored("Plex event '$event' is ignored", event)
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/plex.json")

    private suspend fun parseMultipartPayload(call: ApplicationCall): PlexWebhookDto? {
        val multipart = call.receiveMultipart()
        var payloadJson: String? = null

        multipart.forEachPart { part ->
            if (part is PartData.FormItem && part.name == "payload") {
                payloadJson = part.value
            }
            part.dispose()
        }

        return payloadJson?.let { json.decodeFromString(PlexWebhookDto.serializer(), it) }
    }

    private fun mapToPlexLibraryNew(dto: PlexWebhookDto): MediaPayload.PlexLibraryNew {
        val meta = dto.metadata
        val stream = meta?.media?.firstOrNull()
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
            serverMachineIdentifier = dto.server?.uuid
        )
    }
}
