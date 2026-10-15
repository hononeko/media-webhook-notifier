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
 * card goes privately to the admins: issues with their Acknowledge and Snooze buttons, "health restored" as the outcome
 * next to them. If no admin can be reached, the channel gets an informational copy without buttons.
 *
 * Cards for a check an admin snoozed ([snoozes]) are dropped, issue and restored alike, until the snooze ends.
 */
class SystemHealthService(
    private val notificationPublisher: NotificationPublisherPort,
    private val adminRecipients: List<String> = emptyList(),
    private val snoozes: HealthSnoozes? = null
) : AnnounceSystemHealthUseCase {
    private val logger = LoggerFactory.getLogger(SystemHealthService::class.java)

    override suspend fun announce(payload: MediaPayload.ServarrHealth): Either<DomainError, Unit> =
        either {
            val check = HealthSnoozes.checkOf(payload)
            if (snoozes?.isSnoozed(check) == true) {
                logger.info("Dropping {} card for snoozed health check '{}'", payload.eventType, check)
                return@either
            }
            val built = ServarrCards.buildHealthCard(payload)
            // Snooze rides along with Acknowledge on issues, and only on the admins' private copies.
            val needsAction = CardTemplates.ACKNOWLEDGE in built.interactions
            val card =
                if (snoozes != null && needsAction && adminRecipients.isNotEmpty()) {
                    built.copy(interactions = built.interactions + SnoozeHandler.button(check))
                } else {
                    built
                }
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
