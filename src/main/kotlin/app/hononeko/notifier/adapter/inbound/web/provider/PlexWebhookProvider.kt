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
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.toByteArray
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
        val (dto, thumbBytes) =
            try {
                val contentType = call.request.contentType()
                if (contentType.match(ContentType.MultiPart.FormData)) {
                    parseMultipartPayload(call)
                } else {
                    val rawText = call.receiveText()
                    json.decodeFromString(PlexWebhookDto.serializer(), rawText) to null
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse Plex webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid Plex payload: ${e.message}")
            } ?: return WebhookProcessResult.InvalidPayload("Missing payload in multipart request")

        val event = dto.event?.trim()
        val effectiveInstance = callerName?.ifBlank { null } ?: dto.server?.title?.ifBlank { null } ?: "Plex"

        return if (event.equals("library.new", ignoreCase = true)) {
            val title = dto.metadata?.title ?: "Unknown Media"
            logger.info(
                "Ingesting Plex webhook event: {} for '{}' (instance: {}, ratingKey: {}, addedAt: {})",
                event,
                title,
                effectiveInstance,
                dto.metadata?.ratingKey,
                dto.metadata?.addedAt
            )
            val payload = mapToPlexLibraryNew(dto, effectiveInstance, thumbBytes)
            WebhookProcessResult.Queued(payload, event)
        } else {
            logger.debug("Ignoring unsupported Plex event: {}", event)
            WebhookProcessResult.Ignored("Plex event '$event' is ignored", event)
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/plex.json")

    private suspend fun parseMultipartPayload(call: ApplicationCall): Pair<PlexWebhookDto, ByteArray?>? {
        val multipart = call.receiveMultipart()
        var payloadJson: String? = null
        var thumbBytes: ByteArray? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    if (part.name == "payload") {
                        payloadJson = part.value
                    }
                }
                is PartData.FileItem -> {
                    if (part.name == "thumb" || part.originalFileName?.contains("thumb", ignoreCase = true) == true) {
                        thumbBytes = part.provider().toByteArray()
                    }
                }
                is PartData.BinaryItem -> {
                    if (part.name == "thumb") {
                        thumbBytes = part.provider().readBytes()
                    }
                }
                else -> Unit
            }
            part.dispose()
        }

        val dto = payloadJson?.let { json.decodeFromString(PlexWebhookDto.serializer(), it) } ?: return null
        return dto to thumbBytes
    }

    private val seasonPattern = Regex("""(?:Season|Series|S)\s*(\d+)""", RegexOption.IGNORE_CASE)

    private fun mapToPlexLibraryNew(
        dto: PlexWebhookDto,
        instanceName: String,
        thumbBytes: ByteArray? = null
    ): MediaPayload.PlexLibraryNew {
        val meta = dto.metadata
        val stream = meta?.media?.firstOrNull()
        val durationSec = meta?.duration?.let { it / 1000 }
        val mediaType = meta?.type?.lowercase()

        val isExplicitEpisode =
            mediaType == "episode" ||
                meta?.parentIndex != null ||
                (meta?.grandparentTitle != null && meta?.parentTitle != null)

        val seasonNumber =
            if (meta != null) {
                when {
                    mediaType == "season" -> meta.index ?: extractSeasonNumber(meta.title)
                    isExplicitEpisode -> meta.parentIndex ?: extractSeasonNumber(meta.parentTitle)
                    else -> meta.index
                }
            } else {
                null
            }

        val episodeNumber =
            if (meta != null && isExplicitEpisode) {
                meta.index
            } else {
                null
            }

        val effectivePosterUrl = sanitizeHttpUrl(meta?.thumb)
        val parentPosterUrl = sanitizeHttpUrl(meta?.parentThumb)
        val grandparentPosterUrl = sanitizeHttpUrl(meta?.grandparentThumb)

        val rawAddedAt = meta?.addedAt
        val effectiveAddedAt =
            when {
                rawAddedAt == null -> null
                rawAddedAt > 100_000_000_000L -> rawAddedAt / 1000L
                else -> rawAddedAt
            }

        return MediaPayload.PlexLibraryNew(
            source = AppSource.PLEX,
            eventType = EventType.MEDIA_AVAILABLE,
            title = meta?.title ?: "Unknown Media",
            mediaType = mediaType,
            grandParentTitle = meta?.grandparentTitle,
            parentTitle = meta?.parentTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            year = meta?.year ?: meta?.parentYear,
            summary = meta?.summary,
            rating = meta?.rating,
            durationSeconds = durationSec,
            videoCodec = stream?.videoCodec,
            audioCodec = stream?.audioCodec,
            resolution = stream?.videoResolution,
            posterUrl = effectivePosterUrl,
            parentPosterUrl = parentPosterUrl,
            grandparentPosterUrl = grandparentPosterUrl,
            artworkBytes = thumbBytes,
            ratingKey = meta?.ratingKey,
            addedAt = effectiveAddedAt,
            serverMachineIdentifier = dto.server?.uuid,
            instanceName = instanceName
        )
    }

    private fun sanitizeHttpUrl(url: String?): String? =
        url?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    private fun extractSeasonNumber(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        val match = seasonPattern.find(title)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
