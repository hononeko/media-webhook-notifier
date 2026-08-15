package app.hononeko.notifier.domain.port.inbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import arrow.core.Either

fun interface AnnounceMediaRequestUseCase {
    suspend fun announce(payload: MediaPayload.SeerrEvent): Either<DomainError, Unit>
}
