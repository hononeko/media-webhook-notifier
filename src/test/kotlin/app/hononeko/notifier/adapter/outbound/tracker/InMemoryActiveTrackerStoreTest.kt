package app.hononeko.notifier.adapter.outbound.tracker

import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.model.TrackerStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryActiveTrackerStoreTest {
    private fun createSession(
        hash: String,
        title: String = "Test Show"
    ): ActiveTrackerSession {
        val payload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = hash,
                title = title,
                seriesOrMovieTitle = title
            )
        val handle = NotificationHandle("telegram", "chat123", "msg123")
        return ActiveTrackerSession(
            hash = hash,
            payload = payload,
            handle = handle,
            isPhoto = true,
            job = Job()
        )
    }

    @Test
    fun `should register and retrieve sessions atomically`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            assertEquals(0, store.activeCount())
            assertEquals(emptyList(), store.getAllSnapshots())
            assertFalse(store.isTracking(""))
            assertFalse(store.isTracking("   "))

            val session1 = createSession("hash1", "Show 1")
            val session1Duplicate = createSession("hash1", "Show 1 Duplicate")

            assertTrue(store.register(session1))
            assertFalse(store.register(session1Duplicate))
            assertEquals(1, store.activeCount())
            assertTrue(store.isTracking("hash1"))
            assertTrue(store.isTracking("HASH1 ")) // Case and whitespace normalization

            val retrieved = store.get("HASH1")
            assertNotNull(retrieved)
            assertEquals("hash1", retrieved.hash)
            assertEquals("Show 1", (retrieved.payload as MediaPayload.ArrGrab).title)
            assertEquals(true, retrieved.isPhoto)
        }

    @Test
    fun `should update progress and reflect in snapshots`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val session = createSession("hash_prog", "Prog Show")
            store.register(session)

            val progress =
                TorrentProgress(
                    hash = "hash_prog",
                    name = "Prog Show",
                    progressPercent = 45.0,
                    progressRatio = 0.45,
                    downloadSpeedBytesPerSec = 1000000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 120L,
                    totalSizeBytes = 10000000L,
                    downloadedBytes = 4500000L,
                    state = TorrentState.DOWNLOADING
                )

            store.updateProgress("hash_prog", progress, stalledSeconds = 0L)

            val snapshots = store.getAllSnapshots()
            assertEquals(1, snapshots.size)
            val snapshot = snapshots.first()
            assertEquals(45.0, snapshot.lastProgressPercent)
            assertEquals(1000000L, snapshot.downloadSpeedBytesPerSec)
            assertEquals(120L, snapshot.etaSeconds)
            assertEquals(TrackerStatus.TRACKING, snapshot.status)
            assertTrue(snapshot.startedAtMillis > 0)

            // Test stalled update
            store.updateProgress("hash_prog", progress, stalledSeconds = 60L)
            assertEquals(TrackerStatus.STALLED, store.get("hash_prog")?.status)
            assertEquals(TrackerStatus.STALLED, store.getAllSnapshots().first().status)

            // Updating non-existent hash should be a no-op
            store.updateProgress("non_existent", progress, stalledSeconds = 0L)
            assertEquals(1, store.activeCount())

            // Test snapshotsFlow emission
            assertEquals(1, store.snapshotsFlow.value.size)
        }

    @Test
    fun `should complete and cancel sessions cleanly`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val session1 = createSession("hash_comp", "Complete Show")
            val session2 = createSession("hash_canc", "Cancel Show")

            store.register(session1)
            store.register(session2)
            assertEquals(2, store.activeCount())

            val completed = store.complete("hash_comp")
            assertNotNull(completed)
            assertEquals(TrackerStatus.COMPLETING, completed.status)
            assertFalse(store.isTracking("hash_comp"))
            assertEquals(1, store.activeCount())

            val cancelled = store.cancel("hash_canc")
            assertNotNull(cancelled)
            assertEquals(TrackerStatus.CANCELLED, cancelled.status)
            assertTrue(cancelled.job.isCancelled)
            assertEquals(0, store.activeCount())

            assertNull(store.complete("nonexistent"))
            assertNull(store.cancel("nonexistent"))
        }

    @Test
    fun `should stopAll active sessions and cancel coroutine jobs`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val session1 = createSession("hash1")
            val session2 = createSession("hash2")

            store.register(session1)
            store.register(session2)
            assertEquals(2, store.activeCount())

            store.stopAll()
            assertEquals(0, store.activeCount())
            assertTrue(session1.job.isCancelled)
            assertTrue(session2.job.isCancelled)
        }
}
