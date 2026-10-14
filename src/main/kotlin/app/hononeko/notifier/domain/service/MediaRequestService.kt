package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaRequestUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import org.slf4j.LoggerFactory

/**
 * Sends Seerr cards. Decisions that only admins may take (pending requests, new issues) go as direct messages to
 * [decisionRecipients] with Approve / Decline / Resolve buttons, keeping the channel informational: it learns the
 * outcome from Seerr's own approved / declined / resolved webhooks. If no admin can be reached, the card goes to the
 * channel without buttons so the request isn't lost. With no recipients (interactions off, Seerr not configured or
 * no admins), every card goes to the channel as before.
 */
class MediaRequestService(
    private val notificationPublisher: NotificationPublisherPort,
    private val decisionRecipients: List<String> = emptyList()
) : AnnounceMediaRequestUseCase {
    private val logger = LoggerFactory.getLogger(MediaRequestService::class.java)

    override suspend fun announce(payload: MediaPayload.SeerrEvent): Either<DomainError, Unit> {
        val card = CardFormatterService.buildSeerrCard(payload)
        val buttons = if (decisionRecipients.isNotEmpty()) seerrButtons(payload) else emptyList()
        if (buttons.isEmpty()) return notificationPublisher.sendCard(card).map { }

        // The fallback leaves the buttons off: in the channel, anyone could see them.
        return notificationPublisher.sendPrivatelyOrToChannel(
            card.copy(interactions = buttons + card.interactions),
            decisionRecipients,
            fallback = card,
            logger = logger
        )
    }

    private fun seerrButtons(payload: MediaPayload.SeerrEvent): List<CardInteraction> =
        when (payload.eventType) {
            EventType.REQUEST_PENDING ->
                payload.requestId?.let {
                    listOf(
                        SeerrAction.APPROVE.interaction(it),
                        SeerrAction.DECLINE.interaction(it)
                    )
                }
            EventType.ISSUE_CREATED -> payload.issueId?.let { listOf(SeerrAction.RESOLVE.interaction(it)) }
            else -> null
        }.orEmpty()
}
