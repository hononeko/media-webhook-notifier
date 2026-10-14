package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceSystemHealthUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

/**
 * Sends Sonarr / Radarr health cards. With [adminRecipients] set (interactions on and admins configured), every health
 * card goes privately to the admins: issues with their Acknowledge button, "health restored" as the outcome next to
 * them. If no admin can be reached, the channel gets an informational copy without buttons.
 */
class SystemHealthService(
    private val notificationPublisher: NotificationPublisherPort,
    private val adminRecipients: List<String> = emptyList()
) : AnnounceSystemHealthUseCase {
    private val logger = LoggerFactory.getLogger(SystemHealthService::class.java)

    override suspend fun announce(payload: MediaPayload.ServarrHealth): Either<DomainError, Unit> =
        either {
            val card = CardFormatterService.buildHealthCard(payload)
            if (adminRecipients.isNotEmpty()) {
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
