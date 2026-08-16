package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.ActionLinkDto
import app.hononeko.notifier.adapter.inbound.web.controller.CardFieldDto
import app.hononeko.notifier.adapter.inbound.web.controller.RenderedCardDto
import app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewRequestDto
import app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewResponseDto
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class TemplatePreviewRoutesTest {
    private val testToken = "secret123"
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun withPreviewApp(
        authToken: String = testToken,
        enablePreview: Boolean = true,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit
    ) = testApplication {
        val eventRail = EventRail(capacity = 50)
        application {
            install(ServerContentNegotiation) {
                json(testJson)
            }
            configureWebhookRouting(
                eventRail,
                ServerConfig(authToken = authToken, enablePreview = enablePreview)
            )
        }
        val client =
            createClient {
                install(ClientContentNegotiation) {
                    json(testJson)
                }
            }
        block(client)
    }

    @Test
    fun `preview endpoint returns 404 when enablePreview is false`() =
        withPreviewApp(enablePreview = false) { client ->
            val response =
                client.post("/api/v1/templates/preview") {
                    header(HttpHeaders.Authorization, "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"event_type":"grab"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `preview endpoint returns 401 when unauthenticated and token is required`() =
        withPreviewApp { client ->
            val response =
                client.post("/api/v1/templates/preview") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event_type":"grab"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `preview endpoint returns 400 when json payload is invalid`() =
        withPreviewApp { client ->
            val response =
                client.post("/api/v1/templates/preview") {
                    header(HttpHeaders.Authorization, "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody("{invalid-json}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid JSON payload"))
        }

    @Test
    fun `preview endpoint renders grab event with custom yaml`() =
        withPreviewApp { client ->
            val requestBody =
                """
                {
                  "event_type": "grab",
                  "template_yaml": "events:\n  grab:\n    title: '🎯 Custom: {title}'\n    body: '▪ <b>Calidad:</b> {quality}'"
                }
                """.trimIndent()

            val response =
                client.post("/api/v1/templates/preview") {
                    header(HttpHeaders.Authorization, "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("🎯 Custom: Breaking Bad"))
            assertTrue(body.contains("▪ <b>Calidad:</b> WEBDL-1080p"))
        }

    @Test
    fun `preview endpoint renders download_progress event with custom yaml`() =
        withPreviewApp { client ->
            val requestBody =
                """
                {
                  "event_type": "download_progress",
                  "template_yaml": "events:\n  download_progress:\n    body: '🚀 {progress_percent}% - {speed}'"
                }
                """.trimIndent()

            val response =
                client.post("/api/v1/templates/preview") {
                    header(HttpHeaders.Authorization, "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("🚀 68.50% - 15.0 MB/s"))
        }

    @Test
    fun `preview endpoint renders all mock event types correctly`() =
        withPreviewApp(authToken = "") { client ->
            val eventTypes =
                listOf(
                    "download_complete",
                    "download_stalled",
                    "import",
                    "media_available",
                    "health",
                    "manual_interaction",
                    "request",
                    "issue",
                    "unknown_event"
                )

            for (evt in eventTypes) {
                val response =
                    client.post("/api/v1/templates/preview") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"event_type":"$evt"}""")
                    }

                assertEquals(HttpStatusCode.OK, response.status, "Failed for event $evt")
                val responseDto = testJson.decodeFromString<TemplatePreviewResponseDto>(response.bodyAsText())
                assertEquals("success", responseDto.status)
                assertTrue(responseDto.tagsAvailable.isNotEmpty())
                assertNotNull(responseDto.renderedCard.title)
                assertTrue(responseDto.telegramHtml.isNotBlank())
            }
        }

    @Test
    fun `dto constructors and data models coverage`() {
        val req = TemplatePreviewRequestDto(templateYaml = "foo: bar", eventType = "grab")
        assertEquals("foo: bar", req.templateYaml)
        assertEquals("grab", req.eventType)

        val field = CardFieldDto("Quality", "1080p", true)
        assertEquals("Quality", field.name)
        assertEquals("1080p", field.value)
        assertTrue(field.inline)

        val action = ActionLinkDto("Watch", "https://plex.tv", "PRIMARY")
        assertEquals("Watch", action.label)
        assertEquals("https://plex.tv", action.url)
        assertEquals("PRIMARY", action.style)

        val card =
            RenderedCardDto(
                title = "Title",
                subtitle = "Sub",
                level = "SUCCESS",
                customBody = "Body",
                overview = "Overview",
                fields = listOf(field),
                artworkUrl = "https://img.jpg",
                actions = listOf(action)
            )
        assertEquals("Title", card.title)
        assertEquals("Sub", card.subtitle)

        val resp =
            TemplatePreviewResponseDto(
                status = "success",
                eventType = "grab",
                renderedCard = card,
                telegramHtml = "<b>Title</b>",
                tagsAvailable = listOf("title")
            )
        assertEquals("success", resp.status)
        assertEquals(1, resp.tagsAvailable.size)
    }
}
