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
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentReconciliationServiceTest {
    private class FakeNotificationPublisher : NotificationPublisherPort {
        override val providerId: String = "telegram"
        override val defaultChannelOrChatId: String = "chat123"

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            Either.Right(NotificationHandle("telegram", "chat123", "msg_send"))

        override suspend fun startLiveProgress(
            initialCard: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            Either.Right(NotificationHandle("telegram", "chat123", "msg_live"))

        override suspend fun updateProgress(
            handle: NotificationHandle,
            update: ProgressUpdate
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

        override suspend fun completeProgress(
            handle: NotificationHandle,
            finalCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

        override suspend fun cancelProgress(
            handle: NotificationHandle,
            reasonCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)
    }

    @Test
    fun `should resume existing tracked torrent without sending duplicate initial card`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            val resumedExistingCalls = Collections.synchronizedList(mutableListOf<Pair<String, NotificationHandle>>())
            val newTrackCalls = Collections.synchronizedList(mutableListOf<String>())

            val trackUseCase =
                object : TrackDownloadUseCase {
                    override suspend fun track(
                        hash: String,
                        initialPayload: MediaPayload.ArrGrab
                    ): Either<DomainError, Unit> {
                        newTrackCalls.add(hash)
                        return Either.Right(Unit)
                    }

                    override suspend fun trackExisting(
                        hash: String,
                        payload: MediaPayload.ArrGrab,
                        handle: NotificationHandle,
                        isPhoto: Boolean
                    ): Either<DomainError, Unit> {
                        resumedExistingCalls.add(hash to handle)
                        return Either.Right(Unit)
                    }
                }

            val torrentWithTags =
                TorrentProgress(
                    hash = "hash_with_tag",
                    name = "Severance.S02E01",
                    progressPercent = 60.0,
                    progressRatio = 0.6,
                    downloadSpeedBytesPerSec = 1000000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 60L,
                    totalSizeBytes = 1000000000L,
                    downloadedBytes = 600000000L,
                    state = TorrentState.DOWNLOADING,
                    tags = listOf("tv-sonarr", "mwn_msg:48201", "mwn_photo:1")
                )

            val torrentClient =
                object : TorrentClientPort {
                    override suspend fun getTorrentProgress(
                        hash: String
                    ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(torrentWithTags)

                    override suspend fun getActiveTorrents(
                        filter: String
                    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                        Either.Right(listOf(torrentWithTags))
                }

            val reconciliationService =
                TorrentReconciliationService(
                    torrentClient = torrentClient,
                    trackDownloadUseCase = trackUseCase,
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )

            val resumedCount = reconciliationService.reconcile()
            assertEquals(1, resumedCount)
            assertEquals(1, reconciliationService.resumedCount)
            assertEquals(1, reconciliationService.runCount)
            assertEquals("hash_with_tag", resumedExistingCalls.first().first)
            assertEquals("48201", resumedExistingCalls.first().second.messageReferenceId)
            assertEquals("chat123", resumedExistingCalls.first().second.channelOrChatId)
            assertTrue(resumedExistingCalls.first().second.isPhoto)
            assertEquals(0, newTrackCalls.size)
        }

    @Test
    fun `should prioritize mwn_chat tag over default channel when present`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            val resumedExistingCalls = Collections.synchronizedList(mutableListOf<Pair<String, NotificationHandle>>())

            val trackUseCase =
                object : TrackDownloadUseCase {
                    override suspend fun track(
                        hash: String,
                        initialPayload: MediaPayload.ArrGrab
                    ): Either<DomainError, Unit> = Either.Right(Unit)

                    override suspend fun trackExisting(
                        hash: String,
                        payload: MediaPayload.ArrGrab,
                        handle: NotificationHandle,
                        isPhoto: Boolean
                    ): Either<DomainError, Unit> {
                        resumedExistingCalls.add(hash to handle)
                        return Either.Right(Unit)
                    }
                }

            val torrentWithChatTag =
                TorrentProgress(
                    hash = "hash_custom_chat",
                    name = "Custom.Movie",
                    progressPercent = 50.0,
                    progressRatio = 0.5,
                    downloadSpeedBytesPerSec = 1000000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 60L,
                    totalSizeBytes = 1000000000L,
                    downloadedBytes = 500000000L,
                    state = TorrentState.DOWNLOADING,
                    tags = listOf("mwn_msg:9999", "mwn_photo:0", "mwn_chat:-100123456789")
                )

            val torrentClient =
                object : TorrentClientPort {
                    override suspend fun getTorrentProgress(
                        hash: String
                    ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(torrentWithChatTag)

                    override suspend fun getActiveTorrents(
                        filter: String
                    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                        Either.Right(listOf(torrentWithChatTag))
                }

            val service =
                TorrentReconciliationService(
                    torrentClient = torrentClient,
                    trackDownloadUseCase = trackUseCase,
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )

            val resumedCount = service.reconcile()
            assertEquals(1, resumedCount)
            assertEquals("-100123456789", resumedExistingCalls.first().second.channelOrChatId)
            assertEquals("9999", resumedExistingCalls.first().second.messageReferenceId)
            assertEquals(false, resumedExistingCalls.first().second.isPhoto)
        }

    @Test
    fun `should start new track for active download missing mwn tags`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            val newTrackCalls = Collections.synchronizedList(mutableListOf<String>())
            val trackUseCase =
                object : TrackDownloadUseCase {
                    override suspend fun track(
                        hash: String,
                        initialPayload: MediaPayload.ArrGrab
                    ): Either<DomainError, Unit> {
                        newTrackCalls.add(hash)
                        return Either.Right(Unit)
                    }
                }

            val untrackedTorrent =
                TorrentProgress(
                    hash = "hash_untracked",
                    name = "New.Movie.2026",
                    progressPercent = 10.0,
                    progressRatio = 0.1,
                    downloadSpeedBytesPerSec = 500000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 300L,
                    totalSizeBytes = 2000000000L,
                    downloadedBytes = 200000000L,
                    state = TorrentState.DOWNLOADING,
                    tags = listOf("radarr")
                )

            val torrentClient =
                object : TorrentClientPort {
                    override suspend fun getTorrentProgress(
                        hash: String
                    ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(untrackedTorrent)

                    override suspend fun getActiveTorrents(
                        filter: String
                    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                        Either.Right(listOf(untrackedTorrent))
                }

            val reconciliationService =
                TorrentReconciliationService(
                    torrentClient = torrentClient,
                    trackDownloadUseCase = trackUseCase,
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )

            val count = reconciliationService.reconcile()
            assertEquals(1, count)
            assertEquals(1, newTrackCalls.size)
            assertEquals("hash_untracked", newTrackCalls.first())
        }

    @Test
    fun `should skip torrents already tracked in store or with blank hashes`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            val trackCalls = Collections.synchronizedList(mutableListOf<String>())
            val trackUseCase =
                object : TrackDownloadUseCase {
                    override suspend fun track(
                        hash: String,
                        initialPayload: MediaPayload.ArrGrab
                    ): Either<DomainError, Unit> {
                        trackCalls.add(hash)
                        return Either.Right(Unit)
                    }
                }

            val torrent =
                TorrentProgress(
                    hash = "already_tracked",
                    name = "Show",
                    progressPercent = 50.0,
                    progressRatio = 0.5,
                    downloadSpeedBytesPerSec = 1000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 10L,
                    totalSizeBytes = 1000L,
                    downloadedBytes = 500L,
                    state = TorrentState.DOWNLOADING
                )
            val blankTorrent =
                TorrentProgress(
                    hash = "   ",
                    name = "Blank",
                    progressPercent = 0.0,
                    progressRatio = 0.0,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 0L,
                    downloadedBytes = 0L,
                    state = TorrentState.DOWNLOADING
                )

            // Register in store beforehand
            val session =
                app.hononeko.notifier.domain.model.ActiveTrackerSession(
                    hash = "already_tracked",
                    payload =
                        MediaPayload.ArrGrab(
                            source = AppSource.SONARR,
                            downloadId = "already_tracked",
                            title = "Show",
                            seriesOrMovieTitle = "Show"
                        ),
                    handle = NotificationHandle("telegram", "chat", "msg"),
                    isPhoto = false,
                    job = kotlinx.coroutines.Job()
                )
            store.register(session)

            val torrentClient =
                object : TorrentClientPort {
                    override suspend fun getTorrentProgress(
                        hash: String
                    ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(torrent)

                    override suspend fun getActiveTorrents(
                        filter: String
                    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                        Either.Right(listOf(torrent, blankTorrent))
                }

            val reconciliationService =
                TorrentReconciliationService(
                    torrentClient = torrentClient,
                    trackDownloadUseCase = trackUseCase,
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )

            val count = reconciliationService.reconcile()
            assertEquals(0, count)
            assertEquals(0, trackCalls.size)
        }

    @Test
    fun `should handle disabled state, client errors, and use case failures gracefully`() =
        runTest {
            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            // 1. Disabled state
            val disabledService =
                TorrentReconciliationService(
                    torrentClient =
                        object : TorrentClientPort {
                            override suspend fun getTorrentProgress(
                                hash: String
                            ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(null)

                            override suspend fun getActiveTorrents(
                                filter: String
                            ): Either<DomainError.TorrentClientError, List<TorrentProgress>> = Either.Right(emptyList())
                        },
                    trackDownloadUseCase = { _, _ -> Either.Right(Unit) },
                    activeTrackerStore = store,
                    notificationPublisher = publisher,
                    enabled = false
                )
            assertEquals(0, disabledService.reconcile())
            assertNull(disabledService.start(this))

            // 2. Client query error
            val failingClientService =
                TorrentReconciliationService(
                    torrentClient =
                        object : TorrentClientPort {
                            override suspend fun getTorrentProgress(
                                hash: String
                            ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(null)

                            override suspend fun getActiveTorrents(
                                filter: String
                            ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                                Either.Left(
                                    DomainError.TorrentClientError.ConnectionFailed(
                                        "http://fail",
                                        RuntimeException("Boom")
                                    )
                                )
                        },
                    trackDownloadUseCase = { _, _ -> Either.Right(Unit) },
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )
            assertEquals(0, failingClientService.reconcile())

            // 3. Track use case failure
            val failingTrackService =
                TorrentReconciliationService(
                    torrentClient =
                        object : TorrentClientPort {
                            override suspend fun getTorrentProgress(
                                hash: String
                            ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(null)

                            override suspend fun getActiveTorrents(
                                filter: String
                            ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
                                Either.Right(
                                    listOf(
                                        TorrentProgress(
                                            hash = "hash_fail",
                                            name = "Fail",
                                            progressPercent = 10.0,
                                            progressRatio = 0.1,
                                            downloadSpeedBytesPerSec = 0L,
                                            uploadSpeedBytesPerSec = 0L,
                                            etaSeconds = 0L,
                                            totalSizeBytes = 100L,
                                            downloadedBytes = 10L,
                                            state = TorrentState.DOWNLOADING
                                        )
                                    )
                                )
                        },
                    trackDownloadUseCase = { _, _ ->
                        Either.Left(DomainError.NotificationError.DeliveryFailed("test", "Fail"))
                    },
                    activeTrackerStore = store,
                    notificationPublisher = publisher
                )
            assertEquals(0, failingTrackService.reconcile())
        }

    @Test
    fun `should run startup sync and periodic loops when started`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val store = InMemoryActiveTrackerStore()
            val publisher = FakeNotificationPublisher()

            val torrent =
                TorrentProgress(
                    hash = "hash_periodic",
                    name = "Show",
                    progressPercent = 20.0,
                    progressRatio = 0.2,
                    downloadSpeedBytesPerSec = 1000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 100L,
                    totalSizeBytes = 10000L,
                    downloadedBytes = 2000L,
                    state = TorrentState.DOWNLOADING
                )

            val torrentClient =
                object : TorrentClientPort {
                    override suspend fun getTorrentProgress(
                        hash: String
                    ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(torrent)

                    override suspend fun getActiveTorrents(
                        filter: String
                    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> = Either.Right(listOf(torrent))
                }

            val reconciliationService =
                TorrentReconciliationService(
                    torrentClient = torrentClient,
                    trackDownloadUseCase = { _, _ -> Either.Right(Unit) },
                    activeTrackerStore = store,
                    notificationPublisher = publisher,
                    intervalMinutes = 1
                )

            val job = reconciliationService.start(testScope)
            assertTrue(job != null)

            // Startup sync executed
            testScope.advanceTimeBy(100L)
            assertEquals(1L, reconciliationService.runCount)

            // Periodic tick after 1 minute (60,000 ms)
            testScope.advanceTimeBy(61000L)
            assertEquals(2L, reconciliationService.runCount)

            job.cancel()
        }
}
