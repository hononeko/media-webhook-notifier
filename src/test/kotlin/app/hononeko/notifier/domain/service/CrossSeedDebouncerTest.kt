package app.hononeko.notifier.domain.service

import app.hononeko.notifier.AppDependencies
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.testing.RecordingPublisher
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CrossSeedDebouncerTest {
    @Test
    fun `groups rapid injected events for same media across trackers and deduplicates`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val flushed = Collections.synchronizedList(mutableListOf<MediaPayload.CrossSeedResult>())
            val debouncer =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedResult = { flushed.add(it) }
                )

            debouncer.submit(
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Movie.Name.2024.1080p.BluRay.x264-GRP",
                    trackers = listOf("seedpool (API)", "TorrentLeech"),
                    decisions = listOf("MATCH"),
                    trigger = "announce",
                    category = null,
                    sizeBytes = null,
                    infoHashes = listOf("hash1"),
                    searcheeSource = null,
                    clientHost = null,
                    instanceName = "cross-seed"
                )
            )
            testScope.advanceTimeBy(1000L)
            debouncer.submit(
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "  movie.name.2024.1080p.bluray.x264-grp ",
                    trackers = listOf("TorrentLeech", "BLU"),
                    decisions = listOf("MATCH", "MATCH_PARTIAL"),
                    trigger = "rss",
                    category = "movies",
                    sizeBytes = 1_500_000_000L,
                    infoHashes = listOf("hash1", "hash2"),
                    searcheeSource = "torrentClient",
                    clientHost = "qbittorrent:8080",
                    instanceName = "CROSS-SEED"
                )
            )

            assertEquals(1, debouncer.activeBufferCount)
            assertEquals(0, flushed.size)

            // Sliding window should wait 2000ms after the second event.
            testScope.advanceTimeBy(1200L)
            assertEquals(0, flushed.size)

            testScope.advanceTimeBy(900L)
            assertEquals(1, flushed.size)
            assertEquals(0, debouncer.activeBufferCount)

            val combined = flushed.single()
            assertEquals(listOf("seedpool (API)", "TorrentLeech", "BLU"), combined.trackers)
            assertEquals(listOf("MATCH", "MATCH_PARTIAL"), combined.decisions)
            assertEquals(listOf("hash1", "hash2"), combined.infoHashes)
            assertEquals(1_500_000_000L, combined.sizeBytes)
            assertEquals("movies", combined.category)
            assertEquals("announce", combined.trigger)
            assertEquals("torrentClient", combined.searcheeSource)
            assertEquals("qbittorrent:8080", combined.clientHost)

            val card =
                CrossSeedCards.buildCard(
                    combined,
                    TemplateEngine(
                        app.hononeko.notifier.config.YamlParser.parseTemplateConfig(
                            """
                            events:
                              cross_seed:
                                match:
                                  body: "{trackers}|{tracker_count}|{decisions}|{info_hashes}"
                            """.trimIndent()
                        )
                    )
                )
            assertEquals(
                "seedpool (API), TorrentLeech, BLU|3|Exact, Partial|hash1, hash2",
                card.customBody
            )
        }

    @Test
    fun `keeps failures immediate and separates distinct media, instances, event types, and paused states`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val flushed = Collections.synchronizedList(mutableListOf<MediaPayload.CrossSeedResult>())
            val debouncer =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedResult = { flushed.add(it) }
                )

            val base =
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Dune.Part.Two.2024",
                    trackers = listOf("TorrentLeech"),
                    instanceName = "cross-seed"
                )
            debouncer.submit(base)
            debouncer.submit(base.copy(name = "Arrival.2016"))
            debouncer.submit(base.copy(instanceName = "cross-seed-4k"))
            debouncer.submit(base.copy(paused = true))
            debouncer.submit(base.copy(eventType = EventType.CROSS_SEED_SAVED, paused = true))
            debouncer.submit(base.copy(eventType = EventType.CROSS_SEED_FAILED, trackers = listOf("PTP")))

            // Only the failure fired immediately; the other 5 remain distinct buffered entries.
            assertEquals(1, flushed.size)
            assertEquals(EventType.CROSS_SEED_FAILED, flushed.single().eventType)
            assertEquals(5, debouncer.activeBufferCount)

            debouncer.flush(debounceKey(base))
            assertEquals(2, flushed.size)
            assertEquals(4, debouncer.activeBufferCount)

            debouncer.flushAll()
            assertEquals(6, flushed.size)
            assertEquals(0, debouncer.activeBufferCount)
            assertTrue(flushed.none { it.eventType == EventType.CROSS_SEED_INJECTED && "PTP" in it.trackers })
        }

    @Test
    fun `distributed cross-seed buffers merge across two replicas and sweeper flushes crashed replica`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val sharedStore = InMemoryStateStore()
            val flushed = Collections.synchronizedList(mutableListOf<MediaPayload.CrossSeedResult>())

            val podA =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedResult = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            val podB =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedResult = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            podA.submit(
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Severance.S02E01.1080p",
                    trackers = listOf("seedpool (API)"),
                    decisions = listOf("MATCH"),
                    infoHashes = listOf("h1"),
                    instanceName = "cross-seed"
                )
            )
            testScope.advanceTimeBy(1200L)

            podB.submit(
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Severance.S02E01.1080p",
                    trackers = listOf("seedpool (API)", "TorrentLeech"),
                    decisions = listOf("MATCH_SIZE_ONLY"),
                    infoHashes = listOf("h2"),
                    sizeBytes = 2_000_000_000L,
                    instanceName = "cross-seed"
                )
            )

            // Past Pod A's original 2000ms deadline, extended by Pod B to t=3200ms.
            testScope.advanceTimeBy(1000L)
            assertEquals(0, flushed.size)
            assertEquals(1, podA.distributedBufferCount())

            testScope.advanceTimeBy(1100L)
            assertEquals(1, flushed.size)
            val merged = flushed.single()
            assertEquals(listOf("seedpool (API)", "TorrentLeech"), merged.trackers)
            assertEquals(listOf("MATCH", "MATCH_SIZE_ONLY"), merged.decisions)
            assertEquals(listOf("h1", "h2"), merged.infoHashes)
            assertEquals(2_000_000_000L, merged.sizeBytes)
            assertEquals(0, podA.distributedBufferCount())

            // Simulate a crashed pod leaving a buffered cross-seed match that the live sweeper flushes.
            val crashedScope = TestScope(StandardTestDispatcher(testScheduler))
            val liveScope = TestScope(StandardTestDispatcher(testScheduler))
            val sweeperFlushed = Collections.synchronizedList(mutableListOf<MediaPayload.CrossSeedResult>())
            val crashedPod =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = crashedScope,
                    onDebouncedResult = { sweeperFlushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { liveScope.testScheduler.currentTime })
            val livePod =
                CrossSeedDebouncer(
                    debounceMillis = 2000L,
                    scope = liveScope,
                    onDebouncedResult = { sweeperFlushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { liveScope.testScheduler.currentTime })

            crashedPod.submit(
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Crashed.Pod.Release",
                    trackers = listOf("BLU"),
                    instanceName = "cross-seed"
                )
            )
            val sweeper = livePod.startDistributedSweeper(liveScope, intervalMillis = 1000L)
            liveScope.advanceTimeBy(3500L)
            assertEquals(1, sweeperFlushed.size)
            assertNotNull(sweeperFlushed.single())
            assertEquals(listOf("BLU"), sweeperFlushed.single().trackers)
            sweeper.cancel()
        }

    @Test
    fun `AppDependencies close flushes buffered cross-seed matches before shutdown`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val publisher = RecordingPublisher()
            val crossSeedService = CrossSeedService(publisher)
            val crossSeedDebouncer =
                CrossSeedDebouncer(
                    debounceMillis = 60_000L,
                    scope = scope,
                    onDebouncedResult = { crossSeedService.announce(it) }
                )
            val downloadClient = DownloadClientPort { Either.Right(null) }
            val mediaServer = MediaServerPort { null }
            val downloadTracker =
                DownloadTrackerEngine(
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    activeTrackerStore = InMemoryActiveTrackerStore(),
                    config = DownloadTrackerConfig(),
                    scope = scope
                )
            val seasonDebouncer = SeasonDebouncer(scope = scope)
            val imported = MediaImportedService(publisher)
            val available = MediaAvailableService(publisher, mediaServer)
            val health = SystemHealthService(publisher)
            val manual = ManualInteractionService(publisher)
            val request = MediaRequestService(publisher)
            val ingest =
                IngestWebhookService(
                    trackDownloadUseCase = downloadTracker,
                    announceMediaImportedUseCase = imported,
                    announceMediaAvailableUseCase = available,
                    seasonDebouncer = seasonDebouncer,
                    alertUseCases = AlertUseCases(health, manual, request, crossSeedService)
                ).withCrossSeedDebouncer(crossSeedDebouncer)
            val eventRail = EventRail()
            val dependencies =
                AppDependencies(
                    config = AppConfig(),
                    scope = scope,
                    downloadClient = downloadClient,
                    notificationPublisher = publisher,
                    mediaServerPort = mediaServer,
                    downloadTracker = downloadTracker,
                    seasonDebouncer = seasonDebouncer,
                    mediaImportedService = imported,
                    mediaAvailableService = available,
                    systemHealthService = health,
                    manualInteractionService = manual,
                    mediaRequestService = request,
                    ingestWebhookService = ingest,
                    eventRail = eventRail,
                    rateLimiter = InboundRateLimiter(),
                    providerRegistry = WebhookProviderRegistry(),
                    healthController = HealthController(eventRail, downloadTracker),
                    crossSeedDebouncer = crossSeedDebouncer
                )

            val first =
                MediaPayload.CrossSeedResult(
                    eventType = EventType.CROSS_SEED_INJECTED,
                    name = "Shutdown.Movie.2024",
                    trackers = listOf("TorrentLeech"),
                    decisions = listOf("MATCH"),
                    instanceName = "cross-seed"
                )
            val second = first.copy(trackers = listOf("seedpool (API)"))

            assertEquals(Either.Right(Unit), ingest.execute(first))
            assertEquals(Either.Right(Unit), ingest.execute(second))
            assertEquals(1, crossSeedDebouncer.activeBufferCount)
            assertTrue(publisher.sentCards.isEmpty())

            dependencies.close()

            assertEquals(0, crossSeedDebouncer.activeBufferCount)
            assertEquals(1, publisher.sentCards.size)
            val card = publisher.sentCards.single()
            assertEquals("cross_seed.match", card.eventType)
            assertTrue(card.fields.any { it.name == "Trackers" && it.value == "TorrentLeech, seedpool (API)" })
        }
}
