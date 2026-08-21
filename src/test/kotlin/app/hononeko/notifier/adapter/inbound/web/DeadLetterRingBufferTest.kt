package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
