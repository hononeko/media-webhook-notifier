package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceManualInteractionUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

class ManualInteractionService(
    private val notificationPublisher: NotificationPublisherPort
) : AnnounceManualInteractionUseCase {
    private val logger = LoggerFactory.getLogger(ManualInteractionService::class.java)

    override suspend fun announce(payload: MediaPayload.ServarrManualInteraction): Either<DomainError, Unit> =
        either {
            val card = CardFormatterService.buildManualInteractionCard(payload)
            logger.info("Publishing manual interaction required card: {}", card.title)
            notificationPublisher.sendCard(card).bind()
        }
}
