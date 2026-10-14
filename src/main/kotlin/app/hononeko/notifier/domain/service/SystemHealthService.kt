package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceSystemHealthUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

/**
 * Sends Sonarr / Radarr health cards. Health issues need someone to act, so with [adminRecipients] set (interactions
 * on and admins configured) they go privately to the admins with their Acknowledge button. If no admin can be
 * reached, the channel gets an informational copy without buttons. "Health restored" cards are informational and always go to the channel.
 */
class SystemHealthService(
    private val notificationPublisher: NotificationPublisherPort,
    private val adminRecipients: List<String> = emptyList()
) : AnnounceSystemHealthUseCase {
    private val logger = LoggerFactory.getLogger(SystemHealthService::class.java)

    override suspend fun announce(payload: MediaPayload.ServarrHealth): Either<DomainError, Unit> =
        either {
            val card = CardFormatterService.buildHealthCard(payload)
            // Only issues carry Acknowledge; restored cards have nothing to act on.
            val needsAction = CardFormatterService.ACKNOWLEDGE in card.interactions
            if (needsAction && adminRecipients.isNotEmpty()) {
                logger.info("Sending system health card to admins: {} [{}]", card.title, card.level)
                // Like Seerr decisions, the channel fallback is informational only: no buttons.
                val fallback = card.copy(interactions = emptyList())
                notificationPublisher.sendPrivatelyOrToChannel(card, adminRecipients, fallback, logger).bind()
            } else {
                logger.info("Publishing system health card: {} [{}]", card.title, card.level)
                notificationPublisher.sendCard(card).bind()
            }
        }
}
