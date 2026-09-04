package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

class MediaAvailableService(
    private val notificationPublisher: NotificationPublisherPort,
    private val mediaServerPort: MediaServerPort? = null,
    private val deduplicator: MediaAvailableDeduplicator? = MediaAvailableDeduplicator()
) : AnnounceMediaAvailableUseCase {
    private val logger = LoggerFactory.getLogger(MediaAvailableService::class.java)

    override suspend fun announce(payload: MediaPayload): Either<DomainError, Unit> =
        either {
            val title =
                when (payload) {
                    is MediaPayload.PlexLibraryNew -> payload.title
                    is MediaPayload.JellyfinItemAdded -> payload.title
                    else -> "Media"
                }

            if (deduplicator != null && !deduplicator.tryAcquire(payload)) {
                val key = deduplicator.computeKey(payload)
                logger.info(
                    "Skipping already announced or stale media available event for '{}': {} from {}",
                    title,
                    key,
                    payload.source
                )
                return@either
            }

            logger.info("Announcing media available for '{}' ({}) from {}", title, payload.eventType, payload.source)
            val card = CardFormatterService.buildAvailableCard(payload, mediaServerPort)
            val sendResult = notificationPublisher.sendCard(card)

            when (sendResult) {
                is Either.Right -> Unit
                is Either.Left -> {
                    deduplicator?.release(payload)
                    logger.warn("Failed to publish media available notification: {}", sendResult.value)
                    raise(sendResult.value)
                }
            }
        }
}
