package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.config.ConfigLoader
import app.hononeko.notifier.config.YamlParser
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaRequestUseCase
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.testing.RecordingPublisher
import arrow.core.Either
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-event `enabled` toggles and the media server's `prefer_poster` (MWN-35). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventTogglesTest {
    private fun engineFor(yaml: String) = TemplateEngine(YamlParser.parseTemplateConfig(yaml))

    @Test
    fun `events are enabled unless a template turns them off`() {
        val engine =
            engineFor(
                """
                events:
                  servarr:
                    import:
                      enabled: false
                    grab:
                      disabled: true
                    health:
                      enabled: true
                  request:
                    disabled: false
                """.trimIndent()
            )

        assertFalse(engine.isEnabled("import"))
        assertFalse(engine.isEnabled("servarr.import"))
        assertFalse(engine.isEnabled("grab"))
        assertTrue(engine.isEnabled("health"))
        assertTrue(engine.isEnabled("request"))
        // No template at all, or a template without the flag.
        assertTrue(engine.isEnabled("media_available"))
        assertTrue(TemplateEngine().isEnabled("import"))
    }

    @Test
    fun `a flat event with only a toggle is read as a template`() {
        val config = YamlParser.parseTemplateConfig("events:\n  import:\n    enabled: false")
        assertEquals(false, config.events["import"]?.enabled)
    }

    @Test
    fun `prefer_poster and its inverse alias use_uploaded_thumb`() {
        val preferred =
            YamlParser.parseTemplateConfig(
                "events:\n  media_server:\n    available:\n      prefer_poster: true"
            )
        assertEquals(true, preferred.events["media_available"]?.preferPoster)

        val viaAlias =
            YamlParser.parseTemplateConfig("events:\n  media_server:\n    available:\n      use_uploaded_thumb: false")
        assertEquals(true, viaAlias.events["media_available"]?.preferPoster)

        val unset = YamlParser.parseTemplateConfig("events:\n  media_server:\n    available:\n      silent: true")
        assertNull(unset.events["media_available"]?.preferPoster)
    }

    @Test
    fun `merging a toggle keeps the default template and leaves other events on`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "TEMPLATES_YAML" to
                        "events:\n  servarr:\n    import:\n      enabled: false\n" +
                        "  media_server:\n    available:\n      prefer_poster: true"
                )
            )
        val import = config.templates.events["import"]
        assertEquals(false, import?.enabled)
        assertEquals(true, import?.silent)
        assertTrue(import?.title?.isNotBlank() == true)
        assertEquals(true, config.templates.events["media_available"]?.preferPoster)

        val engine = TemplateEngine(config.templates)
        assertFalse(engine.isEnabled("import"))
        assertTrue(engine.isEnabled("grab"))
        assertTrue(engine.isEnabled("media_available"))
    }

    @Test
    fun `the default templates enable every event`() {
        val engine = TemplateEngine(ConfigLoader.load(emptyMap()).templates)
        val events =
            listOf(
                "grab",
                "download_progress",
                "download_complete",
                "download_stalled",
                "import",
                "manual_interaction",
                "health",
                "media_available",
                "request",
                "issue"
            )
        events.forEach { assertTrue(engine.isEnabled(it), it) }
        assertEquals(
            false,
            ConfigLoader
                .load(emptyMap())
                .templates.events["media_available"]
                ?.preferPoster
        )
    }

    @Test
    fun `payloads map to the template event that announces them`() {
        assertEquals("grab", templateEventName(grab()))
        assertEquals("import", templateEventName(importOf("T")))
        assertEquals("media_available", templateEventName(MediaPayload.PlexLibraryNew(title = "T")))
        assertEquals("request", templateEventName(seerr(EventType.REQUEST_PENDING)))
        assertEquals("issue", templateEventName(seerr(EventType.ISSUE_CREATED)))
    }

    @Test
    fun `a disabled event is not announced while the others still are`() =
        runTest {
            val tracked = CopyOnWriteArrayList<MediaPayload.ArrGrab>()
            val imported = CopyOnWriteArrayList<MediaPayload.ArrDownload>()
            val available = CopyOnWriteArrayList<MediaPayload>()
            val requests = CopyOnWriteArrayList<MediaPayload.SeerrEvent>()
            val engine =
                engineFor(
                    "events:\n  servarr:\n    import:\n      enabled: false\n  seerr:\n    issue:\n      enabled: false"
                )
            val service =
                IngestWebhookService(
                    trackDownloadUseCase =
                        TrackDownloadUseCase { _, grab ->
                            tracked += grab
                            Either.Right(TrackingStart.STARTED)
                        },
                    announceMediaImportedUseCase =
                        AnnounceMediaImportedUseCase {
                            imported += it
                            Either.Right(Unit)
                        },
                    announceMediaAvailableUseCase =
                        AnnounceMediaAvailableUseCase {
                            available += it
                            Either.Right(Unit)
                        },
                    alertUseCases =
                        AlertUseCases(
                            mediaRequest =
                                AnnounceMediaRequestUseCase {
                                    requests += it
                                    Either.Right(Unit)
                                }
                        ),
                    isEventEnabled = engine::isEnabled
                )

            assertTrue(service.execute(importOf("Show - S01E01")).isRight())
            assertTrue(service.execute(seerr(EventType.ISSUE_CREATED)).isRight())
            assertTrue(service.execute(grab()).isRight())
            assertTrue(service.execute(MediaPayload.PlexLibraryNew(title = "Show")).isRight())
            assertTrue(service.execute(seerr(EventType.REQUEST_PENDING)).isRight())

            assertTrue(imported.isEmpty())
            assertEquals(1, tracked.size)
            assertEquals(1, available.size)
            assertEquals(listOf(EventType.REQUEST_PENDING), requests.map { it.eventType })
        }

    @Test
    fun `a disabled grab starts no card and no tracking`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val publisher = RecordingPublisher()
            val tracker = tracker(publisher, scope, disabled = setOf("grab")) { progress(100.0) }

            val result = tracker.track("hash1", grab())

            assertEquals(TrackingStart.DISABLED, result.getOrNull())
            assertTrue(publisher.sentCards.isEmpty())
            assertFalse(tracker.isTracking("hash1"))
        }

    @Test
    fun `a disabled progress keeps the grab card until the download completes`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val publisher = RecordingPublisher()
            val ticks = AtomicInteger()
            val tracker =
                tracker(publisher, scope, disabled = setOf("download_progress")) {
                    if (ticks.incrementAndGet() < 3) progress(40.0) else progress(100.0)
                }

            assertEquals(TrackingStart.STARTED, tracker.track("hash1", grab()).getOrNull())
            scope.advanceTimeBy(3 * 2_100L)

            assertTrue(publisher.progressUpdates.isEmpty())
            assertEquals(1, publisher.completedCards.size)
            assertEquals(0, tracker.activeTrackerCount())
        }

    @Test
    fun `a disabled completion leaves the card as it was and still ends tracking`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val publisher = RecordingPublisher()
            val ticks = AtomicInteger()
            val tracker =
                tracker(publisher, scope, disabled = setOf("download_complete")) {
                    if (ticks.incrementAndGet() < 2) progress(40.0) else progress(100.0)
                }

            tracker.track("hash1", grab())
            scope.advanceTimeBy(2 * 2_100L)

            assertEquals(1, publisher.progressUpdates.size)
            assertTrue(publisher.completedCards.isEmpty())
            assertEquals(0, tracker.activeTrackerCount())
        }

    @Test
    fun `prefer_poster uses the poster instead of the uploaded image`() {
        val bytes = byteArrayOf(1, 2, 3)
        val plex =
            MediaPayload.PlexLibraryNew(
                title = "Movie",
                posterUrl = "https://img.example/poster.jpg",
                artworkBytes = bytes
            )
        val posterEngine = engineFor("events:\n  media_server:\n    available:\n      prefer_poster: true")

        val preferred = PayloadCards.buildAvailableCard(plex, engine = posterEngine)
        assertNull(preferred.artworkBytes)
        assertEquals("https://img.example/poster.jpg", preferred.artworkUrl)

        val default = PayloadCards.buildAvailableCard(plex, engine = TemplateEngine())
        assertContentEquals(bytes, default.artworkBytes)

        // Without a poster, the uploaded image is all there is.
        val noPoster = PayloadCards.buildAvailableCard(plex.copy(posterUrl = null), engine = posterEngine)
        assertContentEquals(bytes, noPoster.artworkBytes)
    }

    private fun tracker(
        publisher: RecordingPublisher,
        scope: TestScope,
        disabled: Set<String>,
        progress: () -> DownloadItemProgress
    ) = DownloadTrackerEngine(
        downloadClient = DownloadClientPort { Either.Right(progress().copy(id = it)) },
        notificationPublisher = publisher,
        activeTrackerStore = InMemoryActiveTrackerStore(),
        config = DownloadTrackerConfig(pollIntervalSeconds = 2, isEventEnabled = { it !in disabled }),
        scope = scope
    )

    private fun progress(percent: Double) =
        DownloadItemProgress(
            id = "",
            name = "Show",
            progressPercent = percent,
            progressRatio = percent / 100,
            downloadSpeedBytesPerSec = 1_000,
            uploadSpeedBytesPerSec = 0,
            etaSeconds = 60,
            totalSizeBytes = 1_000_000L,
            downloadedBytes = (percent * 10_000).toLong(),
            state = if (percent >= 100) DownloadState.COMPLETED else DownloadState.DOWNLOADING
        )

    private fun grab() =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = "hash1",
            title = "Show - S01E01",
            seriesOrMovieTitle = "Show"
        )

    private fun importOf(title: String) =
        MediaPayload.ArrDownload(source = AppSource.SONARR, title = title, seriesOrMovieTitle = "Show")

    private fun seerr(type: EventType) =
        MediaPayload.SeerrEvent(eventType = type, notificationType = type.name, subject = "Dune")
}
