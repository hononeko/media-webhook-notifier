package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaRequestUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either

/**
 * Sends Seerr cards. With [seerrActions] on (interactions enabled, Seerr credentials and at least one admin
 * configured), pending requests get Approve / Decline buttons and new issues a Resolve button.
 */
class MediaRequestService(
    private val notificationPublisher: NotificationPublisherPort,
    private val seerrActions: Boolean = false
) : AnnounceMediaRequestUseCase {
    override suspend fun announce(payload: MediaPayload.SeerrEvent): Either<DomainError, Unit> {
        val card = CardFormatterService.buildSeerrCard(payload)
        val buttons = if (seerrActions) seerrButtons(payload) else emptyList()
        return notificationPublisher.sendCard(card.copy(interactions = buttons + card.interactions)).map { }
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
