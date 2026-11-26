package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceCrossSeedResultUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import org.slf4j.LoggerFactory

/** Sends cross-seed match and failure cards to the channel, or wherever `cross_seed.*` templates route them. */
class CrossSeedService(
    private val notificationPublisher: NotificationPublisherPort
) : AnnounceCrossSeedResultUseCase {
    private val logger = LoggerFactory.getLogger(CrossSeedService::class.java)

    override suspend fun announce(payload: MediaPayload.CrossSeedResult): Either<DomainError, Unit> {
        val card = CrossSeedCards.buildCard(payload)
        logger.info("Publishing cross-seed card: {} [{}]", card.title, card.level)
        return notificationPublisher.sendCard(card).map { }
    }
}
