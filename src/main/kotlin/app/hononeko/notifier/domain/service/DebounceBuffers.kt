package app.hononeko.notifier.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Payloads buffered by key: each [submit] (re)starts the key's timer, and when it runs out after [debounceMillis] the
 * buffered payload goes to [onFlush]. [matchesId] lets [flush] find a buffer by an id in its payload as well as by key.
 */
internal class DebounceBuffers<P>(
    private val debounceMillis: Long,
    private val scope: CoroutineScope,
    private val onFlush: (suspend (P) -> Unit)?,
    private val matchesId: (P, String) -> Boolean = { _, _ -> false }
) {
    class Buffer<P>(
        var payload: P,
        var timerJob: Job
    )

    private val buffers = ConcurrentHashMap<String, Buffer<P>>()
    private val mutex = Mutex()

    val size: Int get() = buffers.size

    /** Buffers [initial] under [key], or replaces what's buffered there with [merge] of it; restarts the timer. */
    suspend fun submit(
        key: String,
        initial: P,
        merge: (P) -> P
    ) {
        mutex.withLock {
            val existing = buffers[key]
            if (existing != null) {
                existing.timerJob.cancel()
                existing.payload = merge(existing.payload)
                existing.timerJob = launchTimer(key)
            } else {
                buffers[key] = Buffer(initial, launchTimer(key))
            }
        }
    }

    suspend fun flush(key: String) {
        val normalized = key.trim().lowercase()
        val buffer =
            mutex.withLock {
                buffers.remove(normalized)
                    ?: buffers.entries
                        .firstOrNull { matchesId(it.value.payload, normalized) }
                        ?.let { buffers.remove(it.key) }
            }
        buffer?.let { release(it) }
    }

    /** Removes every buffer without sending it; [release] each to send it. */
    suspend fun drain(): List<Buffer<P>> =
        mutex.withLock {
            ArrayList(buffers.values).also { buffers.clear() }
        }

    /** Stops [buffer]'s timer, unless this is that timer, and sends its payload. */
    suspend fun release(buffer: Buffer<P>) {
        if (buffer.timerJob != currentCoroutineContext()[Job]) {
            buffer.timerJob.cancel()
        }
        onFlush?.invoke(buffer.payload)
    }

    private fun launchTimer(key: String): Job =
        scope.launch {
            delay(debounceMillis)
            flush(key)
        }
}
