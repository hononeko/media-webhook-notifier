package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.TorrentProgress
import arrow.core.Either

interface TorrentClientPort {
    suspend fun getTorrentProgress(hash: String): Either<DomainError.TorrentClientError, TorrentProgress?>
}
