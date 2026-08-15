package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.JellyfinWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class JellyfinWebhookProvider : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(JellyfinWebhookProvider::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override val providerKeys: Set<String> = setOf("jellyfin", "emby")

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val rawText =
            try {
                call.receiveText()
            } catch (e: Exception) {
                logger.warn("Failed to read Jellyfin webhook request body: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid request body: ${e.message}")
            }

        val dto =
            try {
                json.decodeFromString(JellyfinWebhookDto.serializer(), rawText)
            } catch (e: Exception) {
                logger.warn("Failed to parse Jellyfin webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid Jellyfin payload: ${e.message}")
            }

        val notificationType = dto.notificationType?.trim()
        val effectiveInstance = callerName?.ifBlank { null } ?: dto.serverName?.ifBlank { null } ?: "Jellyfin"
        logger.info(
            "Ingesting Jellyfin webhook event: {} (instance: {})",
            notificationType,
            effectiveInstance
        )

        return if (notificationType.equals("ItemAdded", ignoreCase = true)) {
            val payload = mapToJellyfinItemAdded(dto, effectiveInstance)
            WebhookProcessResult.Queued(payload, notificationType)
        } else {
            logger.debug("Ignoring unsupported Jellyfin notification type: {}", notificationType)
            WebhookProcessResult.Ignored("Jellyfin notification type '$notificationType' is ignored", notificationType)
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/jellyfin.json")

    private fun mapToJellyfinItemAdded(
        dto: JellyfinWebhookDto,
        instanceName: String
    ): MediaPayload.JellyfinItemAdded =
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
            posterUrl = dto.posterUrl,
            instanceName = instanceName
        )
}
