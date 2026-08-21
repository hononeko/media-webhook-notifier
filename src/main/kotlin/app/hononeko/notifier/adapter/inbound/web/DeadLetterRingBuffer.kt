package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.model.MediaPayload
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

data class DeadLetterEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val payload: MediaPayload,
    val errorMessage: String,
    val attemptCount: Int = 1
)

class DeadLetterRingBuffer(
    private val capacity: Int = 100
) {
    private val buffer = ConcurrentLinkedDeque<DeadLetterEntry>()
    private val totalRecorded = AtomicLong(0)

    fun record(
        payload: MediaPayload,
        errorMessage: String,
        attemptCount: Int = 1
    ) {
        totalRecorded.incrementAndGet()
        buffer.addLast(
            DeadLetterEntry(
                payload = payload,
                errorMessage = errorMessage,
                attemptCount = attemptCount
            )
        )
        while (buffer.size > capacity) {
            buffer.pollFirst()
        }
    }

    fun getEntries(): List<DeadLetterEntry> = buffer.toList()

    fun size(): Int = buffer.size

    fun totalRecordedCount(): Long = totalRecorded.get()

    fun clear() {
        buffer.clear()
    }
}
