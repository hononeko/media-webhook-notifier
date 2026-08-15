package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceSystemHealthUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

class SystemHealthService(
    private val notificationPublisher: NotificationPublisherPort
) : AnnounceSystemHealthUseCase {
    private val logger = LoggerFactory.getLogger(SystemHealthService::class.java)

    override suspend fun announce(payload: MediaPayload.ServarrHealth): Either<DomainError, Unit> =
        either {
            val card = CardFormatterService.buildHealthCard(payload)
            logger.info("Publishing system health card: {} [{}]", card.title, card.level)
            notificationPublisher.sendCard(card).bind()
        }
}
