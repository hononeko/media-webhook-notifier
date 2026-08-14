package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

class MediaImportedService(
    private val notificationPublisher: NotificationPublisherPort
) : AnnounceMediaImportedUseCase {
    private val logger = LoggerFactory.getLogger(MediaImportedService::class.java)

    override suspend fun announce(payload: MediaPayload.ArrDownload): Either<DomainError, Unit> =
        either {
            logger.info(
                "Announcing media import for {} ({}) - Upgrade: {}",
                payload.title,
                payload.source,
                payload.isUpgrade
            )
            val card = CardFormatterService.buildImportCard(payload)
            val sendResult = notificationPublisher.sendCard(card)

            when (sendResult) {
                is Either.Right -> Unit
                is Either.Left -> {
                    logger.warn("Failed to publish media import notification: {}", sendResult.value)
                    raise(sendResult.value)
                }
            }
        }
}
