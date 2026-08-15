package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test
    fun `should reject blank torrent hash with Typed Error`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val publisher = FakeNotificationPublisher()
            val torrentClient = TorrentClientPort { Either.Right(null) }

            val engine =
                DownloadTrackerEngine(
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
                DownloadTrackerEngine(
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
                DownloadTrackerEngine(
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
                DownloadTrackerEngine(
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
                DownloadTrackerEngine(
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
}
