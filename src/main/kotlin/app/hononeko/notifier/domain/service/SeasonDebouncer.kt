package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class SeasonDebouncer(
    private val debounceMillis: Long = 5000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val deduplicator: MediaAvailableDeduplicator? = null,
    private val onDebouncedGrab: (suspend (MediaPayload.ArrGrab) -> Unit)? = null,
    private val onDebouncedDownload: (suspend (MediaPayload.ArrDownload) -> Unit)? = null,
    private val onDebouncedAvailable: (suspend (MediaPayload) -> Unit)? = null
) {
    private val grabBuffers = ConcurrentHashMap<String, GrabDebounceBuffer>()
    private val downloadBuffers = ConcurrentHashMap<String, DownloadDebounceBuffer>()
    private val availableBuffers = ConcurrentHashMap<String, AvailableDebounceBuffer>()
    private val mutex = Mutex()

    val supportsAvailable: Boolean
        get() = onDebouncedAvailable != null

    private class GrabDebounceBuffer(
        var payload: MediaPayload.ArrGrab,
        var timerJob: Job
    )

    private class DownloadDebounceBuffer(
        var payload: MediaPayload.ArrDownload,
        var timerJob: Job
    )

    private class AvailableDebounceBuffer(
        var payload: MediaPayload,
        var timerJob: Job
    )

    suspend fun submit(grab: MediaPayload.ArrGrab) {
        val key = computeGrabKey(grab)
        mutex.withLock {
            val existing = grabBuffers[key]
            if (existing != null) {
                // Cancel existing timer
                existing.timerJob.cancel()

                // Merge episode numbers and retain most complete metadata
                val combinedEpisodes = (existing.payload.episodeNumbers + grab.episodeNumbers).distinct().sorted()
                val combinedDownloadIds =
                    (
                        existing.payload.downloadIds + grab.downloadIds +
                            existing.payload.downloadId
                                .split("|")
                                .filter { it.isNotBlank() } +
                            grab.downloadId.split("|").filter { it.isNotBlank() }
                    ).distinct()
                val combinedDownloadId = combinedDownloadIds.joinToString("|")

                val mergedPayload =
                    existing.payload.copy(
                        downloadId = combinedDownloadId,
                        downloadIds = combinedDownloadIds,
                        episodeNumbers = combinedEpisodes,
                        sizeBytes = maxOf(existing.payload.sizeBytes ?: 0L, grab.sizeBytes ?: 0L).takeIf { it > 0 },
                        releaseGroup = existing.payload.releaseGroup ?: grab.releaseGroup,
                        releaseTitle = existing.payload.releaseTitle ?: grab.releaseTitle,
                        quality = existing.payload.quality ?: grab.quality,
                        indexer = existing.payload.indexer ?: grab.indexer,
                        posterUrl = existing.payload.posterUrl ?: grab.posterUrl,
                        instanceName = existing.payload.instanceName ?: grab.instanceName
                    )
                existing.payload = mergedPayload

                // Restart timer
                existing.timerJob = launchGrabTimer(key)
            } else {
                val initialDownloadIds =
                    (
                        grab.downloadIds +
                            grab.downloadId.split("|").filter { it.isNotBlank() }
                    ).distinct()
                val initialDownloadId = initialDownloadIds.joinToString("|")
                val newBuffer =
                    GrabDebounceBuffer(
                        payload = grab.copy(downloadId = initialDownloadId, downloadIds = initialDownloadIds),
                        timerJob = launchGrabTimer(key)
                    )
                grabBuffers[key] = newBuffer
            }
        }
    }

    suspend fun submit(download: MediaPayload.ArrDownload) {
        val key = computeDownloadKey(download)
        mutex.withLock {
            val existing = downloadBuffers[key]
            if (existing != null) {
                // Cancel existing timer
                existing.timerJob.cancel()

                // Merge episode numbers and sum individual episode file sizes
                val combinedEpisodes = (existing.payload.episodeNumbers + download.episodeNumbers).distinct().sorted()
                val totalSizeBytes =
                    maxOf(existing.payload.sizeBytes ?: 0L, download.sizeBytes ?: 0L).takeIf { it > 0 }

                val mergedPayload =
                    existing.payload.copy(
                        episodeNumbers = combinedEpisodes,
                        sizeBytes = totalSizeBytes,
                        videoCodec = existing.payload.videoCodec ?: download.videoCodec,
                        audioCodec = existing.payload.audioCodec ?: download.audioCodec,
                        resolution = existing.payload.resolution ?: download.resolution,
                        quality = existing.payload.quality ?: download.quality,
                        isUpgrade = existing.payload.isUpgrade || download.isUpgrade,
                        posterUrl = existing.payload.posterUrl ?: download.posterUrl,
                        overview = existing.payload.overview ?: download.overview,
                        year = existing.payload.year ?: download.year,
                        instanceName = existing.payload.instanceName ?: download.instanceName,
                        webUrl = existing.payload.webUrl ?: download.webUrl
                    )
                existing.payload = mergedPayload

                // Restart timer
                existing.timerJob = launchDownloadTimer(key)
            } else {
                val newBuffer =
                    DownloadDebounceBuffer(
                        payload = download,
                        timerJob = launchDownloadTimer(key)
                    )
                downloadBuffers[key] = newBuffer
            }
        }
    }

    suspend fun submit(plex: MediaPayload.PlexLibraryNew) {
        if (onDebouncedAvailable == null) return
        if (deduplicator?.isStale(plex) == true) return
        val key = computePlexKey(plex)
        mutex.withLock {
            val existing = availableBuffers[key]
            if (existing != null && existing.payload is MediaPayload.PlexLibraryNew) {
                val prev = existing.payload as MediaPayload.PlexLibraryNew
                existing.timerJob.cancel()

                val combinedEpisodes = (prev.episodeNumbers + plex.episodeNumbers).distinct().sorted()
                val combinedRatingKeys = (prev.ratingKeys + plex.ratingKeys).distinct()
                val merged =
                    prev.copy(
                        episodeNumbers = combinedEpisodes,
                        ratingKeys = combinedRatingKeys,
                        artworkBytes = prev.artworkBytes ?: plex.artworkBytes,
                        posterUrl = prev.posterUrl ?: plex.posterUrl,
                        parentPosterUrl = prev.parentPosterUrl ?: plex.parentPosterUrl,
                        grandparentPosterUrl = prev.grandparentPosterUrl ?: plex.grandparentPosterUrl,
                        summary = prev.summary ?: plex.summary,
                        rating = prev.rating ?: plex.rating,
                        videoCodec = prev.videoCodec ?: plex.videoCodec,
                        audioCodec = prev.audioCodec ?: plex.audioCodec,
                        resolution = prev.resolution ?: plex.resolution,
                        instanceName = prev.instanceName ?: plex.instanceName
                    )
                existing.payload = merged
                existing.timerJob = launchAvailableTimer(key)
            } else {
                val newBuffer =
                    AvailableDebounceBuffer(
                        payload = plex,
                        timerJob = launchAvailableTimer(key)
                    )
                availableBuffers[key] = newBuffer
            }
        }
    }

    suspend fun submit(jellyfin: MediaPayload.JellyfinItemAdded) {
        if (onDebouncedAvailable == null) return
        if (deduplicator?.isStale(jellyfin) == true) return
        val key = computeJellyfinKey(jellyfin)
        mutex.withLock {
            val existing = availableBuffers[key]
            if (existing != null && existing.payload is MediaPayload.JellyfinItemAdded) {
                val prev = existing.payload as MediaPayload.JellyfinItemAdded
                existing.timerJob.cancel()

                val combinedEpisodes = (prev.episodeNumbers + jellyfin.episodeNumbers).distinct().sorted()
                val merged =
                    prev.copy(
                        episodeNumbers = combinedEpisodes,
                        posterUrl = prev.posterUrl ?: jellyfin.posterUrl,
                        overview = prev.overview ?: jellyfin.overview,
                        videoCodec = prev.videoCodec ?: jellyfin.videoCodec,
                        audioCodec = prev.audioCodec ?: jellyfin.audioCodec,
                        resolution = prev.resolution ?: jellyfin.resolution,
                        instanceName = prev.instanceName ?: jellyfin.instanceName
                    )
                existing.payload = merged
                existing.timerJob = launchAvailableTimer(key)
            } else {
                val newBuffer =
                    AvailableDebounceBuffer(
                        payload = jellyfin,
                        timerJob = launchAvailableTimer(key)
                    )
                availableBuffers[key] = newBuffer
            }
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

    private fun computeGrabKey(grab: MediaPayload.ArrGrab): String {
        val title = grab.seriesOrMovieTitle.trim().lowercase()
        val source = grab.source.name.lowercase()
        val instance = (grab.instanceName ?: "").trim().lowercase()
        return if (title.isNotBlank()) {
            "$source:$instance:$title:s${grab.seasonNumber ?: 0}"
        } else {
            val downloadId = grab.downloadId.trim().lowercase()
            if (downloadId.isNotBlank()) downloadId else "$source:$instance:unknown:s${grab.seasonNumber ?: 0}"
        }
    }

    private fun computeDownloadKey(download: MediaPayload.ArrDownload): String {
        val title = download.seriesOrMovieTitle.trim().lowercase()
        val source = download.source.name.lowercase()
        val instance = (download.instanceName ?: "").trim().lowercase()
        return if (title.isNotBlank()) {
            "title:$source:$instance:$title:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
        } else {
            val downloadId = download.downloadId?.trim()?.lowercase()
            if (!downloadId.isNullOrBlank()) {
                "dl:$downloadId:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
            } else {
                "title:$source:$instance:unknown:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
            }
        }
    }

    private fun computePlexKey(plex: MediaPayload.PlexLibraryNew): String {
        val instance = (plex.instanceName ?: "").trim().lowercase()
        val isSeries =
            plex.seasonNumber != null ||
                !plex.parentTitle.isNullOrBlank() ||
                !plex.grandParentTitle.isNullOrBlank() ||
                plex.mediaType?.lowercase() == "episode" ||
                plex.mediaType?.lowercase() == "season" ||
                plex.mediaType?.lowercase() == "show" ||
                plex.mediaType?.lowercase() == "series"
        return if (isSeries) {
            val series = (plex.grandParentTitle ?: plex.parentTitle ?: plex.title).trim().lowercase()
            "plex:$instance:show:$series:s${plex.seasonNumber ?: 0}"
        } else {
            val title = plex.title.trim().lowercase()
            val year = plex.year ?: 0
            "plex:$instance:movie:$title:$year"
        }
    }

    private fun computeJellyfinKey(jellyfin: MediaPayload.JellyfinItemAdded): String {
        val instance = (jellyfin.instanceName ?: "").trim().lowercase()
        val isSeries =
            jellyfin.seasonNumber != null ||
                !jellyfin.seriesName.isNullOrBlank() ||
                jellyfin.mediaType?.lowercase() == "episode" ||
                jellyfin.mediaType?.lowercase() == "season" ||
                jellyfin.mediaType?.lowercase() == "show" ||
                jellyfin.mediaType?.lowercase() == "series"
        return if (isSeries) {
            val series = (jellyfin.seriesName ?: jellyfin.title).trim().lowercase()
            "jellyfin:$instance:show:$series:s${jellyfin.seasonNumber ?: 0}"
        } else {
            val title = jellyfin.title.trim().lowercase()
            val year = jellyfin.year ?: 0
            "jellyfin:$instance:movie:$title:$year"
        }
    }

    private fun launchGrabTimer(key: String): Job =
        scope.launch {
            delay(debounceMillis)
            flushGrab(key)
        }

    private fun launchDownloadTimer(key: String): Job =
        scope.launch {
            delay(debounceMillis)
            flushDownload(key)
        }

    private fun launchAvailableTimer(key: String): Job =
        scope.launch {
            delay(debounceMillis)
            flushAvailable(key)
        }

    suspend fun flushGrab(key: String) {
        val normalized = key.trim().lowercase()
        val buffer =
            mutex.withLock {
                grabBuffers.remove(normalized)
                    ?: grabBuffers.entries
                        .firstOrNull {
                            it.value.payload.downloadId
                                .equals(normalized, ignoreCase = true) ||
                                it.value.payload.downloadIds
                                    .any { id -> id.equals(normalized, ignoreCase = true) }
                        }?.let {
                            grabBuffers.remove(it.key)
                        }
            }
        buffer?.let {
            val currentJob = coroutineContext[Job]
            if (it.timerJob != currentJob) {
                it.timerJob.cancel()
            }
            onDebouncedGrab?.invoke(it.payload)
        }
    }

    suspend fun flushDownload(key: String) {
        val normalized = key.trim().lowercase()
        val buffer =
            mutex.withLock {
                downloadBuffers.remove(normalized)
                    ?: downloadBuffers.entries
                        .firstOrNull {
                            it.value.payload.downloadId
                                ?.equals(normalized, ignoreCase = true) == true
                        }?.let {
                            downloadBuffers.remove(it.key)
                        }
            }
        buffer?.let {
            val currentJob = coroutineContext[Job]
            if (it.timerJob != currentJob) {
                it.timerJob.cancel()
            }
            onDebouncedDownload?.invoke(it.payload)
        }
    }

    suspend fun flushAvailable(key: String) {
        val normalized = key.trim().lowercase()
        val buffer =
            mutex.withLock {
                availableBuffers.remove(normalized)
            }
        buffer?.let {
            val currentJob = coroutineContext[Job]
            if (it.timerJob != currentJob) {
                it.timerJob.cancel()
            }
            onDebouncedAvailable?.invoke(it.payload)
        }
    }

    suspend fun flush(key: String) {
        val normalized = key.trim().lowercase()
        flushGrab(normalized)
        flushDownload(normalized)
        flushAvailable(normalized)
    }

    suspend fun flushAll() {
        val (allGrabs, allDownloads, allAvailable) =
            mutex.withLock {
                val grabs = ArrayList(grabBuffers.values)
                val downloads = ArrayList(downloadBuffers.values)
                val available = ArrayList(availableBuffers.values)
                grabBuffers.clear()
                downloadBuffers.clear()
                availableBuffers.clear()
                Triple(grabs, downloads, available)
            }
        val currentJob = coroutineContext[Job]
        for (buffer in allGrabs) {
            if (buffer.timerJob != currentJob) {
                buffer.timerJob.cancel()
            }
            onDebouncedGrab?.invoke(buffer.payload)
        }
        for (buffer in allDownloads) {
            if (buffer.timerJob != currentJob) {
                buffer.timerJob.cancel()
            }
            onDebouncedDownload?.invoke(buffer.payload)
        }
        for (buffer in allAvailable) {
            if (buffer.timerJob != currentJob) {
                buffer.timerJob.cancel()
            }
            onDebouncedAvailable?.invoke(buffer.payload)
        }
    }

    fun activeBufferCount(): Int = grabBuffers.size + downloadBuffers.size + availableBuffers.size

    fun activeGrabBufferCount(): Int = grabBuffers.size

    fun activeDownloadBufferCount(): Int = downloadBuffers.size

    fun activeAvailableBufferCount(): Int = availableBuffers.size
}
