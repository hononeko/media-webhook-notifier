package app.hononeko.notifier.domain.port.inbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import arrow.core.Either

fun interface TrackDownloadUseCase {
    suspend fun track(
        hash: String,
        initialPayload: MediaPayload.ArrGrab
    ): Either<DomainError, Unit>

    suspend fun trackExisting(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        isPhoto: Boolean
    ): Either<DomainError, Unit> = Either.Right(Unit)
}
