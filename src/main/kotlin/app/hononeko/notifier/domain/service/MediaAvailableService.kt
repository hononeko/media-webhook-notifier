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
    private val mediaServerPort: MediaServerPort? = null
) : AnnounceMediaAvailableUseCase {
    private val logger = LoggerFactory.getLogger(MediaAvailableService::class.java)

    override suspend fun announce(payload: MediaPayload): Either<DomainError, Unit> =
        either {
            logger.info("Announcing media available for event: {} from {}", payload.eventType, payload.source)
            val card = CardFormatterService.buildAvailableCard(payload, mediaServerPort)
            val sendResult = notificationPublisher.sendCard(card)

            when (sendResult) {
                is Either.Right -> Unit
                is Either.Left -> {
                    logger.warn("Failed to publish media available notification: {}", sendResult.value)
                    raise(sendResult.value)
                }
            }
        }
}
