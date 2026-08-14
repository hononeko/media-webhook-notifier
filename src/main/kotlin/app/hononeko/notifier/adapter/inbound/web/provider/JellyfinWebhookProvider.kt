package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.JellyfinWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import org.slf4j.LoggerFactory

class JellyfinWebhookProvider : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(JellyfinWebhookProvider::class.java)

    override val providerKeys: Set<String> = setOf("jellyfin", "emby")

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val dto =
            try {
                call.receive<JellyfinWebhookDto>()
            } catch (e: Exception) {
                logger.warn("Failed to parse Jellyfin webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid Jellyfin payload: ${e.message}")
            }

        val notificationType = dto.notificationType?.trim()
        logger.info(
            "Ingesting Jellyfin webhook event: {} (server: {}, caller: {})",
            notificationType,
            dto.serverName ?: dto.serverId ?: "unknown",
            callerName ?: "default"
        )

        return if (notificationType.equals("ItemAdded", ignoreCase = true)) {
            val payload = mapToJellyfinItemAdded(dto)
            WebhookProcessResult.Queued(payload, notificationType)
        } else {
            logger.debug("Ignoring unsupported Jellyfin notification type: {}", notificationType)
            WebhookProcessResult.Ignored("Jellyfin notification type '$notificationType' is ignored", notificationType)
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/jellyfin.json")

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
