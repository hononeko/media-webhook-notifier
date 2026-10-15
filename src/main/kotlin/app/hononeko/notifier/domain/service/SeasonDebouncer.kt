package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Buffers rapid grabs, imports and library additions of the same title and season (see [debounceKey]) for
 * [debounceMillis], merging them into one payload so a multi-episode batch becomes one notification.
 */
class SeasonDebouncer(
    debounceMillis: Long = 5000L,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val deduplicator: MediaAvailableDeduplicator? = null,
    onDebouncedGrab: (suspend (MediaPayload.ArrGrab) -> Unit)? = null,
    onDebouncedDownload: (suspend (MediaPayload.ArrDownload) -> Unit)? = null,
    private val onDebouncedAvailable: (suspend (MediaPayload) -> Unit)? = null
) {
    private val grabs =
        DebounceBuffers(debounceMillis, scope, onDebouncedGrab) { grab, id ->
            grab.downloadId.equals(id, ignoreCase = true) || grab.downloadIds.any { it.equals(id, ignoreCase = true) }
        }
    private val downloads =
        DebounceBuffers(debounceMillis, scope, onDebouncedDownload) { download, id ->
            download.downloadId?.equals(id, ignoreCase = true) == true
        }
    private val available = DebounceBuffers(debounceMillis, scope, onDebouncedAvailable)

    val supportsAvailable: Boolean
        get() = onDebouncedAvailable != null

    val activeGrabBufferCount: Int get() = grabs.size
    val activeDownloadBufferCount: Int get() = downloads.size
    val activeAvailableBufferCount: Int get() = available.size

    suspend fun submit(grab: MediaPayload.ArrGrab) {
        val initialDownloadIds = (grab.downloadIds + grab.downloadId.split("|").filter { it.isNotBlank() }).distinct()
        val initial = grab.copy(downloadId = initialDownloadIds.joinToString("|"), downloadIds = initialDownloadIds)
        grabs.submit(debounceKey(grab), initial) { mergeGrab(it, grab) }
    }

    suspend fun submit(download: MediaPayload.ArrDownload) {
        downloads.submit(debounceKey(download), download) { mergeDownload(it, download) }
    }

    suspend fun submit(plex: MediaPayload.PlexLibraryNew) {
        if (onDebouncedAvailable == null) return
        if (deduplicator?.isStale(plex) == true) return
        available.submit(debounceKey(plex), plex) { prev ->
            (prev as? MediaPayload.PlexLibraryNew)?.let { mergePlexPayload(it, plex) } ?: plex
        }
    }

    suspend fun submit(jellyfin: MediaPayload.JellyfinItemAdded) {
        if (onDebouncedAvailable == null) return
        if (deduplicator?.isStale(jellyfin) == true) return
        available.submit(debounceKey(jellyfin), jellyfin) { prev ->
            (prev as? MediaPayload.JellyfinItemAdded)?.let { mergeJellyfin(it, jellyfin) } ?: jellyfin
        }
    }

    suspend fun submit(payload: MediaPayload) {
        when (payload) {
            is MediaPayload.ArrGrab -> submit(payload)
            is MediaPayload.ArrDownload -> submit(payload)
            is MediaPayload.PlexLibraryNew -> submit(payload)
            is MediaPayload.JellyfinItemAdded -> submit(payload)
            else -> Unit
        }
    }

    /** Sends the grab buffered under [key], or whose download id is [key], now. */
    suspend fun flushGrab(key: String) = grabs.flush(key)

    /** Sends the import buffered under [key], or whose download id is [key], now. */
    suspend fun flushDownload(key: String) = downloads.flush(key)

    suspend fun flush(key: String) {
        val normalized = key.trim().lowercase()
        grabs.flush(normalized)
        downloads.flush(normalized)
        available.flush(normalized)
    }

    suspend fun flushAll() {
        val allGrabs = grabs.drain()
        val allDownloads = downloads.drain()
        val allAvailable = available.drain()
        allGrabs.forEach { grabs.release(it) }
        allDownloads.forEach { downloads.release(it) }
        allAvailable.forEach { available.release(it) }
    }

    fun activeBufferCount(): Int = grabs.size + downloads.size + available.size
}

/** Merges a grab into the buffered one: episodes and download ids combine, the buffered metadata wins. */
private fun mergeGrab(
    prev: MediaPayload.ArrGrab,
    incoming: MediaPayload.ArrGrab
): MediaPayload.ArrGrab {
    val combinedEpisodes = (prev.episodeNumbers + incoming.episodeNumbers).distinct().sorted()
    val combinedDownloadIds =
        (
            prev.downloadIds + incoming.downloadIds +
                prev.downloadId.split("|").filter { it.isNotBlank() } +
                incoming.downloadId.split("|").filter { it.isNotBlank() }
        ).distinct()
    return prev.copy(
        downloadId = combinedDownloadIds.joinToString("|"),
        downloadIds = combinedDownloadIds,
        episodeNumbers = combinedEpisodes,
        sizeBytes = maxOf(prev.sizeBytes ?: 0L, incoming.sizeBytes ?: 0L).takeIf { it > 0 },
        releaseGroup = prev.releaseGroup ?: incoming.releaseGroup,
        releaseTitle = prev.releaseTitle ?: incoming.releaseTitle,
        quality = prev.quality ?: incoming.quality,
        indexer = prev.indexer ?: incoming.indexer,
        posterUrl = prev.posterUrl ?: incoming.posterUrl,
        instanceName = prev.instanceName ?: incoming.instanceName
    )
}

private fun mergeDownload(
    prev: MediaPayload.ArrDownload,
    incoming: MediaPayload.ArrDownload
): MediaPayload.ArrDownload =
    prev.copy(
        episodeNumbers = (prev.episodeNumbers + incoming.episodeNumbers).distinct().sorted(),
        sizeBytes = maxOf(prev.sizeBytes ?: 0L, incoming.sizeBytes ?: 0L).takeIf { it > 0 },
        videoCodec = prev.videoCodec ?: incoming.videoCodec,
        audioCodec = prev.audioCodec ?: incoming.audioCodec,
        resolution = prev.resolution ?: incoming.resolution,
        quality = prev.quality ?: incoming.quality,
        isUpgrade = prev.isUpgrade || incoming.isUpgrade,
        posterUrl = prev.posterUrl ?: incoming.posterUrl,
        overview = prev.overview ?: incoming.overview,
        year = prev.year ?: incoming.year,
        instanceName = prev.instanceName ?: incoming.instanceName,
        webUrl = prev.webUrl ?: incoming.webUrl
    )

private fun mergeJellyfin(
    prev: MediaPayload.JellyfinItemAdded,
    incoming: MediaPayload.JellyfinItemAdded
): MediaPayload.JellyfinItemAdded =
    prev.copy(
        episodeNumbers = (prev.episodeNumbers + incoming.episodeNumbers).distinct().sorted(),
        posterUrl = prev.posterUrl ?: incoming.posterUrl,
        overview = prev.overview ?: incoming.overview,
        videoCodec = prev.videoCodec ?: incoming.videoCodec,
        audioCodec = prev.audioCodec ?: incoming.audioCodec,
        resolution = prev.resolution ?: incoming.resolution,
        instanceName = prev.instanceName ?: incoming.instanceName
    )

private fun mergePlexPayload(
    prev: MediaPayload.PlexLibraryNew,
    incoming: MediaPayload.PlexLibraryNew
): MediaPayload.PlexLibraryNew {
    val combinedEpisodes = (prev.episodeNumbers + incoming.episodeNumbers).distinct().sorted()
    val combinedRatingKeys = (prev.ratingKeys + incoming.ratingKeys).distinct()
    return prev.copy(
        episodeNumbers = combinedEpisodes,
        ratingKeys = combinedRatingKeys,
        artworkBytes = prev.artworkBytes ?: incoming.artworkBytes,
        posterUrl = prev.posterUrl ?: incoming.posterUrl,
        parentPosterUrl = prev.parentPosterUrl ?: incoming.parentPosterUrl,
        grandparentPosterUrl = prev.grandparentPosterUrl ?: incoming.grandparentPosterUrl,
        summary = prev.summary ?: incoming.summary,
        rating = prev.rating ?: incoming.rating,
        videoCodec = prev.videoCodec ?: incoming.videoCodec,
        audioCodec = prev.audioCodec ?: incoming.audioCodec,
        resolution = prev.resolution ?: incoming.resolution,
        instanceName = prev.instanceName ?: incoming.instanceName
    )
}
