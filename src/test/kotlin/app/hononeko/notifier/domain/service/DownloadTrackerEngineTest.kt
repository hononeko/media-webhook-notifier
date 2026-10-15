package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.SwarmStats
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import app.hononeko.notifier.testing.RecordingPublisher
import arrow.core.Either
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
/** The publisher these tests expect: Telegram-like ids the resume tags are checked against. */
private fun newPublisher(shouldFailStart: Boolean = false) =
    RecordingPublisher(
        providerId = "fake",
        chatId = "chat123",
        sentMessageId = "msg_send",
        liveMessageId = "msg_live",
        shouldFailStart = shouldFailStart
    )

class DownloadTrackerEngineTest {
    private fun createEngine(
        downloadClient: DownloadClientPort,
        notificationPublisher: NotificationPublisherPort,
        activeTrackerStore: ActiveTrackerStore = InMemoryActiveTrackerStore(),
        config: DownloadTrackerConfig = DownloadTrackerConfig(),
        scope: CoroutineScope
    ) = DownloadTrackerEngine(
        downloadClient = downloadClient,
        notificationPublisher = notificationPublisher,
        activeTrackerStore = activeTrackerStore,
        config = config,
        scope = scope
    )

    private class FakeTorrentClient(
        private val progressProvider: (String) -> Either<DomainError.DownloadClientError, DownloadItemProgress?> = {
            Either.Right(null)
        }
    ) : DownloadClientPort {
        val addedTags = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val removedTags = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val deletedTags = Collections.synchronizedList(mutableListOf<List<String>>())

        override suspend fun getProgress(hash: String): Either<DomainError.DownloadClientError, DownloadItemProgress?> =
            progressProvider(hash)

        override suspend fun addTags(
            hash: String,
            tags: List<String>
        ): Either<DomainError.DownloadClientError, Unit> {
            addedTags.add(hash to tags)
            return Either.Right(Unit)
        }

        override suspend fun removeTags(
            hash: String,
            tags: List<String>
        ): Either<DomainError.DownloadClientError, Unit> {
            removedTags.add(hash to tags)
            return Either.Right(Unit)
        }

        override suspend fun deleteTags(tags: List<String>): Either<DomainError.DownloadClientError, Unit> {
            deletedTags.add(tags)
            return Either.Right(Unit)
        }
    }

    @Test
    fun `should reject blank torrent hash with Typed Error`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val downloadClient = DownloadClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "   ",
                    title = "Test",
                    seriesOrMovieTitle = "Test"
                )

            val result = engine.track("   ", grab)
            assertTrue(result.isLeft())
            assertEquals(DomainError.WebhookError.MissingTorrentHash, (result as Either.Left).value)
        }

    @Test
    fun `should track download progress until 100 percent completion`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val callCount = AtomicInteger(0)

            val downloadClient =
                DownloadClientPort { hash ->
                    val count = callCount.incrementAndGet()
                    when (count) {
                        1 ->
                            Either.Right(
                                DownloadItemProgress(
                                    id = hash,
                                    name = "Test",
                                    progressPercent = 50.0,
                                    progressRatio = 0.5,
                                    downloadSpeedBytesPerSec = 10485760,
                                    uploadSpeedBytesPerSec = 0,
                                    etaSeconds = 60,
                                    totalSizeBytes = 1000000000L,
                                    downloadedBytes = 500000000L,
                                    state = DownloadState.DOWNLOADING
                                )
                            )
                        else ->
                            Either.Right(
                                DownloadItemProgress(
                                    id = hash,
                                    name = "Test",
                                    progressPercent = 100.0,
                                    progressRatio = 1.0,
                                    downloadSpeedBytesPerSec = 0,
                                    uploadSpeedBytesPerSec = 0,
                                    etaSeconds = 0,
                                    totalSizeBytes = 1000000000L,
                                    downloadedBytes = 1000000000L,
                                    state = DownloadState.COMPLETED
                                )
                            )
                    }
                }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 2),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash123",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance"
                )

            val trackResult = engine.track("hash123", grab)
            assertTrue(trackResult.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertTrue(engine.isTracking("hash123"))

            // Duplicate tracking call should be no-op
            val dupResult = engine.track("hash123", grab)
            assertTrue(dupResult.isRight())

            // Tick 1 (50%)
            testScope.advanceTimeBy(2100L)
            assertEquals(1, publisher.progressUpdates.size)
            assertEquals(50.0, publisher.progressUpdates.first().percent)

            // Tick 2 (100% Complete)
            testScope.advanceTimeBy(2100L)
            assertEquals(1, publisher.completedCards.size)
            assertEquals(0, engine.activeTrackerCount())
        }

    @Test
    fun `should tolerate transient missing torrents up to grace limit`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val downloadClient =
                DownloadClientPort {
                    Either.Right(null)
                }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1, missingGraceAttempts = 3),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashMissing",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance"
                )

            engine.track("hashMissing", grab)
            assertEquals(1, engine.activeTrackerCount())

            // 3 grace attempts: 3 seconds
            testScope.advanceTimeBy(3500L)

            assertEquals(1, publisher.cancelledCards.size)
            assertEquals(0, engine.activeTrackerCount())
        }

    @Test
    fun `should fail track when publisher returns error`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val failingPublisher = newPublisher(shouldFailStart = true)
            val downloadClient = DownloadClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = failingPublisher,
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashFail",
                    title = "Test",
                    seriesOrMovieTitle = "Test"
                )

            val result = engine.track("hashFail", grab)
            assertTrue(result.isLeft())
            assertFalse(engine.isTracking("hashFail"))
        }

    @Test
    fun `should cancel trackers on stopAll`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val downloadClient = DownloadClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 10),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashStop",
                    title = "Test",
                    seriesOrMovieTitle = "Test"
                )

            engine.track("hashStop", grab)
            assertTrue(engine.isTracking("hashStop"))

            engine.stopAll()
            assertEquals(0, engine.activeTrackerCount())
            assertFalse(engine.isTracking("hashStop"))
        }

    @Test
    fun `should stop tracking when maxPollingMinutes is exceeded`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val progress =
                DownloadItemProgress(
                    id = "hashMaxPoll",
                    name = "Show",
                    progressPercent = 10.0,
                    progressRatio = 0.1,
                    downloadSpeedBytesPerSec = 1000,
                    uploadSpeedBytesPerSec = 0,
                    etaSeconds = 600,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 100000L,
                    swarm = SwarmStats(seedsCount = 1, seedsTotal = 2, peersCount = 1, peersTotal = 2),
                    state = DownloadState.DOWNLOADING
                )
            val downloadClient = DownloadClientPort { Either.Right(progress) }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config =
                        DownloadTrackerConfig(
                            pollIntervalSeconds = 1,
                            maxPollingMinutes = 1
                        ),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashMaxPoll",
                    title = "Show",
                    seriesOrMovieTitle = "Show"
                )

            engine.track("hashMaxPoll", grab)
            assertTrue(engine.isTracking("hashMaxPoll"))

            // Advance time past 1 minute (61 seconds)
            testScope.advanceTimeBy(65000L)

            assertEquals(0, engine.activeTrackerCount())
            assertFalse(engine.isTracking("hashMaxPoll"))
        }

    @Test
    fun `should send stalled notification when download is stalled beyond timeout`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val stalledProgress =
                DownloadItemProgress(
                    id = "hashStalled",
                    name = "Stalled Show",
                    progressPercent = 15.0,
                    progressRatio = 0.15,
                    downloadSpeedBytesPerSec = 0,
                    uploadSpeedBytesPerSec = 0,
                    etaSeconds = -1,
                    totalSizeBytes = 2000000L,
                    downloadedBytes = 300000L,
                    swarm = SwarmStats(seedsCount = 0, seedsTotal = 0, peersCount = 0, peersTotal = 0),
                    state = DownloadState.STALLED
                )
            val downloadClient = DownloadClientPort { Either.Right(stalledProgress) }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config =
                        DownloadTrackerConfig(
                            pollIntervalSeconds = 1,
                            stalledTimeoutMinutes = 1
                        ),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashStalled",
                    title = "Stalled Show",
                    seriesOrMovieTitle = "Stalled Show"
                )

            engine.track("hashStalled", grab)

            // Advance time past 1 minute stalled window
            testScope.advanceTimeBy(65000L)

            assertEquals(1, publisher.cancelledCards.size)
            assertEquals(0, engine.activeTrackerCount())
        }

    @Test
    fun `should handle torrent client errors during poll loop gracefully`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val downloadClient =
                DownloadClientPort {
                    Either.Left(DomainError.DownloadClientError.ConnectionFailed("http://localhost:8080"))
                }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashErr",
                    title = "Err Show",
                    seriesOrMovieTitle = "Err Show"
                )

            engine.track("hashErr", grab)
            assertTrue(engine.isTracking("hashErr"))

            testScope.advanceTimeBy(3000L)

            // Should remain tracking despite transient errors
            assertTrue(engine.isTracking("hashErr"))
            engine.stopAll()
        }

    @Test
    fun `should support trackExisting and track to completion without sending initial card`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            var callCount = 0
            val downloadClient =
                DownloadClientPort {
                    callCount++
                    val progressPercent = if (callCount == 1) 50.0 else 100.0
                    val state = if (callCount == 1) DownloadState.DOWNLOADING else DownloadState.COMPLETED
                    Either.Right(
                        DownloadItemProgress(
                            id = "hash_exist",
                            name = "Severance.S02E01",
                            progressPercent = progressPercent,
                            progressRatio = progressPercent / 100.0,
                            downloadSpeedBytesPerSec = 500000L,
                            uploadSpeedBytesPerSec = 0L,
                            etaSeconds = if (progressPercent == 100.0) 0L else 30L,
                            totalSizeBytes = 1000000L,
                            downloadedBytes = (1000000L * (progressPercent / 100.0)).toLong(),
                            state = state
                        )
                    )
                }

            val engine =
                createEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_exist",
                    title = "Severance.S02E01",
                    seriesOrMovieTitle = "Severance"
                )

            val handle = NotificationHandle("telegram", "chat123", "msg999")

            // Test blank hash
            val blankResult = engine.trackExisting("   ", grab, handle, isPhoto = true)
            assertTrue(blankResult.isLeft())

            // Test valid trackExisting
            val trackResult = engine.trackExisting("hash_exist", grab, handle, isPhoto = true)
            assertTrue(trackResult.isRight())
            assertTrue(engine.isTracking("hash_exist"))

            // Initial card should NOT be sent via publisher (because it was resumed)
            assertEquals(0, publisher.sentCards.size)

            // Test already tracking check
            val duplicateResult = engine.trackExisting("hash_exist", grab, handle, isPhoto = true)
            assertTrue(duplicateResult.isRight())

            // Advance time for progress poll
            testScope.advanceTimeBy(1100L)
            assertEquals(1, publisher.progressUpdates.size)

            // Advance time to completion
            testScope.advanceTimeBy(1100L)
            assertEquals(1, publisher.completedCards.size)
            assertEquals(0, engine.activeTrackerCount())
        }

    @Test
    fun `should prevent duplicate tracking sessions on concurrent track calls for same hash`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val client = FakeTorrentClient()

            val engine =
                createEngine(
                    downloadClient = client,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_concurrent",
                    title = "Concurrent Show",
                    seriesOrMovieTitle = "Concurrent Show"
                )

            // Launch 5 concurrent track calls for the same hash
            val jobs =
                (1..5).map {
                    testScope.async {
                        engine.track("hash_concurrent", grab)
                    }
                }

            val results = jobs.awaitAll()
            assertTrue(results.all { it.isRight() })

            // Exactly 1 card sent and 1 session registered
            assertEquals(1, publisher.sentCards.size)
            assertTrue(engine.isTracking("hash_concurrent"))
            assertEquals(1, engine.activeTrackerCount())
        }

    @Test
    fun `should strip all mwn tags from torrent and delete ephemeral mwn_msg tags on completion`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val client =
                FakeTorrentClient { hash ->
                    Either.Right(
                        DownloadItemProgress(
                            id = hash,
                            name = "Full Metal Jacket",
                            progressPercent = 100.0,
                            progressRatio = 1.0,
                            downloadSpeedBytesPerSec = 0,
                            uploadSpeedBytesPerSec = 0,
                            etaSeconds = 0,
                            totalSizeBytes = 1000000L,
                            downloadedBytes = 1000000L,
                            state = DownloadState.COMPLETED,
                            tags = listOf("mwn_msg:9065", "mwn_photo:0", "mwn_chat:chat123", "non_mwn_tag")
                        )
                    )
                }

            val engine =
                createEngine(
                    downloadClient = client,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.RADARR,
                    downloadId = "hash_tags_test",
                    title = "Full Metal Jacket",
                    seriesOrMovieTitle = "Full Metal Jacket"
                )

            engine.track("hash_tags_test", grab)

            // Trigger completion tick
            testScope.advanceTimeBy(1100L)

            // 1. removeTags should have been called with all mwn_* tags found on torrent + session tags
            val allRemoved = client.removedTags.flatMap { it.second }.toSet()
            assertTrue(allRemoved.contains("mwn_msg:msg_live"))
            assertTrue(allRemoved.contains("mwn_msg:9065"))
            assertTrue(allRemoved.contains("mwn_photo:0"))
            assertTrue(allRemoved.contains("mwn_photo:1"))
            assertTrue(allRemoved.contains("mwn_chat:chat123"))
            assertFalse(allRemoved.contains("non_mwn_tag"))

            // 2. deleteTags should have been called for single-use mwn_msg tags permanently
            val allDeleted = client.deletedTags.flatten().toSet()
            assertTrue(allDeleted.contains("mwn_msg:msg_live"))
            assertTrue(allDeleted.contains("mwn_msg:9065"))
            assertFalse(allDeleted.contains("mwn_photo:1"))
            assertFalse(allDeleted.contains("mwn_photo:0"))
            assertFalse(allDeleted.contains("mwn_chat:chat123"))
            assertFalse(allDeleted.contains("non_mwn_tag"))
        }

    @Test
    fun `should keep resume tags when tracking is stopped`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = newPublisher()
            val client =
                FakeTorrentClient {
                    Either.Right(
                        DownloadItemProgress(
                            id = "hash_stop",
                            name = "Stopping Show",
                            progressPercent = 10.0,
                            progressRatio = 0.1,
                            downloadSpeedBytesPerSec = 1000L,
                            uploadSpeedBytesPerSec = 0L,
                            etaSeconds = 500L,
                            totalSizeBytes = 1000000L,
                            downloadedBytes = 100000L,
                            state = DownloadState.DOWNLOADING,
                            tags = listOf("mwn_msg:msg_live", "mwn_photo:1")
                        )
                    )
                }

            val engine =
                createEngine(
                    downloadClient = client,
                    notificationPublisher = publisher,
                    config = DownloadTrackerConfig(pollIntervalSeconds = 1),
                    scope = testScope
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_stop",
                    title = "Stopping Show",
                    seriesOrMovieTitle = "Stopping Show"
                )

            engine.track("hash_stop", grab)
            assertTrue(engine.isTracking("hash_stop"))

            // Advance time slightly to let the loop enter its initial delay
            testScope.advanceTimeBy(500L)

            // Stop all trackers
            engine.stopAll()
            testScheduler.advanceUntilIdle()

            // A stop isn't an ending: the tags stay so the next instance resumes the same card.
            assertTrue(client.removedTags.isEmpty())
            assertTrue(client.deletedTags.isEmpty())
            assertEquals(0, engine.activeTrackerCount())
        }

    private fun halfway(hash: String): Either<DomainError.DownloadClientError, DownloadItemProgress?> =
        Either.Right(
            DownloadItemProgress(
                id = hash,
                name = "Test",
                progressPercent = 50.0,
                progressRatio = 0.5,
                downloadSpeedBytesPerSec = 1_000,
                uploadSpeedBytesPerSec = 0,
                etaSeconds = 60,
                totalSizeBytes = 1_000,
                downloadedBytes = 500,
                state = DownloadState.DOWNLOADING
            )
        )

    private val leaseGrab =
        MediaPayload.ArrGrab(source = AppSource.SONARR, downloadId = "h1", title = "Show", seriesOrMovieTitle = "Show")

    private val liveHandle = NotificationHandle("fake", "chat123", "msg_live")

    /** One "pod": its own engine, publisher, client and session registry, sharing [store] with the other pods. */
    private inner class Pod(
        owner: String,
        store: StateStorePort,
        scope: CoroutineScope,
        failStart: Boolean = false,
        withLeases: Boolean = true,
        progress: (String) -> Either<DomainError.DownloadClientError, DownloadItemProgress?> = { Either.Right(null) },
        client: DownloadClientPort? = null
    ) {
        val publisher = newPublisher(shouldFailStart = failStart)
        val torrents = FakeTorrentClient(progress)
        val engine =
            DownloadTrackerEngine(
                downloadClient = client ?: torrents,
                notificationPublisher = publisher,
                activeTrackerStore = InMemoryActiveTrackerStore(),
                config = DownloadTrackerConfig(pollIntervalSeconds = 5),
                scope = scope,
                leases = if (withLeases) TrackingLeases(store, owner, ttlSeconds = 30) else null
            )
    }

    @Test
    fun `a download tracked by another pod gets no second card`() =
        runTest {
            val store = InMemoryStateStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope)
            val b = Pod("pod-b", store, scope)

            assertEquals(Either.Right(TrackingStart.STARTED), a.engine.track("h1", leaseGrab))
            assertEquals(Either.Right(TrackingStart.TRACKED_ELSEWHERE), b.engine.track("h1", leaseGrab))

            assertTrue(b.publisher.sentCards.isEmpty())
            assertTrue(b.torrents.addedTags.isEmpty())
        }

    @Test
    fun `a second request on the same pod is ALREADY_TRACKED`() =
        runTest {
            val a = Pod("pod-a", InMemoryStateStore(), TestScope(StandardTestDispatcher(testScheduler)))

            a.engine.track("h1", leaseGrab)

            assertEquals(
                Either.Right(TrackingStart.ALREADY_TRACKED),
                a.engine.trackExisting("h1", leaseGrab, liveHandle, false)
            )
        }

    @Test
    fun `stopAll keeps resume tags and releases leases`() =
        runTest {
            val store = InMemoryStateStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope)
            val b = Pod("pod-b", store, scope)
            a.engine.track("h1", leaseGrab)

            a.engine.stopAll()

            assertTrue(a.torrents.removedTags.isEmpty(), "resume tags must survive a shutdown")
            assertTrue(a.torrents.deletedTags.isEmpty())
            assertTrue(store.acquireLock("lock:track:h1", "x", 30), "lease must be released")
            store.releaseLock("lock:track:h1", "x")
            assertEquals(
                Either.Right(TrackingStart.STARTED),
                b.engine.trackExisting("h1", leaseGrab, liveHandle, false)
            )
        }

    @Test
    fun `a pod that lost its lease stops without touching the card`() =
        runTest {
            val store = InMemoryStateStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope, progress = ::halfway)
            a.engine.track("h1", leaseGrab)

            store.releaseLock("lock:track:h1", "pod-a")
            store.acquireLock("lock:track:h1", "pod-b", 30)
            scope.advanceTimeBy(6_000)

            assertFalse(a.engine.isTracking("h1"))
            assertTrue(a.publisher.progressUpdates.isEmpty())
            assertTrue(a.publisher.completedCards.isEmpty())
            assertTrue(a.publisher.cancelledCards.isEmpty())
            assertTrue(a.torrents.removedTags.isEmpty())
        }

    @Test
    fun `tracking continues when the store can't renew`() =
        runTest {
            val store = spyk(InMemoryStateStore())
            coEvery { store.renewLock(any(), any(), any(), any()) } returns LockRenewal.STORE_UNAVAILABLE
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope, progress = ::halfway)
            a.engine.track("h1", leaseGrab)

            scope.advanceTimeBy(12_000)

            assertTrue(a.engine.isTracking("h1"))
            assertTrue(a.publisher.progressUpdates.isNotEmpty())
        }

    @Test
    fun `a pod holding a|b blocks a sweep that only sees b`() =
        runTest {
            val store = InMemoryStateStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope)
            val b = Pod("pod-b", store, scope)

            assertEquals(Either.Right(TrackingStart.STARTED), a.engine.track("a|b", leaseGrab))

            assertEquals(
                Either.Right(TrackingStart.TRACKED_ELSEWHERE),
                b.engine.trackExisting("b", leaseGrab, liveHandle, false)
            )
            assertEquals(
                Either.Right(TrackingStart.ALREADY_TRACKED),
                a.engine.trackExisting("b", leaseGrab, liveHandle, false)
            )
            assertEquals(1, a.engine.activeTrackerCount())
        }

    @Test
    fun `a failed initial card releases the leases`() =
        runTest {
            val store = InMemoryStateStore()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", store, scope, failStart = true)
            val b = Pod("pod-b", store, scope)

            assertTrue(a.engine.track("h1", leaseGrab).isLeft())
            assertEquals(Either.Right(TrackingStart.STARTED), b.engine.track("h1", leaseGrab))
        }

    @Test
    fun `without leases cancellation keeps tags`() =
        runTest {
            val a =
                Pod("pod-a", InMemoryStateStore(), TestScope(StandardTestDispatcher(testScheduler)), withLeases = false)
            a.engine.track("h1", leaseGrab)

            a.engine.stopAll()

            assertTrue(a.torrents.removedTags.isEmpty())
            assertTrue(a.torrents.deletedTags.isEmpty())
        }

    @Test
    fun `stopAll returns within its bound when a loop won't cancel`() =
        runTest {
            val stuck =
                DownloadClientPort { hash ->
                    withContext(NonCancellable) { delay(60_000) }
                    halfway(hash)
                }
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val a = Pod("pod-a", InMemoryStateStore(), scope, client = stuck)
            a.engine.track("h1", leaseGrab)
            scope.advanceTimeBy(6_000)

            val before = currentTime
            a.engine.stopAll()

            assertTrue(currentTime - before <= 3_000, "stopAll took ${currentTime - before}ms")
        }

    @Test
    fun `a cancelled duplicate loop leaves the surviving session alone`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val store = spyk(InMemoryActiveTrackerStore())
            val survivorJob = Job()
            store.register(
                app.hononeko.notifier.domain.model.ActiveTrackerSession(
                    hash = "h1",
                    payload = leaseGrab,
                    handle = liveHandle,
                    isPhoto = false,
                    job = survivorJob
                )
            )
            // The race: the duplicate's pre-check misses the survivor, then register() finds it.
            every { store.isTracking("h1") } returns false andThenAnswer { callOriginal() }
            val engine =
                createEngine(
                    downloadClient = FakeTorrentClient(),
                    notificationPublisher = newPublisher(),
                    activeTrackerStore = store,
                    scope = testScope
                )

            engine.track("h1", leaseGrab)
            testScope.advanceTimeBy(1)

            assertFalse(survivorJob.isCancelled, "the surviving loop must keep running")
            assertTrue(store.get("h1")?.job === survivorJob)
        }
}
