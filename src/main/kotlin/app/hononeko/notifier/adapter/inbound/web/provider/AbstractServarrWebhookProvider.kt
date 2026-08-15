package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.ServarrWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

abstract class AbstractServarrWebhookProvider(
    private val defaultSource: AppSource
) : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val rawText =
            try {
                call.receiveText()
            } catch (e: Exception) {
                logger.warn("Failed to read Servarr webhook request body: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid request body: ${e.message}")
            }

        val dto =
            try {
                json.decodeFromString(ServarrWebhookDto.serializer(), rawText)
            } catch (e: Exception) {
                logger.warn("Failed to parse Servarr webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid JSON payload: ${e.message}")
            }

        val eventType = dto.eventType?.trim()

        if (eventType.equals("Test", ignoreCase = true)) {
            val instance = resolveInstanceName(callerName, dto.instanceName, defaultSource)
            logger.info("Received Test webhook from Servarr instance: {}", instance)
            return WebhookProcessResult.TestOk(instance)
        }

        val source = resolveSource(dto)
        val effectiveCaller = resolveInstanceName(callerName, dto.instanceName, source)
        logger.info("Ingesting {} webhook for {} (caller: {})", eventType, source, effectiveCaller)

        val payload: MediaPayload? =
            when (eventType?.lowercase()) {
                "grab" -> mapToArrGrab(dto, source, callerName)
                "download" -> mapToArrDownload(dto, source, callerName)
                "health", "healthissue" -> mapToHealth(dto, source, callerName, isRestored = false)
                "healthrestored" -> mapToHealth(dto, source, callerName, isRestored = true)
                "manualinteractionrequired" -> mapToManualInteraction(dto, source, callerName)
                else -> {
                    logger.debug("Ignoring unsupported Servarr event type: {}", eventType)
                    null
                }
            }

        return if (payload != null) {
            WebhookProcessResult.Queued(payload, eventType)
        } else {
            WebhookProcessResult.Ignored("Event type '$eventType' is ignored", eventType)
        }
    }

    protected open fun resolveSource(dto: ServarrWebhookDto): AppSource =
        when {
            dto.series != null -> AppSource.SONARR
            dto.movie != null -> AppSource.RADARR
            else -> defaultSource
        }

    private fun mapToArrGrab(
        dto: ServarrWebhookDto,
        source: AppSource,
        callerName: String?
    ): MediaPayload.ArrGrab {
        val title = extractTitle(dto)
        val seriesOrMovieTitle = dto.series?.title ?: dto.movie?.title ?: title
        val poster = extractPosterUrl(dto)
        val effectiveInstanceName = resolveInstanceName(callerName, dto.instanceName, source)

        return MediaPayload.ArrGrab(
            source = source,
            downloadId = dto.downloadId ?: dto.release?.releaseTitle ?: "",
            title = title,
            seriesOrMovieTitle = seriesOrMovieTitle,
            seasonNumber = dto.episodes.firstOrNull()?.seasonNumber,
            episodeNumbers = dto.episodes.mapNotNull { it.episodeNumber },
            releaseGroup = dto.release?.releaseGroup,
            quality = dto.release?.quality ?: dto.release?.qualityVersion?.toString(),
            sizeBytes = dto.release?.size,
            indexer = dto.release?.indexer,
            posterUrl = poster,
            instanceName = effectiveInstanceName
        )
    }

    private fun mapToArrDownload(
        dto: ServarrWebhookDto,
        source: AppSource,
        callerName: String?
    ): MediaPayload.ArrDownload {
        val title = extractTitle(dto)
        val seriesOrMovieTitle = dto.series?.title ?: dto.movie?.title ?: title
        val poster = extractPosterUrl(dto)
        val isUpgrade = dto.isUpgrade == true || dto.upgrade?.isUpgrade == true

        val videoCodec =
            dto.movie?.movieFile?.videoCodec ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.videoCodec
        val audioCodec =
            dto.movie?.movieFile?.audioCodec ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.audioCodec
        val quality = extractDownloadQuality(dto)
        val sizeBytes = extractDownloadSizeBytes(dto)
        val effectiveInstanceName = resolveInstanceName(callerName, dto.instanceName, source)

        return MediaPayload.ArrDownload(
            source = source,
            downloadId = dto.downloadId,
            title = title,
            seriesOrMovieTitle = seriesOrMovieTitle,
            seasonNumber = dto.episodes.firstOrNull()?.seasonNumber,
            episodeNumbers = dto.episodes.mapNotNull { it.episodeNumber },
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            resolution = quality,
            quality = quality,
            isUpgrade = isUpgrade,
            sizeBytes = sizeBytes,
            posterUrl = poster,
            overview = dto.movie?.overview ?: dto.series?.overview,
            year = dto.movie?.year ?: dto.series?.year,
            instanceName = effectiveInstanceName,
            webUrl = dto.applicationUrl
        )
    }

    private fun extractTitle(dto: ServarrWebhookDto): String =
        dto.movie?.title
            ?: dto.series?.title
            ?: dto.release?.releaseTitle
            ?: "Unknown Media"

    private fun extractPosterUrl(dto: ServarrWebhookDto): String? {
        val seriesImages = dto.series?.images
        val movieImages = dto.movie?.images

        val seriesPoster = seriesImages?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
        val moviePoster = movieImages?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }

        return seriesPoster?.remoteUrl
            ?: seriesPoster?.url
            ?: moviePoster?.remoteUrl
            ?: moviePoster?.url
    }

    private fun extractDownloadQuality(dto: ServarrWebhookDto): String? =
        dto.movie?.movieFile?.quality
            ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.quality
            ?: dto.release?.quality

    private fun extractDownloadSizeBytes(dto: ServarrWebhookDto): Long? =
        dto.movie?.movieFile?.size
            ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.size
            ?: dto.release?.size

    private fun mapToHealth(
        dto: ServarrWebhookDto,
        source: AppSource,
        callerName: String?,
        isRestored: Boolean
    ): MediaPayload.ServarrHealth {
        val effectiveInstanceName = resolveInstanceName(callerName, dto.instanceName, source)
        val level =
            if (isRestored) {
                "ok"
            } else {
                dto.level ?: "warning"
            }
        val eventType =
            if (isRestored) {
                app.hononeko.notifier.domain.model.EventType.HEALTH_RESTORED
            } else {
                app.hononeko.notifier.domain.model.EventType.HEALTH_ISSUE
            }

        return MediaPayload.ServarrHealth(
            source = source,
            eventType = eventType,
            level = level,
            message = dto.message ?: "Health alert from $effectiveInstanceName",
            type = dto.type,
            wikiUrl = dto.wikiUrl,
            instanceName = effectiveInstanceName
        )
    }

    private fun mapToManualInteraction(
        dto: ServarrWebhookDto,
        source: AppSource,
        callerName: String?
    ): MediaPayload.ServarrManualInteraction {
        val title = extractTitle(dto)
        val seriesOrMovieTitle = dto.series?.title ?: dto.movie?.title ?: title
        val poster = extractPosterUrl(dto)
        val effectiveInstanceName = resolveInstanceName(callerName, dto.instanceName, source)

        return MediaPayload.ServarrManualInteraction(
            source = source,
            title = title,
            seriesOrMovieTitle = seriesOrMovieTitle,
            seasonNumber = dto.episodes.firstOrNull()?.seasonNumber,
            episodeNumbers = dto.episodes.mapNotNull { it.episodeNumber },
            releaseTitle = dto.release?.releaseTitle ?: dto.downloadId,
            quality = dto.release?.quality ?: dto.release?.qualityVersion?.toString(),
            sizeBytes = dto.release?.size,
            indexer = dto.release?.indexer,
            downloadClient = dto.downloadClient,
            downloadId = dto.downloadId,
            reason = dto.reason ?: dto.message,
            posterUrl = poster,
            webUrl = dto.applicationUrl,
            instanceName = effectiveInstanceName
        )
    }

    private fun resolveInstanceName(
        callerName: String?,
        payloadInstanceName: String?,
        source: AppSource
    ): String =
        callerName?.ifBlank { null }
            ?: payloadInstanceName?.ifBlank { null }
            ?: source.displayName
}
