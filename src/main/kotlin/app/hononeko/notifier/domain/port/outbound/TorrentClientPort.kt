package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.TorrentProgress
import arrow.core.Either

fun interface TorrentClientPort {
    suspend fun getTorrentProgress(hash: String): Either<DomainError.TorrentClientError, TorrentProgress?>

    suspend fun getActiveTorrents(
        filter: String = "downloading"
    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> = Either.Right(emptyList())

    suspend fun addTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> = Either.Right(Unit)

    suspend fun removeTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> = Either.Right(Unit)
}
