package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class DeadLetterStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("resolved")
    RESOLVED
}

@Serializable
data class DeadLetterEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val payload: MediaPayload,
    val errorMessage: String,
    val stackTrace: String? = null,
    val attemptCount: Int = 1,
    val status: DeadLetterStatus = DeadLetterStatus.PENDING,
    val resolvedAt: Long? = null
)

sealed interface DeadLetterReplayResult {
    data class Replayed(
        val entry: DeadLetterEntry
    ) : DeadLetterReplayResult

    data class AlreadyResolved(
        val entry: DeadLetterEntry
    ) : DeadLetterReplayResult

    /** The event rail refused the payload (full or shutting down); the entry stays pending. */
    data class Rejected(
        val entry: DeadLetterEntry
    ) : DeadLetterReplayResult

    data object NotFound : DeadLetterReplayResult
}

/**
 * Bounded, insertion-ordered (oldest first) store of payloads that could not be processed.
 * [changeListener] is invoked after every mutation so the buffer can be mirrored into a durable store.
 */
class DeadLetterRingBuffer(
    private val capacity: Int = 100
) {
    companion object {
        const val MAX_STACK_TRACE_CHARS = 8_192
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, DeadLetterEntry>()
    private val totalRecorded = AtomicLong(0)

    @Volatile
    var changeListener: (() -> Unit)? = null

    fun record(
        payload: MediaPayload,
        errorMessage: String,
        attemptCount: Int = 1,
        stackTrace: String? = null
    ): DeadLetterEntry {
        val entry =
            DeadLetterEntry(
                payload = payload,
                errorMessage = errorMessage,
                stackTrace = stackTrace?.take(MAX_STACK_TRACE_CHARS),
                attemptCount = attemptCount
            )
        totalRecorded.incrementAndGet()
        synchronized(lock) {
            entries[entry.id] = entry
            evictOverflow()
        }
        changeListener?.invoke()
        return entry
    }

    fun getEntries(): List<DeadLetterEntry> = synchronized(lock) { entries.values.toList() }

    fun get(id: String): DeadLetterEntry? = synchronized(lock) { entries[id] }

    fun size(): Int = synchronized(lock) { entries.size }

    fun totalRecordedCount(): Long = totalRecorded.get()

    /**
     * Hands a pending entry to [dispatch] and marks it resolved when dispatch succeeds. Runs under the buffer
     * lock so concurrent replays of the same id dispatch at most once; [dispatch] must therefore not block.
     */
    fun replay(
        id: String,
        nowMillis: Long = System.currentTimeMillis(),
        dispatch: (DeadLetterEntry) -> Boolean
    ): DeadLetterReplayResult {
        val result =
            synchronized(lock) {
                val entry = entries[id]
                when {
                    entry == null -> DeadLetterReplayResult.NotFound
                    entry.status == DeadLetterStatus.RESOLVED -> DeadLetterReplayResult.AlreadyResolved(entry)
                    !dispatch(entry) -> DeadLetterReplayResult.Rejected(entry)
                    else -> {
                        val resolved = entry.copy(status = DeadLetterStatus.RESOLVED, resolvedAt = nowMillis)
                        entries[id] = resolved
                        DeadLetterReplayResult.Replayed(resolved)
                    }
                }
            }
        if (result is DeadLetterReplayResult.Replayed) {
            changeListener?.invoke()
        }
        return result
    }

    /** Merges previously persisted entries with the live ones, keeping the newest [capacity] by timestamp. */
    fun restore(restored: List<DeadLetterEntry>) {
        if (restored.isEmpty()) return
        synchronized(lock) {
            val merged = (restored + entries.values).sortedBy { it.timestamp }
            entries.clear()
            merged.forEach { entries[it.id] = it }
            evictOverflow()
        }
        changeListener?.invoke()
    }

    /** Removes every entry and returns how many were dropped. */
    fun clear(): Int {
        val cleared =
            synchronized(lock) {
                val count = entries.size
                entries.clear()
                count
            }
        changeListener?.invoke()
        return cleared
    }

    private fun evictOverflow() {
        val iterator = entries.keys.iterator()
        while (entries.size > capacity && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}
