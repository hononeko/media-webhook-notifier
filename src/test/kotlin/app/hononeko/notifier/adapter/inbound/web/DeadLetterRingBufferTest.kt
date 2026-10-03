package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterRingBufferTest {
    private fun createPayload(id: String): MediaPayload =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = id,
            title = "Title $id",
            seriesOrMovieTitle = "Series $id"
        )

    @Test
    fun `should record and maintain bounded circular FIFO capacity`() {
        val buffer = DeadLetterRingBuffer(capacity = 3)
        assertEquals(0, buffer.size())
        assertEquals(0L, buffer.totalRecordedCount())

        buffer.record(createPayload("1"), "Error 1")
        buffer.record(createPayload("2"), "Error 2")
        buffer.record(createPayload("3"), "Error 3")

        assertEquals(3, buffer.size())
        assertEquals(3L, buffer.totalRecordedCount())

        val entries = buffer.getEntries()
        assertEquals(3, entries.size)
        assertEquals("1", (entries[0].payload as MediaPayload.ArrGrab).downloadId)
        assertEquals("Error 1", entries[0].errorMessage)
        assertEquals(1, entries[0].attemptCount)
        assertTrue(entries[0].timestamp > 0)

        // Overflow ring buffer
        buffer.record(createPayload("4"), "Error 4")
        buffer.record(createPayload("5"), "Error 5")

        assertEquals(3, buffer.size())
        assertEquals(5L, buffer.totalRecordedCount())

        val updatedEntries = buffer.getEntries()
        assertEquals(3, updatedEntries.size)
        assertEquals("3", (updatedEntries[0].payload as MediaPayload.ArrGrab).downloadId)
        assertEquals("4", (updatedEntries[1].payload as MediaPayload.ArrGrab).downloadId)
        assertEquals("5", (updatedEntries[2].payload as MediaPayload.ArrGrab).downloadId)

        // Test clear
        buffer.clear()
        assertEquals(0, buffer.size())
        assertEquals(0, buffer.getEntries().size)
    }

    @Test
    fun `should assign unique ids and look entries up by id`() {
        val buffer = DeadLetterRingBuffer(capacity = 5)
        val first = buffer.record(createPayload("1"), "Error 1", stackTrace = "x".repeat(20_000))
        val second = buffer.record(createPayload("2"), "Error 2", attemptCount = 3)

        assertNotEquals(first.id, second.id)
        assertEquals(first, buffer.get(first.id))
        assertEquals(3, buffer.get(second.id)?.attemptCount)
        assertEquals(DeadLetterRingBuffer.MAX_STACK_TRACE_CHARS, first.stackTrace?.length)
        assertNull(buffer.get("missing"))
        assertEquals(DeadLetterStatus.PENDING, first.status)
    }

    @Test
    fun `should replay pending entries exactly once and keep rejected entries pending`() {
        val buffer = DeadLetterRingBuffer(capacity = 5)
        val entry = buffer.record(createPayload("1"), "Error 1")
        val dispatched = AtomicInteger(0)

        val rejected = buffer.replay(entry.id) { false }
        assertIs<DeadLetterReplayResult.Rejected>(rejected)
        assertEquals(DeadLetterStatus.PENDING, buffer.get(entry.id)?.status)

        val replayed = buffer.replay(entry.id, nowMillis = 1234L) { dispatched.incrementAndGet() > 0 }
        assertIs<DeadLetterReplayResult.Replayed>(replayed)
        assertEquals(DeadLetterStatus.RESOLVED, replayed.entry.status)
        assertEquals(1234L, buffer.get(entry.id)?.resolvedAt)

        val again = buffer.replay(entry.id) { dispatched.incrementAndGet() > 0 }
        assertIs<DeadLetterReplayResult.AlreadyResolved>(again)
        assertEquals(1, dispatched.get())

        assertEquals(DeadLetterReplayResult.NotFound, buffer.replay("missing") { true })
    }

    @Test
    fun `should merge restored entries by timestamp and enforce capacity`() {
        val buffer = DeadLetterRingBuffer(capacity = 3)
        val live = buffer.record(createPayload("live"), "Live error")
        val restored =
            listOf(
                DeadLetterEntry(id = "old-1", timestamp = 1L, payload = createPayload("o1"), errorMessage = "Old 1"),
                DeadLetterEntry(id = "old-2", timestamp = 2L, payload = createPayload("o2"), errorMessage = "Old 2"),
                DeadLetterEntry(id = "old-3", timestamp = 3L, payload = createPayload("o3"), errorMessage = "Old 3")
            )

        buffer.restore(restored)

        assertEquals(listOf("old-2", "old-3", live.id), buffer.getEntries().map { it.id })
    }

    @Test
    fun `should notify change listener on every mutation`() {
        val buffer = DeadLetterRingBuffer(capacity = 3)
        val changes = AtomicInteger(0)
        buffer.changeListener = { changes.incrementAndGet() }

        val entry = buffer.record(createPayload("1"), "Error 1")
        buffer.replay(entry.id) { false }
        buffer.replay(entry.id) { true }
        buffer.restore(listOf(DeadLetterEntry(payload = createPayload("2"), errorMessage = "Restored")))
        assertEquals(2, buffer.clear())

        // record, successful replay, restore, clear (a rejected replay changes nothing)
        assertEquals(4, changes.get())
    }
}
