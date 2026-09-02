package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.config.ServerConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.IngestWebhookService
import app.hononeko.notifier.domain.service.ManualInteractionService
import app.hononeko.notifier.domain.service.MediaAvailableService
import app.hononeko.notifier.domain.service.MediaImportedService
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.SeasonDebouncer
import app.hononeko.notifier.domain.service.SystemHealthService
import arrow.core.Either
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndToEndIntegrationTest {
    private class MockPublisher : NotificationPublisherPort {
        override val providerId: String = "mock-telegram"
        val sentCards = CopyOnWriteArrayList<NotificationCard>()
        val liveProgressHandles = CopyOnWriteArrayList<NotificationHandle>()
        val progressUpdates = CopyOnWriteArrayList<Pair<NotificationHandle, ProgressUpdate>>()

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> {
            sentCards.add(card)
            return Either.Right(NotificationHandle("mock", "test-chat", System.currentTimeMillis().toString()))
        }

        override suspend fun startLiveProgress(
            initialCard: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> {
            val handle = NotificationHandle("mock", "test-chat", System.currentTimeMillis().toString())
            liveProgressHandles.add(handle)
            sentCards.add(initialCard)
            return Either.Right(handle)
        }

        override suspend fun updateProgress(
            handle: NotificationHandle,
            update: ProgressUpdate
        ): Either<DomainError.NotificationError, Unit> {
            progressUpdates.add(handle to update)
            return Either.Right(Unit)
        }

        override suspend fun completeProgress(
            handle: NotificationHandle,
            finalCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> {
            sentCards.add(finalCard)
            return Either.Right(Unit)
        }

        override suspend fun cancelProgress(
            handle: NotificationHandle,
            reasonCard: NotificationCard
        ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)
    }

    private class MockTorrentClient : TorrentClientPort {
        override suspend fun getTorrentProgress(
            hash: String
        ): Either<DomainError.TorrentClientError, TorrentProgress?> = Either.Right(null)
    }

    private class MockMediaServer : MediaServerPort {
        override fun resolveDeepLink(payload: MediaPayload): String = "https://app.plex.tv/desktop#!/server/mock"
    }

    @Test
    fun `full end-to-end flow from sonarr grab and import to plex media available`() =
        testApplication {
            val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val mockPublisher = MockPublisher()
            val mockTorrentClient = MockTorrentClient()
            val mockMediaServer = MockMediaServer()

            val config =
                AppConfig(
                    server = ServerConfig(authToken = "secret123"),
                    mediaServer = MediaServerConfig(publicUrl = "https://plex.example.com")
                )

            val downloadTracker =
                DownloadTrackerEngine(
                    torrentClient = mockTorrentClient,
                    notificationPublisher = mockPublisher,
                    activeTrackerStore = InMemoryActiveTrackerStore(),
                    pollIntervalSeconds = 1L,
                    maxPollingMinutes = 1L,
                    stalledTimeoutMinutes = 1L,
                    scope = testScope
                )

            val mediaImportedService = MediaImportedService(notificationPublisher = mockPublisher)

            val seasonDebouncer =
                SeasonDebouncer(
                    debounceMillis = 100L,
                    scope = testScope,
                    onDebouncedGrab = { grab ->
                        downloadTracker.track(grab.downloadId, grab)
                    },
                    onDebouncedDownload = { download ->
                        mediaImportedService.announce(download)
                    }
                )
            val mediaAvailableService =
                MediaAvailableService(
                    notificationPublisher = mockPublisher,
                    mediaServerPort = mockMediaServer
                )

            val systemHealthService = SystemHealthService(notificationPublisher = mockPublisher)
            val manualInteractionService = ManualInteractionService(notificationPublisher = mockPublisher)
            val mediaRequestService = MediaRequestService(notificationPublisher = mockPublisher)

            val ingestWebhookService =
                IngestWebhookService(
                    seasonDebouncer = seasonDebouncer,
                    trackDownloadUseCase = downloadTracker,
                    announceMediaImportedUseCase = mediaImportedService,
                    announceMediaAvailableUseCase = mediaAvailableService,
                    announceSystemHealthUseCase = systemHealthService,
                    announceManualInteractionUseCase = manualInteractionService,
                    announceMediaRequestUseCase = mediaRequestService
                )

            val eventRail = EventRail(capacity = 100)
            eventRail.start(testScope, ingestWebhookService)

            val rateLimiter = InboundRateLimiter(limitPerMinute = config.server.rateLimitPerMinute)
            val providerRegistry = WebhookProviderRegistry()
            val healthController = HealthController(eventRail = eventRail, downloadTracker = downloadTracker)

            val dependencies =
                AppDependencies(
                    config = config,
                    scope = testScope,
                    torrentClient = mockTorrentClient,
                    notificationPublisher = mockPublisher,
                    mediaServerPort = mockMediaServer,
                    downloadTracker = downloadTracker,
                    seasonDebouncer = seasonDebouncer,
                    mediaImportedService = mediaImportedService,
                    mediaAvailableService = mediaAvailableService,
                    systemHealthService = systemHealthService,
                    manualInteractionService = manualInteractionService,
                    mediaRequestService = mediaRequestService,
                    ingestWebhookService = ingestWebhookService,
                    eventRail = eventRail,
                    rateLimiter = rateLimiter,
                    providerRegistry = providerRegistry,
                    healthController = healthController
                )

            application {
                module(dependencies)
            }

            // 1. Health & Probes Check
            val livezRes = client.get("/livez")
            assertEquals(HttpStatusCode.OK, livezRes.status)

            val readyzRes = client.get("/readyz")
            assertEquals(HttpStatusCode.OK, readyzRes.status)
            assertTrue(readyzRes.bodyAsText().contains("UP"))

            // 2. Ingest Sonarr Grab Webhook
            val sonarrGrabPayload =
                """
                {
                  "eventType": "Grab",
                  "series": {
                    "title": "Frieren: Beyond Journey's End",
                    "year": 2023,
                    "tvdbId": 404800
                  },
                  "episodes": [
                    { "episodeNumber": 1, "title": "The Journey's End" },
                    { "episodeNumber": 2, "title": "It Didn't Have to Be Magic..." }
                  ],
                  "release": {
                    "releaseTitle": "Frieren.S01E01-E02.1080p.CR.WEB-DL.AAC2.0.H.264",
                    "quality": "1080p",
                    "size": 2847291000
                  },
                  "downloadClient": "qBittorrent",
                  "downloadId": "A1B2C3D4E5F6789012345678901234567890ABCD"
                }
                """.trimIndent()

            val grabResponse =
                client.post("/api/v1/webhook/sonarr?token=secret123") {
                    contentType(ContentType.Application.Json)
                    setBody(sonarrGrabPayload)
                }
            assertEquals(HttpStatusCode.Accepted, grabResponse.status)

            // Wait for debounce and track
            runBlocking { delay(250) }

            // 3. Ingest Sonarr Download (Import) Webhook
            val sonarrImportPayload =
                """
                {
                  "eventType": "Download",
                  "series": {
                    "title": "Frieren: Beyond Journey's End",
                    "year": 2023,
                    "tvdbId": 404800
                  },
                  "episodes": [
                    { "episodeNumber": 1, "title": "The Journey's End" }
                  ],
                  "isUpgrade": false,
                  "downloadClient": "qBittorrent"
                }
                """.trimIndent()

            val importResponse =
                client.post("/api/v1/webhook/sonarr?token=secret123") {
                    contentType(ContentType.Application.Json)
                    setBody(sonarrImportPayload)
                }
            assertEquals(HttpStatusCode.Accepted, importResponse.status)

            // 4. Ingest Plex Library New Webhook
            val plexPayload =
                """
                {
                  "event": "library.new",
                  "Metadata": {
                    "ratingKey": "12345",
                    "type": "episode",
                    "grandparentTitle": "Frieren: Beyond Journey's End",
                    "title": "The Journey's End",
                    "year": 2023,
                    "index": 1,
                    "parentIndex": 1,
                    "summary": "The party of heroes defeats the Demon King."
                  },
                  "Server": {
                    "title": "Home-Media",
                    "uuid": "server-uuid-1234"
                  }
                }
                """.trimIndent()

            val plexResponse =
                client.post("/api/v1/webhook/plex?token=secret123") {
                    contentType(ContentType.Application.Json)
                    setBody(plexPayload)
                }
            assertEquals(HttpStatusCode.Accepted, plexResponse.status)

            // 5. Ingest Seerr Media Pending Webhook
            val seerrPayload =
                """
                {
                  "notification_type": "MEDIA_PENDING",
                  "subject": "Severance (2022)",
                  "message": "New request submitted by Alice",
                  "media": {
                    "media_type": "tv",
                    "tmdbId": "95557"
                  },
                  "request": {
                    "request_id": "1",
                    "requestedBy_username": "Alice",
                    "is4k": "true"
                  },
                  "application_url": "https://seerr.example.com"
                }
                """.trimIndent()

            val seerrResponse =
                client.post("/api/v1/webhook/seerr?token=secret123") {
                    contentType(ContentType.Application.Json)
                    setBody(seerrPayload)
                }
            assertEquals(HttpStatusCode.Accepted, seerrResponse.status)

            // Wait for event rail queue processing
            runBlocking { delay(250) }

            // Assert that cards were published to mock publisher
            assertTrue(mockPublisher.sentCards.isNotEmpty())
            assertTrue(mockPublisher.sentCards.any { it.title.contains("Frieren") })
            assertTrue(mockPublisher.sentCards.any { it.title.contains("Severance") })

            // 6. Verify Metrics Telemetry
            val metricsRes = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metricsRes.status)
            val metricsBody = metricsRes.bodyAsText()
            assertTrue(metricsBody.contains("uptimeMillis"))
            assertTrue(metricsBody.contains("memory"))

            dependencies.close()
        }
}
