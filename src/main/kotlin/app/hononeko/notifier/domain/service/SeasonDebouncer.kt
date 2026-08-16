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
    private val onDebouncedGrab: (suspend (MediaPayload.ArrGrab) -> Unit)? = null,
    private val onDebouncedDownload: (suspend (MediaPayload.ArrDownload) -> Unit)? = null
) {
    private val grabBuffers = ConcurrentHashMap<String, GrabDebounceBuffer>()
    private val downloadBuffers = ConcurrentHashMap<String, DownloadDebounceBuffer>()
    private val mutex = Mutex()

    private class GrabDebounceBuffer(
        var payload: MediaPayload.ArrGrab,
        var timerJob: Job
    )

    private class DownloadDebounceBuffer(
        var payload: MediaPayload.ArrDownload,
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
                val mergedPayload =
                    existing.payload.copy(
                        episodeNumbers = combinedEpisodes,
                        sizeBytes = maxOf(existing.payload.sizeBytes ?: 0L, grab.sizeBytes ?: 0L).takeIf { it > 0 },
                        releaseGroup = existing.payload.releaseGroup ?: grab.releaseGroup,
                        quality = existing.payload.quality ?: grab.quality,
                        indexer = existing.payload.indexer ?: grab.indexer,
                        posterUrl = existing.payload.posterUrl ?: grab.posterUrl,
                        instanceName = existing.payload.instanceName ?: grab.instanceName
                    )
                existing.payload = mergedPayload

                // Restart timer
                existing.timerJob = launchGrabTimer(key)
            } else {
                val newBuffer =
                    GrabDebounceBuffer(
                        payload = grab,
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

    suspend fun submit(payload: MediaPayload) {
        when (payload) {
            is MediaPayload.ArrGrab -> submit(payload)
            is MediaPayload.ArrDownload -> submit(payload)
            else -> Unit
        }
    }

    private fun computeGrabKey(grab: MediaPayload.ArrGrab): String {
        val downloadId = grab.downloadId.trim().lowercase()
        return if (downloadId.isNotBlank()) {
            downloadId
        } else {
            val title = grab.seriesOrMovieTitle.trim().lowercase()
            val source = grab.source.name.lowercase()
            val instance = (grab.instanceName ?: "").trim().lowercase()
            "$source:$instance:$title:s${grab.seasonNumber ?: 0}"
        }
    }

    private fun computeDownloadKey(download: MediaPayload.ArrDownload): String {
        val downloadId = download.downloadId?.trim()?.lowercase()
        return if (!downloadId.isNullOrBlank()) {
            "dl:$downloadId:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
        } else {
            val title = download.seriesOrMovieTitle.trim().lowercase()
            val source = download.source.name.lowercase()
            val instance = (download.instanceName ?: "").trim().lowercase()
            "title:$source:$instance:$title:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
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

    suspend fun flushGrab(key: String) {
        val normalized = key.trim().lowercase()
        val buffer =
            mutex.withLock {
                grabBuffers.remove(normalized)
                    ?: grabBuffers.entries.firstOrNull { it.key.contains(normalized) }?.let {
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
                    ?: downloadBuffers.entries.firstOrNull { it.key.contains(normalized) }?.let {
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

    suspend fun flush(key: String) {
        val normalized = key.trim().lowercase()
        flushGrab(normalized)
        flushDownload(normalized)
    }

    suspend fun flushAll() {
        val (allGrabs, allDownloads) =
            mutex.withLock {
                val grabs = ArrayList(grabBuffers.values)
                val downloads = ArrayList(downloadBuffers.values)
                grabBuffers.clear()
                downloadBuffers.clear()
                grabs to downloads
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
    }

    fun activeBufferCount(): Int = grabBuffers.size + downloadBuffers.size

    fun activeGrabBufferCount(): Int = grabBuffers.size

    fun activeDownloadBufferCount(): Int = downloadBuffers.size
}
