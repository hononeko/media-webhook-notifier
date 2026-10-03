package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.DownloadItemProgress
import arrow.core.Either

/**
 * Driven port for download clients (qBittorrent, Transmission, SABnzbd, ...).
 *
 * A `downloadId` is the client's item identifier as reported by the *arr apps (torrent info hash or NZO id).
 * Several ids may be joined with `|` to address a multi-item grab as one aggregated download.
 * Tag operations default to no-ops for clients without tag support.
 */
fun interface DownloadClientPort {
    suspend fun getProgress(downloadId: String): Either<DomainError.DownloadClientError, DownloadItemProgress?>

    suspend fun getActiveDownloads(
        filter: String = "downloading"
    ): Either<DomainError.DownloadClientError, List<DownloadItemProgress>> = Either.Right(emptyList())

    suspend fun addTags(
        downloadId: String,
        tags: List<String>
    ): Either<DomainError.DownloadClientError, Unit> = Either.Right(Unit)

    suspend fun removeTags(
        downloadId: String,
        tags: List<String>
    ): Either<DomainError.DownloadClientError, Unit> = Either.Right(Unit)

    suspend fun deleteTags(tags: List<String>): Either<DomainError.DownloadClientError, Unit> = Either.Right(Unit)
}
