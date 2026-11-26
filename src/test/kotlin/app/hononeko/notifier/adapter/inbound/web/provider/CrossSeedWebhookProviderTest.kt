package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** cross-seed v7 payloads, as `PushNotifier` sends them (MWN-40). */
class CrossSeedWebhookProviderTest {
    private val provider = CrossSeedWebhookProvider()

    /** Posts [body] through the provider and returns its result. */
    private fun process(
        body: String,
        callerName: String? = null
    ): WebhookProcessResult {
        var result: WebhookProcessResult? = null
        testApplication {
            routing {
                post("/webhook") {
                    result = provider.process(call, callerName)
                    call.respondText("OK")
                }
            }
            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return assertNotNull(result)
    }

    private fun queued(
        body: String,
        callerName: String? = null
    ): MediaPayload.CrossSeedResult {
        val queued = assertIs<WebhookProcessResult.Queued>(process(body, callerName))
        return assertIs<MediaPayload.CrossSeedResult>(queued.payload)
    }

    @Test
    fun `supports the cross-seed provider keys and serves its schema`() {
        assertEquals(setOf("cross-seed", "crossseed", "cross_seed"), provider.providerKeys)
        assertTrue(assertNotNull(provider.getSchemaJson()).contains("cross-seed Webhook Payload"))
    }

    @Test
    fun `acknowledges a test notification without queueing anything`() {
        val result = process(TEST_PAYLOAD, callerName = "seedbox")

        assertEquals(WebhookProcessResult.TestOk(instanceName = "seedbox"), result)
    }

    @Test
    fun `maps an injected match with every field cross-seed sends`() {
        val payload = queued(resultsPayload(result = "INJECTED"))

        assertEquals(AppSource.CROSS_SEED, payload.source)
        assertEquals(EventType.CROSS_SEED_INJECTED, payload.eventType)
        assertEquals("Movie.Name.2024.1080p.BluRay.x264-GRP", payload.name)
        assertEquals(listOf("seedpool (API)", "TorrentLeech"), payload.trackers)
        assertEquals(listOf("MATCH", "MATCH_PARTIAL"), payload.decisions)
        assertEquals("rss", payload.trigger)
        assertEquals(false, payload.paused)
        assertEquals("movies", payload.category)
        assertEquals(1234567890L, payload.sizeBytes)
        assertEquals(listOf("aaa111", "bbb222"), payload.infoHashes)
        assertEquals("torrentClient", payload.searcheeSource)
        assertEquals("qbittorrent-ui.media.svc.cluster.local:8080", payload.clientHost)
        assertEquals("cross-seed", payload.instanceName)
    }

    @Test
    fun `maps saved and failed results to their own event types`() {
        val saved = queued(resultsPayload(result = "SAVED", paused = true))
        assertEquals(EventType.CROSS_SEED_SAVED, saved.eventType)
        assertTrue(saved.paused)

        val failed = queued(resultsPayload(result = "FAILURE"), callerName = "seedbox")
        assertEquals(EventType.CROSS_SEED_FAILED, failed.eventType)
        assertEquals("seedbox", failed.instanceName)
    }

    @Test
    fun `reports the raw result as the receipt event type`() {
        val result = assertIs<WebhookProcessResult.Queued>(process(resultsPayload(result = "INJECTED")))

        assertEquals("INJECTED", result.eventType)
    }

    @Test
    fun `accepts the strings a custom cross-seed payload template produces`() {
        val payload =
            queued(
                """
                {"extra": {"event": "results", "name": "Show.S01", "result": "saved", "paused": "true",
                 "searchee": {"length": "2048", "category": null, "tags": null, "clientHost": null}}}
                """.trimIndent()
            )

        assertEquals(EventType.CROSS_SEED_SAVED, payload.eventType)
        assertTrue(payload.paused)
        assertEquals(2048L, payload.sizeBytes)
        assertEquals(null, payload.category)
        assertEquals(emptyList(), payload.trackers)
    }

    @Test
    fun `treats a saved result without paused as paused`() {
        val payload = queued("""{"extra": {"event": "RESULTS", "name": "Show.S01", "result": "SAVED"}}""")

        assertTrue(payload.paused)
    }

    @Test
    fun `ignores events and results it doesn't announce`() {
        assertEquals(
            WebhookProcessResult.Ignored("cross-seed event 'CLEANUP' is not supported", "CLEANUP"),
            process("""{"extra": {"event": "CLEANUP"}}""")
        )
        assertEquals(
            WebhookProcessResult.Ignored("cross-seed result 'ALREADY_EXISTS' is not announced", "ALREADY_EXISTS"),
            process(resultsPayload(result = "ALREADY_EXISTS"))
        )
    }

    @Test
    fun `rejects malformed JSON and payloads without an event or release name`() {
        val malformed = assertIs<WebhookProcessResult.InvalidPayload>(process("not json"))
        assertTrue(malformed.errorMessage.startsWith("Invalid cross-seed payload"))

        assertEquals(
            WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: missing extra.event"),
            process("""{"title": "cross-seed", "body": "Something happened"}""")
        )
        assertEquals(
            WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: missing extra.event"),
            process("""{"extra": {"name": "Show.S01"}}""")
        )
        assertEquals(
            WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: RESULTS without extra.name"),
            process("""{"extra": {"event": "RESULTS", "result": "INJECTED", "name": " "}}""")
        )
    }

    companion object {
        const val TEST_PAYLOAD =
            """{"title": "cross-seed", "body": "Test notification from cross-seed", "extra": {"event": "TEST"}}"""

        fun resultsPayload(
            result: String,
            paused: Boolean = false
        ): String =
            """
            {
              "title": "cross-seed",
              "body": "rss: Injected Movie.Name.2024.1080p.BluRay.x264-GRP on 2 trackers by MATCH, MATCH_PARTIAL",
              "extra": {
                "event": "RESULTS",
                "name": "Movie.Name.2024.1080p.BluRay.x264-GRP",
                "infoHashes": ["aaa111", "bbb222"],
                "trackers": ["seedpool (API)", "TorrentLeech"],
                "source": "rss",
                "result": "$result",
                "paused": $paused,
                "decisions": ["MATCH", "MATCH_PARTIAL"],
                "searchee": {
                  "category": "movies",
                  "tags": [],
                  "trackers": ["tracker.example"],
                  "length": 1234567890,
                  "clientHost": "qbittorrent-ui.media.svc.cluster.local:8080",
                  "infoHash": "ccc333",
                  "path": "/data/torrents/movies/Movie.Name.2024",
                  "source": "torrentClient"
                }
              }
            }
            """.trimIndent()
    }
}
