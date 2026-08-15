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
    private val onDebouncedGrab: suspend (MediaPayload.ArrGrab) -> Unit
) {
    private val buffers = ConcurrentHashMap<String, DebounceBuffer>()
    private val mutex = Mutex()

    private class DebounceBuffer(
        var payload: MediaPayload.ArrGrab,
        var timerJob: Job
    )

    suspend fun submit(grab: MediaPayload.ArrGrab) {
        val hash = grab.downloadId.trim().lowercase()
        mutex.withLock {
            val existing = buffers[hash]
            if (existing != null) {
                // Cancel existing timer
                existing.timerJob.cancel()

                // Merge episode numbers and retain most complete metadata
                val combinedEpisodes = (existing.payload.episodeNumbers + grab.episodeNumbers).distinct().sorted()
                val mergedPayload =
                    existing.payload.copy(
                        episodeNumbers = combinedEpisodes,
                        sizeBytes = maxOf(existing.payload.sizeBytes ?: 0L, grab.sizeBytes ?: 0L).takeIf { it > 0 }
                    )
                existing.payload = mergedPayload

                // Restart timer
                existing.timerJob = launchTimer(hash)
            } else {
                val newBuffer =
                    DebounceBuffer(
                        payload = grab,
                        timerJob = launchTimer(hash)
                    )
                buffers[hash] = newBuffer
            }
        }
    }

    private fun launchTimer(hash: String): Job =
        scope.launch {
            delay(debounceMillis)
            flush(hash)
        }

    suspend fun flush(hash: String) {
        val normalizedHash = hash.trim().lowercase()
        val buffer =
            mutex.withLock {
                buffers.remove(normalizedHash)
            }
        buffer?.let {
            val currentJob = coroutineContext[Job]
            if (it.timerJob != currentJob) {
                it.timerJob.cancel()
            }
            onDebouncedGrab(it.payload)
        }
    }

    suspend fun flushAll() {
        val allBuffers =
            mutex.withLock {
                val copy = ArrayList(buffers.values)
                buffers.clear()
                copy
            }
        val currentJob = coroutineContext[Job]
        for (buffer in allBuffers) {
            if (buffer.timerJob != currentJob) {
                buffer.timerJob.cancel()
            }
            onDebouncedGrab(buffer.payload)
        }
    }

    fun activeBufferCount(): Int = buffers.size
}
