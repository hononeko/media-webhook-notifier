package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either

/** Whether the media server answers: Plex's `/identity`, Jellyfin's `/System/Info/Public`. */
fun interface MediaServerHealthPort {
    suspend fun checkHealth(): Either<DomainError.MediaServerError, Unit>
}
