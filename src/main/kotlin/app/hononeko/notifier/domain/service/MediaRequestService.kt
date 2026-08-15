package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaRequestUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either

class MediaRequestService(
    private val notificationPublisher: NotificationPublisherPort
) : AnnounceMediaRequestUseCase {
    override suspend fun announce(payload: MediaPayload.SeerrEvent): Either<DomainError, Unit> {
        val card = CardFormatterService.buildSeerrCard(payload)
        return notificationPublisher.sendCard(card).map { }
    }
}
