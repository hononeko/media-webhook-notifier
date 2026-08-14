package app.hononeko.notifier.domain.port.inbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import arrow.core.Either

fun interface TrackDownloadUseCase {
    suspend fun track(
        hash: String,
        initialPayload: MediaPayload.ArrGrab
    ): Either<DomainError, Unit>
}
