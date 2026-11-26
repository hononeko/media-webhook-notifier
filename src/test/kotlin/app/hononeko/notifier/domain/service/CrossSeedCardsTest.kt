package app.hononeko.notifier.domain.service

import app.hononeko.notifier.config.ConfigLoader
import app.hononeko.notifier.config.YamlParser
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaAvailableUseCase
import app.hononeko.notifier.domain.port.inbound.AnnounceMediaImportedUseCase
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.testing.RecordingPublisher
import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** cross-seed match and failure cards, their templates and how they're dispatched (MWN-40). */
class CrossSeedCardsTest {
    private val injected =
        MediaPayload.CrossSeedResult(
            eventType = EventType.CROSS_SEED_INJECTED,
            name = "Movie.Name.2024.1080p.BluRay.x264-GRP",
            trackers = listOf("seedpool (API)", "TorrentLeech"),
            decisions = listOf("MATCH", "MATCH", "MATCH_PARTIAL"),
            trigger = "rss",
            category = "movies",
            sizeBytes = 1234567890L,
            infoHashes = listOf("aaa111", "bbb222"),
            searcheeSource = "torrentClient",
            clientHost = "qbittorrent:8080",
            instanceName = "cross-seed"
        )
    private val failed =
        injected.copy(eventType = EventType.CROSS_SEED_FAILED, trackers = listOf("TorrentLeech"), trigger = "search")

    /** The built-in templates, plus the user's [yaml] on top, as the app loads them. */
    private fun engine(yaml: String? = null): TemplateEngine =
        TemplateEngine(ConfigLoader.load(yaml?.let { mapOf("TEMPLATES_YAML" to it) } ?: emptyMap()).templates)

    @Test
    fun `an injected match is a silent success card with the match details`() {
        val card = CrossSeedCards.buildCard(injected, TemplateEngine())

        assertEquals("🌱 Cross-Seed Injected: Movie.Name.2024.1080p.BluRay.x264-GRP", card.title)
        assertEquals("cross-seed • RSS", card.subtitle)
        assertEquals(NotificationLevel.SUCCESS, card.level)
        assertEquals("cross_seed.match", card.eventType)
        assertTrue(card.silent)
        assertNull(card.artworkUrl)
        assertTrue(card.interactions.isEmpty())
        assertEquals(
            listOf(
                CardField("Trackers", "seedpool (API), TorrentLeech", inline = false),
                CardField("Match", "Exact, Partial"),
                CardField("Status", "Injected"),
                CardField("Size", "1.15 GB"),
                CardField("Category", "movies")
            ),
            card.fields
        )
    }

    @Test
    fun `paused and saved matches say so`() {
        val paused = CrossSeedCards.buildCard(injected.copy(paused = true), TemplateEngine())
        assertEquals("⏸️ Cross-Seed Injected: Movie.Name.2024.1080p.BluRay.x264-GRP", paused.title)
        assertEquals(NotificationLevel.INFO, paused.level)
        assertTrue(CardField("Status", "Injected (paused)") in paused.fields)

        val saved =
            CrossSeedCards.buildCard(
                injected.copy(eventType = EventType.CROSS_SEED_SAVED, paused = true),
                TemplateEngine()
            )
        assertEquals("💾 Cross-Seed Saved: Movie.Name.2024.1080p.BluRay.x264-GRP", saved.title)
        assertEquals("cross_seed.match", saved.eventType)
        assertTrue(CardField("Status", "Saved") in saved.fields)
    }

    @Test
    fun `a failed injection is an audible error card naming the client`() {
        val card = CrossSeedCards.buildCard(failed, TemplateEngine())

        assertEquals("❌ Cross-Seed Failed: Movie.Name.2024.1080p.BluRay.x264-GRP", card.title)
        assertEquals("cross-seed • Search", card.subtitle)
        assertEquals(NotificationLevel.ERROR, card.level)
        assertEquals("cross_seed.failure", card.eventType)
        assertFalse(card.silent)
        assertTrue(CardField("Client", "qbittorrent:8080") in card.fields)
        assertTrue(card.fields.none { it.name == "Status" })
        assertEquals(card, PayloadCards.buildAvailableCard(failed, engine = TemplateEngine()))
    }

    @Test
    fun `unknown triggers and decisions are shown as sent, a missing trigger as Match`() {
        val card =
            CrossSeedCards.buildCard(
                injected.copy(trigger = "dataDir", decisions = listOf("FUZZY_SIZE_MISMATCH"), trackers = emptyList()),
                TemplateEngine()
            )
        assertEquals("cross-seed • DataDir", card.subtitle)
        assertTrue(CardField("Match", "FUZZY_SIZE_MISMATCH") in card.fields)
        assertTrue(card.fields.none { it.name == "Trackers" })

        assertEquals("cross-seed • Match", CrossSeedCards.buildCard(injected.copy(trigger = null)).subtitle)
    }

    @Test
    fun `the built-in templates render the match and failure bodies`() {
        val engine = engine()

        val match = CrossSeedCards.buildCard(injected.copy(category = null), engine)
        assertEquals("🌱 Cross-Seed Injected: Movie.Name.2024.1080p.BluRay.x264-GRP", match.title)
        assertEquals("cross-seed • RSS", match.subtitle)
        assertTrue(match.silent)
        assertEquals(
            """
            ▪ <b>Trackers:</b> seedpool (API), TorrentLeech
            ▪ <b>Match:</b> Exact, Partial
            ▪ <b>Status:</b> Injected
            ▪ <b>Size:</b> 1.15 GB
            """.trimIndent(),
            match.customBody
        )

        val failure = CrossSeedCards.buildCard(failed, engine)
        assertFalse(failure.silent)
        val failureBody = assertNotNull(failure.customBody)
        assertTrue(failureBody.contains("▪ <b>Client:</b> qbittorrent:8080"))
        assertFalse(failureBody.contains("Status"))
    }

    @Test
    fun `templates override chat, topic, sound and body per cross-seed event`() {
        val engine =
            engine(
                """
                events:
                  cross_seed:
                    match:
                      chat_id: "-100111"
                      topic_id: 7
                      silent: false
                      body: "{trackers} via {trigger} ({tracker_count})"
                    failure:
                      chat_id: "-100222"
                """.trimIndent()
            )

        val match = CrossSeedCards.buildCard(injected, engine)
        assertEquals("-100111", match.chatId)
        assertEquals(7L, match.topicId)
        assertFalse(match.silent)
        assertEquals("seedpool (API), TorrentLeech via RSS (2)", match.customBody)
        // The default title survives an override that leaves it out.
        assertEquals("🌱 Cross-Seed Injected: Movie.Name.2024.1080p.BluRay.x264-GRP", match.title)

        val failure = CrossSeedCards.buildCard(failed, engine)
        assertEquals("-100222", failure.chatId)
        assertNull(failure.topicId)
        assertTrue(failure.customBody!!.contains("Client"))
    }

    @Test
    fun `flat and dashed template names reach the same events`() {
        val engine =
            TemplateEngine(
                YamlParser.parseTemplateConfig(
                    """
                    events:
                      cross_seed_match:
                        title: "match {name}"
                      cross-seed:
                        failure:
                          title: "failure {name}"
                    """.trimIndent()
                )
            )

        assertEquals("match ${injected.name}", CrossSeedCards.buildCard(injected, engine).title)
        assertEquals("failure ${failed.name}", CrossSeedCards.buildCard(failed, engine).title)
    }

    @Test
    fun `the service sends the card to the channel`() =
        runTest {
            val publisher = RecordingPublisher()

            val result = CrossSeedService(publisher).announce(failed)

            assertEquals(Either.Right(Unit), result)
            assertEquals(listOf("cross_seed.failure"), publisher.sentCards.map { it.eventType })
        }

    @Test
    fun `a failed send is returned as a domain error`() =
        runTest {
            val result = CrossSeedService(RecordingPublisher(shouldFail = true)).announce(injected)

            assertIs<Either.Left<*>>(result)
        }

    @Test
    fun `ingest routes cross-seed results to their use case unless the event is disabled`() =
        runTest {
            val announced = mutableListOf<MediaPayload.CrossSeedResult>()
            val disabled = engine("events:\n  cross_seed:\n    failure:\n      enabled: false")
            val service =
                IngestWebhookService(
                    trackDownloadUseCase = TrackDownloadUseCase { _, _ -> error("not a download") },
                    announceMediaImportedUseCase = AnnounceMediaImportedUseCase { error("not an import") },
                    announceMediaAvailableUseCase = AnnounceMediaAvailableUseCase { error("not available media") },
                    alertUseCases =
                        AlertUseCases(
                            crossSeed = { payload ->
                                announced += payload
                                Either.Right(Unit)
                            }
                        ),
                    isEventEnabled = { disabled.isEnabled(it) }
                )

            assertEquals(Either.Right(Unit), service.execute(injected))
            assertEquals(Either.Right(Unit), service.execute(failed))

            assertEquals(listOf(injected), announced)
            assertEquals("cross_seed.match", templateEventName(injected))
            assertEquals("cross_seed.failure", templateEventName(failed))
        }
}
