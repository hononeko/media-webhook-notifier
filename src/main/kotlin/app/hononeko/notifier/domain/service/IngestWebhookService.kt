package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

class IngestWebhookService(
    private val seasonDebouncer: SeasonDebouncer? = null,
    private val trackDownloadUseCase: TrackDownloadUseCase,
    private val announceMediaImportedUseCase: AnnounceMediaImportedUseCase,
    private val announceMediaAvailableUseCase: AnnounceMediaAvailableUseCase
) : IngestWebhookUseCase {
    private val logger = LoggerFactory.getLogger(IngestWebhookService::class.java)

    override suspend fun execute(payload: MediaPayload): Either<DomainError, Unit> =
        either {
            logger.info("Ingesting webhook payload: {} ({})", payload.eventType, payload.source)

            when (payload) {
                is MediaPayload.ArrGrab -> {
                    if (seasonDebouncer != null) {
                        logger.debug("Routing ArrGrab to SeasonDebouncer for hash: {}", payload.downloadId)
                        seasonDebouncer.submit(payload)
                    } else {
                        trackDownloadUseCase.track(payload.downloadId, payload).bind()
                    }
                }
                is MediaPayload.ArrDownload -> {
                    announceMediaImportedUseCase.announce(payload).bind()
                }
                is MediaPayload.PlexLibraryNew,
                is MediaPayload.JellyfinItemAdded -> {
                    announceMediaAvailableUseCase.announce(payload).bind()
                }
            }
        }
}
