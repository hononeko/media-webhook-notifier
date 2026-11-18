package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TrackerStatus
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import arrow.core.Either
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedTrackerRegistryTest {
    private val sampleGrab =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = "h1",
            title = "Severance - S02E01",
            seriesOrMovieTitle = "Severance",
            seasonNumber = 2,
            episodeNumbers = listOf(1)
        )
    private val sampleHandle = NotificationHandle("telegram", "-1001", "42", isPhoto = true, topicId = 99L)

    @Test
    fun `aggregates snapshots across replicas and fences stale owner writes`() =
        runTest {
            var now = 1_000_000L
            val sharedStore = InMemoryStateStore()
            val localA = InMemoryActiveTrackerStore()
            val localB = InMemoryActiveTrackerStore()
            val regA = SharedTrackerRegistry(sharedStore, localA, ownerId = "pod-a", clock = { now })
            val regB = SharedTrackerRegistry(sharedStore, localB, ownerId = "pod-b", clock = { now })

            val sessionA = ActiveTrackerSession("h1", sampleGrab, sampleHandle, isPhoto = true, job = Job())
            localA.register(sessionA)
            regA.recordStarted(sessionA)

            val progress =
                DownloadItemProgress(
                    id = "h1",
                    name = "Severance",
                    progressPercent = 45.0,
                    progressRatio = 0.45,
                    downloadSpeedBytesPerSec = 10_000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 30L,
                    totalSizeBytes = 1000L,
                    downloadedBytes = 450L,
                    state = DownloadState.DOWNLOADING
                )
            regA.recordProgress("h1", progress, stalledSeconds = 0L)

            // Pod B sees Pod A's tracker in allSnapshots() even though Pod B doesn't run it locally.
            val bView = regB.allSnapshots()
            assertEquals(1, bView.size)
            assertEquals(45.0, bView.single().lastProgressPercent)
            assertEquals(99L, bView.single().handle.topicId)

            // Pod B takes over; Pod A's stale recordFinished must NOT delete Pod B's record.
            val sessionB = ActiveTrackerSession("h1", sampleGrab, sampleHandle, isPhoto = true, job = Job())
            regB.recordStarted(sessionB)
            regA.recordFinished("h1")
            assertEquals(1, regB.allSnapshots().size)

            // Stale Pod A progress update must NOT overwrite Pod B's record.
            regA.recordProgress("h1", progress.copy(progressPercent = 10.0), stalledSeconds = 10L)
            assertEquals(TrackerStatus.TRACKING, regB.allSnapshots().single().status)

            regB.recordFinished("h1")
            assertNull(sharedStore.get(SharedTrackerRegistry.KEY_PREFIX + "h1", now))
        }

    @Test
    fun `sweepOrphanedTrackers resumes trackers when owner dies and ignores live peers`() =
        runTest {
            var now = 1_000_000L
            val sharedStore = InMemoryStateStore()
            val localA = InMemoryActiveTrackerStore()
            val localB = InMemoryActiveTrackerStore()
            val hbA = PodHeartbeat(sharedStore, "pod-a", ttlSeconds = 9L, clock = { now })
            val hbB = PodHeartbeat(sharedStore, "pod-b", ttlSeconds = 9L, clock = { now })
            hbA.beat()
            hbB.beat()

            val leasesA =
                TrackingLeases(sharedStore, "pod-a", ttlSeconds = 30L, clock = { now }, isOwnerAlive = hbA::isAlive)
            leasesA.acquire("h1")
            val regA = SharedTrackerRegistry(sharedStore, localA, "pod-a", hbA::isAlive, clock = { now })
            val sessionA = ActiveTrackerSession("h1", sampleGrab, sampleHandle, isPhoto = true, job = Job())
            localA.register(sessionA)
            regA.recordStarted(sessionA)

            val regB = SharedTrackerRegistry(sharedStore, localB, "pod-b", hbB::isAlive, clock = { now })
            val resumed = mutableListOf<Triple<String, MediaPayload.ArrGrab, NotificationHandle>>()
            val useCase =
                object : TrackDownloadUseCase {
                    override suspend fun track(
                        hash: String,
                        initialPayload: MediaPayload.ArrGrab
                    ): Either<DomainError, TrackingStart> = Either.Right(TrackingStart.STARTED)

                    override suspend fun trackExisting(
                        hash: String,
                        payload: MediaPayload.ArrGrab,
                        handle: NotificationHandle,
                        isPhoto: Boolean
                    ): Either<DomainError, TrackingStart> {
                        resumed += Triple(hash, payload, handle)
                        return Either.Right(TrackingStart.STARTED)
                    }
                }

            // While Pod A's heartbeat is fresh, Pod B leaves the tracker alone.
            assertEquals(0, regB.sweepOrphanedTrackers(useCase))
            assertEquals(0, resumed.size)

            // Pod A crashes: heartbeat expires (9s) even though lock TTL (30s) hasn't expired yet!
            now += 10_000L
            hbB.beat()
            assertEquals(1, regB.sweepOrphanedTrackers(useCase))
            assertEquals("h1", resumed.single().first)
            assertEquals(99L, resumed.single().third.topicId)
        }
}
