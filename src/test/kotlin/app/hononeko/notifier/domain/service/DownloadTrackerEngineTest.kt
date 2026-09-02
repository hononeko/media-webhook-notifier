package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTrackerEngineTest {
    private fun createEngine(
        torrentClient: TorrentClientPort,
        notificationPublisher: NotificationPublisherPort,
        activeTrackerStore: ActiveTrackerStore = InMemoryActiveTrackerStore(),
        pollIntervalSeconds: Long = 5,
        maxPollingMinutes: Long = 30,
        stalledTimeoutMinutes: Long = 15,
        missingGraceAttempts: Int = 6,
        webuiPublicUrl: String? = null,
        scope: CoroutineScope
    ) = DownloadTrackerEngine(
        torrentClient = torrentClient,
        notificationPublisher = notificationPublisher,
        activeTrackerStore = activeTrackerStore,
        pollIntervalSeconds = pollIntervalSeconds,
        maxPollingMinutes = maxPollingMinutes,
        stalledTimeoutMinutes = stalledTimeoutMinutes,
        missingGraceAttempts = missingGraceAttempts,
        webuiPublicUrl = webuiPublicUrl,
        scope = scope
    )

    private class FakeNotificationPublisher(
        private val shouldFailStart: Boolean = false
    ) : NotificationPublisherPort {
        override val providerId: String = "fake"

        val sentCards = Collections.synchronizedList(mutableListOf<NotificationCard>())
        val progressUpdates = Collections.synchronizedList(mutableListOf<ProgressUpdate>())
        val completedCards = Collections.synchronizedList(mutableListOf<NotificationCard>())
        val cancelledCards = Collections.synchronizedList(mutableListOf<NotificationCard>())

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> {
            sentCards.add(card)
            return Either.Right(NotificationHandle("fake", "chat123", "msg_send"))
        }

        override suspend fun startLiveProgress(
            initialCard: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> {
            if (shouldFailStart) {
                return Either.Left(DomainError.NotificationError.RateLimited("fake", 30))
            }
            sentCards.add(initialCard)
            return Either.Right(NotificationHandle("fake", "chat123", "msg_live"))
        }

        override suspend fun updateProgress(
            handle: NotificationHandle,
            update: ProgressUpdate
        ): Either<DomainError.NotificationError, Unit> {
            progressUpdates.add(update)
            return Either.Right(Unit)
        }

        override suspend fun completeProgress(
            handle: NotificationHandle,
            finalCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> {
            completedCards.add(finalCard)
            return Either.Right(Unit)
        }

        override suspend fun cancelProgress(
            handle: NotificationHandle,
            reasonCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> {
            cancelledCards.add(reasonCard)
            return Either.Right(Unit)
        }
    }

    private class FakeTorrentClient(
        private val progressProvider: (String) -> Either<DomainError.TorrentClientError, TorrentProgress?> = {
            Either.Right(null)
        }
    ) : TorrentClientPort {
        val addedTags = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val removedTags = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val deletedTags = Collections.synchronizedList(mutableListOf<List<String>>())

        override suspend fun getTorrentProgress(
            hash: String
        ): Either<DomainError.TorrentClientError, TorrentProgress?> = progressProvider(hash)

        override suspend fun addTorrentTags(
            hash: String,
            tags: List<String>
        ): Either<DomainError.TorrentClientError, Unit> {
            addedTags.add(hash to tags)
            return Either.Right(Unit)
        }

        override suspend fun removeTorrentTags(
            hash: String,
            tags: List<String>
        ): Either<DomainError.TorrentClientError, Unit> {
            removedTags.add(hash to tags)
            return Either.Right(Unit)
        }

        override suspend fun deleteTags(tags: List<String>): Either<DomainError.TorrentClientError, Unit> {
            deletedTags.add(tags)
            return Either.Right(Unit)
        }
    }

    @Test
    fun `should reject blank torrent hash with Typed Error`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = FakeNotificationPublisher()
            val torrentClient = TorrentClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
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

            val publisher = FakeNotificationPublisher()
            val callCount = AtomicInteger(0)

            val torrentClient =
                TorrentClientPort { hash ->
                    val count = callCount.incrementAndGet()
                    when (count) {
                        1 ->
                            Either.Right(
                                TorrentProgress(
                                    hash = hash,
                                    name = "Test",
                                    progressPercent = 50.0,
                                    progressRatio = 0.5,
                                    downloadSpeedBytesPerSec = 10485760,
                                    uploadSpeedBytesPerSec = 0,
                                    etaSeconds = 60,
                                    totalSizeBytes = 1000000000L,
                                    downloadedBytes = 500000000L,
                                    state = TorrentState.DOWNLOADING
                                )
                            )
                        else ->
                            Either.Right(
                                TorrentProgress(
                                    hash = hash,
                                    name = "Test",
                                    progressPercent = 100.0,
                                    progressRatio = 1.0,
                                    downloadSpeedBytesPerSec = 0,
                                    uploadSpeedBytesPerSec = 0,
                                    etaSeconds = 0,
                                    totalSizeBytes = 1000000000L,
                                    downloadedBytes = 1000000000L,
                                    state = TorrentState.COMPLETED
                                )
                            )
                    }
                }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 2,
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

            val publisher = FakeNotificationPublisher()
            val torrentClient =
                TorrentClientPort {
                    Either.Right(null)
                }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
                    missingGraceAttempts = 3,
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

            val failingPublisher = FakeNotificationPublisher(shouldFailStart = true)
            val torrentClient = TorrentClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
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

            val publisher = FakeNotificationPublisher()
            val torrentClient = TorrentClientPort { Either.Right(null) }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 10,
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

            val publisher = FakeNotificationPublisher()
            val progress =
                TorrentProgress(
                    hash = "hashMaxPoll",
                    name = "Show",
                    progressPercent = 10.0,
                    progressRatio = 0.1,
                    downloadSpeedBytesPerSec = 1000,
                    uploadSpeedBytesPerSec = 0,
                    etaSeconds = 600,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 100000L,
                    seedsCount = 1,
                    seedsTotal = 2,
                    peersCount = 1,
                    peersTotal = 2,
                    state = TorrentState.DOWNLOADING
                )
            val torrentClient = TorrentClientPort { Either.Right(progress) }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
                    maxPollingMinutes = 1, // 1 minute max
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

            val publisher = FakeNotificationPublisher()
            val stalledProgress =
                TorrentProgress(
                    hash = "hashStalled",
                    name = "Stalled Show",
                    progressPercent = 15.0,
                    progressRatio = 0.15,
                    downloadSpeedBytesPerSec = 0,
                    uploadSpeedBytesPerSec = 0,
                    etaSeconds = -1,
                    totalSizeBytes = 2000000L,
                    downloadedBytes = 300000L,
                    seedsCount = 0,
                    seedsTotal = 0,
                    peersCount = 0,
                    peersTotal = 0,
                    state = TorrentState.STALLED
                )
            val torrentClient = TorrentClientPort { Either.Right(stalledProgress) }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
                    stalledTimeoutMinutes = 1, // 1 minute stalled timeout
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

            val publisher = FakeNotificationPublisher()
            val torrentClient =
                TorrentClientPort {
                    Either.Left(DomainError.TorrentClientError.ConnectionFailed("http://localhost:8080"))
                }

            val engine =
                createEngine(
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
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

            val publisher = FakeNotificationPublisher()
            var callCount = 0
            val torrentClient =
                TorrentClientPort {
                    callCount++
                    val progressPercent = if (callCount == 1) 50.0 else 100.0
                    val state = if (callCount == 1) TorrentState.DOWNLOADING else TorrentState.COMPLETED
                    Either.Right(
                        TorrentProgress(
                            hash = "hash_exist",
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
                    torrentClient = torrentClient,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
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

            val publisher = FakeNotificationPublisher()
            val client = FakeTorrentClient()

            val engine =
                createEngine(
                    torrentClient = client,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
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

            val publisher = FakeNotificationPublisher()
            val client =
                FakeTorrentClient { hash ->
                    Either.Right(
                        TorrentProgress(
                            hash = hash,
                            name = "Full Metal Jacket",
                            progressPercent = 100.0,
                            progressRatio = 1.0,
                            downloadSpeedBytesPerSec = 0,
                            uploadSpeedBytesPerSec = 0,
                            etaSeconds = 0,
                            totalSizeBytes = 1000000L,
                            downloadedBytes = 1000000L,
                            state = TorrentState.COMPLETED,
                            tags = listOf("mwn_msg:9065", "mwn_photo:0", "mwn_chat:chat123", "non_mwn_tag")
                        )
                    )
                }

            val engine =
                createEngine(
                    torrentClient = client,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
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

            // 1. removeTorrentTags should have been called with all mwn_* tags found on torrent + session tags
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
    fun `should clean up tags in finally when tracking is stopped`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = FakeNotificationPublisher()
            val client =
                FakeTorrentClient {
                    Either.Right(
                        TorrentProgress(
                            hash = "hash_stop",
                            name = "Stopping Show",
                            progressPercent = 10.0,
                            progressRatio = 0.1,
                            downloadSpeedBytesPerSec = 1000L,
                            uploadSpeedBytesPerSec = 0L,
                            etaSeconds = 500L,
                            totalSizeBytes = 1000000L,
                            downloadedBytes = 100000L,
                            state = TorrentState.DOWNLOADING,
                            tags = listOf("mwn_msg:msg_live", "mwn_photo:1")
                        )
                    )
                }

            val engine =
                createEngine(
                    torrentClient = client,
                    notificationPublisher = publisher,
                    pollIntervalSeconds = 1,
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

            // Tags should be cleaned up via finally
            val allDeleted = client.deletedTags.flatten().toSet()
            assertTrue(allDeleted.contains("mwn_msg:msg_live"))
            assertEquals(0, engine.activeTrackerCount())
        }
}
