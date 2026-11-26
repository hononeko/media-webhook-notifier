package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceCrossSeedResultUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceManualInteractionUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaRequestUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceSystemHealthUseCase
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

/** The cards sent straight to the publisher, without download tracking or debouncing. */
data class AlertUseCases(
    val systemHealth: AnnounceSystemHealthUseCase? = null,
    val manualInteraction: AnnounceManualInteractionUseCase? = null,
    val mediaRequest: AnnounceMediaRequestUseCase? = null,
    val crossSeed: AnnounceCrossSeedResultUseCase? = null
)

class IngestWebhookService(
    private val trackDownloadUseCase: TrackDownloadUseCase,
    private val announceMediaImportedUseCase: AnnounceMediaImportedUseCase,
    private val announceMediaAvailableUseCase: AnnounceMediaAvailableUseCase,
    private val seasonDebouncer: SeasonDebouncer? = null,
    private val alertUseCases: AlertUseCases = AlertUseCases(),
    // Whether a template event is announced at all (`enabled: false` in the templates turns it off).
    private val isEventEnabled: (String) -> Boolean = { CardTemplates.templateEngine.isEnabled(it) },
    private val crossSeedDebouncer: CrossSeedDebouncer? = null
) : IngestWebhookUseCase {
    private val logger = LoggerFactory.getLogger(IngestWebhookService::class.java)
    private var eventDeduplicator: EventDeduplicator? = null

    fun withDeduplicator(deduplicator: EventDeduplicator): IngestWebhookService =
        apply {
            this.eventDeduplicator = deduplicator
        }

    override suspend fun execute(payload: MediaPayload): Either<DomainError, Unit> =
        either {
            logger.info("Ingesting webhook payload: {} ({})", payload.eventType, payload.source)

            val templateEvent = templateEventName(payload)
            if (!isEventEnabled(templateEvent)) {
                logger.info("Skipping {} from {}: '{}' is disabled", payload.eventType, payload.source, templateEvent)
                return@either
            }

            if (eventDeduplicator?.tryAcquire(payload) == false) {
                logger.info(
                    "Skipping duplicate {} from {}: {}",
                    payload.eventType,
                    payload.source,
                    eventDeduplicator?.computeKey(payload)
                )
                return@either
            }

            dispatch(payload).bind()
        }

    private suspend fun dispatch(payload: MediaPayload): Either<DomainError, Unit> =
        either {
            when (payload) {
                is MediaPayload.ArrGrab -> dispatchGrab(payload).bind()
                is MediaPayload.ArrDownload -> dispatchDownload(payload).bind()
                is MediaPayload.PlexLibraryNew,
                is MediaPayload.JellyfinItemAdded -> {
                    if (seasonDebouncer != null && seasonDebouncer.supportsAvailable) {
                        logger.debug("Routing {} to SeasonDebouncer for: {}", payload.eventType, payload.source)
                        seasonDebouncer.submit(payload)
                    } else {
                        announceMediaAvailableUseCase.announce(payload).bind()
                    }
                }
                is MediaPayload.ServarrHealth -> alertUseCases.systemHealth?.announce(payload)?.bind()
                is MediaPayload.ServarrManualInteraction -> alertUseCases.manualInteraction?.announce(payload)?.bind()
                is MediaPayload.SeerrEvent -> alertUseCases.mediaRequest?.announce(payload)?.bind()
                is MediaPayload.CrossSeedResult -> dispatchCrossSeed(payload).bind()
            }
        }

    private suspend fun dispatchCrossSeed(payload: MediaPayload.CrossSeedResult): Either<DomainError, Unit> =
        either {
            if (crossSeedDebouncer != null && payload.eventType != EventType.CROSS_SEED_FAILED) {
                logger.debug("Routing {} to CrossSeedDebouncer for: {}", payload.eventType, payload.name)
                crossSeedDebouncer.submit(payload)
            } else {
                alertUseCases.crossSeed?.announce(payload)?.bind()
            }
        }

    private suspend fun dispatchGrab(payload: MediaPayload.ArrGrab): Either<DomainError, Unit> =
        either {
            if (seasonDebouncer != null) {
                logger.debug("Routing ArrGrab to SeasonDebouncer for hash: {}", payload.downloadId)
                seasonDebouncer.submit(payload)
            } else {
                trackDownloadUseCase
                    .track(payload.downloadId, payload)
                    .onLeft { eventDeduplicator?.release(payload) }
                    .bind()
            }
        }

    private suspend fun dispatchDownload(payload: MediaPayload.ArrDownload): Either<DomainError, Unit> =
        either {
            if (seasonDebouncer != null) {
                logger.debug(
                    "Routing ArrDownload to SeasonDebouncer for: {} (season {})",
                    payload.seriesOrMovieTitle,
                    payload.seasonNumber
                )
                seasonDebouncer.submit(payload)
            } else {
                announceMediaImportedUseCase
                    .announce(payload)
                    .onLeft { eventDeduplicator?.release(payload) }
                    .bind()
            }
        }
}

/** The template event that announces [payload], whose `enabled` flag decides whether it's announced at all. */
internal fun templateEventName(payload: MediaPayload): String =
    when (payload) {
        is MediaPayload.ArrGrab -> "grab"
        is MediaPayload.ArrDownload -> "import"
        is MediaPayload.PlexLibraryNew, is MediaPayload.JellyfinItemAdded -> "media_available"
        is MediaPayload.ServarrHealth -> "health"
        is MediaPayload.ServarrManualInteraction -> "manual_interaction"
        is MediaPayload.SeerrEvent -> SeerrCards.templateEventName(payload)
        is MediaPayload.CrossSeedResult -> CrossSeedCards.templateEventName(payload)
    }
