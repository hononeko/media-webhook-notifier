package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.AuthGuard
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.dto.ServarrWebhookDto
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

class ServarrWebhookController(
    private val eventRail: EventRail
) {
    private val logger = LoggerFactory.getLogger(ServarrWebhookController::class.java)

    suspend fun handleSonarr(call: ApplicationCall) {
        handleServarr(call, defaultSource = AppSource.SONARR)
    }

    suspend fun handleRadarr(call: ApplicationCall) {
        handleServarr(call, defaultSource = AppSource.RADARR)
    }

    suspend fun handleServarr(
        call: ApplicationCall,
        defaultSource: AppSource = AppSource.SONARR
    ) {
        val dto =
            try {
                call.receive<ServarrWebhookDto>()
            } catch (e: Exception) {
                logger.warn("Failed to parse Servarr webhook payload: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    WebhookReceiptDto(status = "error", message = "Invalid JSON payload: ${e.message}")
                )
                return
            }

        val callerName = AuthGuard.extractCallerName(call)
        val eventType = dto.eventType?.trim()

        if (eventType.equals("Test", ignoreCase = true)) {
            val instance = dto.instanceName ?: callerName ?: defaultSource.name
            logger.info("Received Test webhook from Servarr instance: {}", instance)
            call.respond(
                HttpStatusCode.OK,
                WebhookReceiptDto(
                    status = "ok",
                    message = "Test webhook received successfully",
                    eventType = "Test"
                )
            )
            return
        }

        val source =
            when {
                dto.series != null -> AppSource.SONARR
                dto.movie != null -> AppSource.RADARR
                else -> defaultSource
            }

        logger.info(
            "Ingesting {} webhook for {} (caller: {})",
            eventType,
            source,
            callerName ?: dto.instanceName ?: "default"
        )

        val payload: MediaPayload? =
            when (eventType?.lowercase()) {
                "grab" -> mapToArrGrab(dto, source, callerName)
                "download" -> mapToArrDownload(dto, source, callerName)
                else -> {
                    logger.debug("Ignoring unsupported Servarr event type: {}", eventType)
                    null
                }
            }

        if (payload != null) {
            val published = eventRail.publish(payload)
            if (published) {
                call.respond(
                    HttpStatusCode.Accepted,
                    WebhookReceiptDto(
                        status = "accepted",
                        message = "Webhook received and queued for processing",
                        eventType = eventType
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    WebhookReceiptDto(status = "error", message = "Event rail queue buffer full")
                )
            }
        } else {
            call.respond(
                HttpStatusCode.OK,
                WebhookReceiptDto(
                    status = "ignored",
                    message = "Event type '$eventType' is ignored",
                    eventType = eventType
                )
            )
        }
    }

    private fun mapToArrGrab(
        dto: ServarrWebhookDto,
        source: AppSource,
        callerName: String?
    ): MediaPayload.ArrGrab {
        val title =
            dto.movie?.title
                ?: dto.series?.title
                ?: dto.release?.releaseTitle
                ?: "Unknown Media"

        val seriesOrMovieTitle = dto.series?.title ?: dto.movie?.title ?: title
        val poster =
            dto.series
                ?.images
                ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                ?.remoteUrl
                ?: dto.series
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.url
                ?: dto.movie
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.remoteUrl
                ?: dto.movie
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.url

        val effectiveInstanceName = dto.instanceName ?: callerName

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
        val title =
            dto.movie?.title
                ?: dto.series?.title
                ?: dto.release?.releaseTitle
                ?: "Unknown Media"

        val seriesOrMovieTitle = dto.series?.title ?: dto.movie?.title ?: title
        val poster =
            dto.series
                ?.images
                ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                ?.remoteUrl
                ?: dto.series
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.url
                ?: dto.movie
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.remoteUrl
                ?: dto.movie
                    ?.images
                    ?.firstOrNull { it.coverType.equals("poster", ignoreCase = true) }
                    ?.url

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
        val quality =
            dto.movie?.movieFile?.quality ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.quality ?: dto.release?.quality
        val sizeBytes =
            dto.movie?.movieFile?.size ?: dto.episodes
                .firstOrNull()
                ?.episodeFile
                ?.size ?: dto.release?.size

        val effectiveInstanceName = dto.instanceName ?: callerName

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
}
