package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.config.ServerConfig
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `preview endpoint returns 404 when enablePreview is false`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(
                    eventRail,
                    ServerConfig(authToken = testToken, enablePreview = false)
                )
            }

            val client =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

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
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(
                    eventRail,
                    ServerConfig(authToken = testToken, enablePreview = true)
                )
            }

            val client =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val response =
                client.post("/api/v1/templates/preview") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event_type":"grab"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `preview endpoint renders grab event with custom yaml`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(
                    eventRail,
                    ServerConfig(authToken = testToken, enablePreview = true)
                )
            }

            val client =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

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
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(
                    eventRail,
                    ServerConfig(authToken = testToken, enablePreview = true)
                )
            }

            val client =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

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
}
