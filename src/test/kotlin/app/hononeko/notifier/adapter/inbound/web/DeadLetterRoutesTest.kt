package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.config.ServerConfig
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class DeadLetterRoutesTest {
    private val token = "admin-secret"
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val downloadWebhook =
        """
        {
          "eventType": "Download",
          "instanceName": "Sonarr-Main",
          "series": { "id": 1, "title": "Severance" },
          "episodes": [ { "id": 10, "episodeNumber": 1, "seasonNumber": 2, "title": "Hello" } ],
          "isUpgrade": false
        }
        """.trimIndent()

    private fun ApplicationTestBuilder.installRoutes(
        eventRail: EventRail,
        authToken: String = token
    ) {
        application {
            install(ServerContentNegotiation) { json(testJson) }
            configureWebhookRouting(eventRail, ServerConfig(authToken = authToken))
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.listDeadLetters(query: String = ""): JsonObject =
        get("/api/v1/dlq?token=$token$query").json()

    private fun grab(id: String) =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = id,
            title = "Show $id",
            seriesOrMovieTitle = "Show"
        )

    @Test
    fun `should capture, list, inspect, replay and clear dead letters end to end`() =
        testApplication {
            val healthy = AtomicBoolean(false)
            val processed = AtomicInteger(0)
            val ingest =
                IngestWebhookUseCase {
                    processed.incrementAndGet()
                    check(healthy.get()) { "Telegram unreachable" }
                    Either.Right(Unit)
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventRail = EventRail(capacity = 50)
            eventRail.start(scope, ingest, workerCount = 1)
            installRoutes(eventRail)

            try {
                val accepted =
                    client.post("/api/v1/webhook/sonarr?token=$token") {
                        contentType(ContentType.Application.Json)
                        setBody(downloadWebhook)
                    }
                assertEquals(HttpStatusCode.Accepted, accepted.status)
                withTimeout(5_000) {
                    while (eventRail.deadLetterBuffer.size() == 0) delay(10)
                }

                // Listing
                val listing = client.listDeadLetters()
                assertEquals(1, listing.getValue("total").jsonPrimitive.int)
                val summary =
                    listing
                        .getValue("entries")
                        .jsonArray
                        .single()
                        .jsonObject
                val id = summary.getValue("id").jsonPrimitive.content
                assertEquals("sonarr", summary.getValue("provider").jsonPrimitive.content)
                assertEquals("Sonarr-Main", summary.getValue("instanceName").jsonPrimitive.content)
                assertEquals("DOWNLOAD", summary.getValue("eventType").jsonPrimitive.content)
                assertEquals("pending", summary.getValue("status").jsonPrimitive.content)
                assertEquals("Telegram unreachable", summary.getValue("errorMessage").jsonPrimitive.content)
                assertTrue(
                    summary
                        .getValue("payloadPreview")
                        .jsonPrimitive.content
                        .contains("arr_download")
                )

                // Detail
                val detail = client.get("/api/v1/dlq/$id") { header("Authorization", "Bearer $token") }
                assertEquals(HttpStatusCode.OK, detail.status)
                val detailJson = detail.json()
                val payload = detailJson.getValue("payload").jsonObject
                assertEquals("arr_download", payload.getValue("payloadType").jsonPrimitive.content)
                assertEquals("Severance", payload.getValue("seriesOrMovieTitle").jsonPrimitive.content)
                assertTrue(
                    detailJson
                        .getValue("stackTrace")
                        .jsonPrimitive.content
                        .contains("IllegalStateException")
                )

                // Replay once the downstream issue is fixed
                healthy.set(true)
                val replay = client.post("/api/v1/dlq/$id/replay") { header("X-Api-Key", token) }
                assertEquals(HttpStatusCode.Accepted, replay.status)
                assertEquals(
                    "resolved",
                    replay
                        .json()
                        .getValue("entry")
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive.content
                )
                withTimeout(5_000) {
                    while (processed.get() < 2) delay(10)
                }
                assertEquals(1, eventRail.deadLetterBuffer.size())
                assertEquals(
                    1,
                    client
                        .listDeadLetters("&status=resolved")
                        .getValue("total")
                        .jsonPrimitive.int
                )
                assertEquals(
                    0,
                    client
                        .listDeadLetters("&status=pending")
                        .getValue("total")
                        .jsonPrimitive.int
                )

                val secondReplay = client.post("/api/v1/dlq/$id/replay?apikey=$token")
                assertEquals(HttpStatusCode.Conflict, secondReplay.status)

                // Deletion
                val cleared = client.delete("/api/v1/dlq?token=$token")
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertEquals(
                    1,
                    cleared
                        .json()
                        .getValue("cleared")
                        .jsonPrimitive.int
                )
                assertEquals(
                    0,
                    client
                        .listDeadLetters()
                        .getValue("total")
                        .jsonPrimitive.int
                )
            } finally {
                eventRail.close()
                scope.cancel()
            }
        }

    @Test
    fun `should paginate newest first and validate query parameters`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            installRoutes(eventRail)
            val ids = (1..5).map { eventRail.deadLetterBuffer.record(grab("h$it"), "Error $it").id }

            val page = client.listDeadLetters("&limit=2&offset=1")
            assertEquals(5, page.getValue("total").jsonPrimitive.int)
            assertEquals(2, page.getValue("limit").jsonPrimitive.int)
            assertEquals(1, page.getValue("offset").jsonPrimitive.int)
            assertEquals(
                listOf(ids[3], ids[2]),
                page.getValue("entries").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            )

            assertEquals(
                5,
                client
                    .listDeadLetters()
                    .getValue("entries")
                    .jsonArray.size
            )

            listOf("&limit=0", "&limit=501", "&limit=abc", "&offset=-1", "&offset=x", "&status=bogus").forEach {
                val response = client.get("/api/v1/dlq?token=$token$it")
                assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for '$it'")
            }
        }

    @Test
    fun `should return 404 for unknown dead letters`() =
        testApplication {
            installRoutes(EventRail(capacity = 10))

            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/dlq/missing?token=$token").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/dlq/missing/replay?token=$token").status)
        }

    @Test
    fun `should return 503 and keep entry pending when the event rail rejects a replay`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            installRoutes(eventRail)
            val entry = eventRail.deadLetterBuffer.record(grab("h1"), "Ingest failed")
            eventRail.close()

            val response = client.post("/api/v1/dlq/${entry.id}/replay?token=$token")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(DeadLetterStatus.PENDING, eventRail.deadLetterBuffer.get(entry.id)?.status)
        }

    @Test
    fun `should reject requests without a valid admin token`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            installRoutes(eventRail)
            val entry = eventRail.deadLetterBuffer.record(grab("h1"), "Ingest failed")

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/dlq").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/dlq?token=wrong").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/dlq/${entry.id}").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/dlq/${entry.id}/replay").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/dlq").status)
            assertEquals(1, eventRail.deadLetterBuffer.size())
            assertEquals(DeadLetterStatus.PENDING, eventRail.deadLetterBuffer.get(entry.id)?.status)
        }

    @Test
    fun `should refuse all DLQ access when no SERVER_AUTH_TOKEN is configured`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            installRoutes(eventRail, authToken = "")
            val entry = eventRail.deadLetterBuffer.record(grab("h1"), "Ingest failed")

            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/dlq").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/dlq/${entry.id}").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/dlq/${entry.id}/replay").status)
            assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/dlq").status)
            assertEquals(1, eventRail.deadLetterBuffer.size())
        }

    @Test
    fun `should treat a token list without usable entries as unconfigured`() =
        testApplication {
            installRoutes(EventRail(capacity = 10), authToken = " , ")

            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/dlq").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/dlq?token=anything").status)
        }
}
