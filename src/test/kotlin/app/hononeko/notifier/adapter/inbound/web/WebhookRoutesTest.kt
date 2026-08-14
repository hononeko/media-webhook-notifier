package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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

class WebhookRoutesTest {
    private val testToken = "secret123"
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `should ingest Sonarr Grab and Download webhooks with 202 Accepted`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val jsonClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val grabPayload =
                """
                {
                  "eventType": "Grab",
                  "series": {
                    "id": 1,
                    "title": "Severance",
                    "images": [{"coverType": "poster", "remoteUrl": "https://example.com/poster.jpg"}]
                  },
                  "episodes": [
                    { "id": 10, "episodeNumber": 1, "seasonNumber": 2, "title": "Hello" }
                  ],
                  "release": {
                    "quality": "1080p",
                    "size": 1500000000,
                    "indexer": "PTP",
                    "releaseGroup": "FLUX"
                  },
                  "downloadId": "hash12345"
                }
                """.trimIndent()

            val grabResponse =
                jsonClient.post("/api/v1/webhook/sonarr?token=$testToken") {
                    contentType(ContentType.Application.Json)
                    setBody(grabPayload)
                }
            val body = grabResponse.bodyAsText()
            assertEquals(HttpStatusCode.Accepted, grabResponse.status)
            assertTrue(body.contains("accepted") || body.contains("queued"))

            val downloadPayload =
                """
                {
                  "eventType": "Download",
                  "series": {
                    "id": 1,
                    "title": "Severance",
                    "overview": "Workplace thriller"
                  },
                  "episodes": [
                    {
                      "id": 10,
                      "episodeNumber": 1,
                      "seasonNumber": 2,
                      "title": "Hello",
                      "episodeFile": {
                        "quality": "1080p",
                        "videoCodec": "x265",
                        "audioCodec": "EAC3",
                        "size": 1400000000
                      }
                    }
                  ],
                  "isUpgrade": false
                }
                """.trimIndent()

            val downloadResponse =
                jsonClient.post("/api/v1/webhook/sonarr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(downloadPayload)
                }
            assertEquals(HttpStatusCode.Accepted, downloadResponse.status)
        }

    @Test
    fun `should ingest Radarr Grab and Download webhooks with 202 Accepted`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val jsonClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val radarrGrabPayload =
                """
                {
                  "eventType": "Grab",
                  "movie": {
                    "id": 5,
                    "title": "Dune: Part Two",
                    "year": 2024,
                    "images": [{"coverType": "poster", "remoteUrl": "https://example.com/dune.jpg"}]
                  },
                  "release": {
                    "quality": "2160p",
                    "size": 25000000000,
                    "indexer": "HDB",
                    "releaseGroup": "DON"
                  },
                  "downloadId": "hashdune2"
                }
                """.trimIndent()

            val grabResponse =
                jsonClient.post("/api/v1/webhook/radarr") {
                    header("X-Api-Key", testToken)
                    contentType(ContentType.Application.Json)
                    setBody(radarrGrabPayload)
                }
            assertEquals(HttpStatusCode.Accepted, grabResponse.status)

            val radarrDownloadPayload =
                """
                {
                  "eventType": "Download",
                  "movie": {
                    "id": 5,
                    "title": "Dune: Part Two",
                    "year": 2024,
                    "overview": "Paul Atreides unites with Chani...",
                    "movieFile": {
                      "quality": "2160p",
                      "videoCodec": "HEVC",
                      "audioCodec": "TrueHD Atmos",
                      "size": 24000000000
                    }
                  },
                  "isUpgrade": true
                }
                """.trimIndent()

            val downloadResponse =
                jsonClient.post("/api/v1/webhook/servarr?apikey=$testToken") {
                    contentType(ContentType.Application.Json)
                    setBody(radarrDownloadPayload)
                }
            assertEquals(HttpStatusCode.Accepted, downloadResponse.status)
        }

    @Test
    fun `should handle Servarr Test webhook with 200 OK`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val jsonClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val testPayload = """{"eventType": "Test", "instanceName": "Sonarr-Main"}"""
            val response =
                jsonClient.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody(testPayload)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Test webhook received"))
        }

    @Test
    fun `should ingest Plex library new JSON and multipart webhooks`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val jsonClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val plexJson =
                """
                {
                  "event": "library.new",
                  "Server": { "uuid": "server-uuid-1234" },
                  "Metadata": {
                    "ratingKey": "1001",
                    "title": "Episode 1",
                    "grandparentTitle": "Severance",
                    "parentTitle": "Season 2",
                    "year": 2025,
                    "summary": "Great episode",
                    "duration": 3300000,
                    "Media": [
                      {
                        "videoCodec": "hevc",
                        "audioCodec": "eac3",
                        "videoResolution": "1080p"
                      }
                    ]
                  }
                }
                """.trimIndent()

            val jsonResponse =
                jsonClient.post("/api/v1/webhook/plex?token=$testToken") {
                    contentType(ContentType.Application.Json)
                    setBody(plexJson)
                }
            assertEquals(HttpStatusCode.Accepted, jsonResponse.status)

            // Multipart form data submission
            val multipartResponse =
                client.submitFormWithBinaryData(
                    url = "/api/v1/webhook/plex?token=$testToken",
                    formData =
                        formData {
                            append("payload", plexJson)
                            append(
                                "thumb",
                                byteArrayOf(1, 2, 3),
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/jpeg")
                                    append(HttpHeaders.ContentDisposition, "filename=\"thumb.jpg\"")
                                }
                            )
                        }
                )
            assertEquals(HttpStatusCode.Accepted, multipartResponse.status)

            // Ignored Plex event
            val ignoredResponse =
                jsonClient.post("/api/v1/webhook/plex?token=$testToken") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event": "media.play"}""")
                }
            assertEquals(HttpStatusCode.OK, ignoredResponse.status)
            assertTrue(ignoredResponse.bodyAsText().contains("ignored"))
        }

    @Test
    fun `should ingest Jellyfin ItemAdded webhook with 202 Accepted`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val jsonClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(testJson)
                    }
                }

            val jellyfinJson =
                """
                {
                  "NotificationType": "ItemAdded",
                  "ItemId": "item-9988",
                  "Name": "Dune 2",
                  "Year": 2024,
                  "Overview": "Epic sci-fi",
                  "VideoCodec": "HEVC",
                  "AudioCodec": "AAC",
                  "Resolution": "4K",
                  "ServerId": "jelly-server-1"
                }
                """.trimIndent()

            val response =
                jsonClient.post("/api/v1/webhook/jellyfin?token=$testToken") {
                    contentType(ContentType.Application.Json)
                    setBody(jellyfinJson)
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `should serve health, metrics, and schema documentation`() =
        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(EventRail(), ServerConfig())
            }

            val healthRes = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthRes.status)

            val metricsRes = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metricsRes.status)

            val sonarrSchemaRes = client.get("/schema/sonarr")
            assertEquals(HttpStatusCode.OK, sonarrSchemaRes.status)

            val radarrSchemaRes = client.get("/schema/radarr")
            assertEquals(HttpStatusCode.OK, radarrSchemaRes.status)

            val plexSchemaRes = client.get("/schema/plex")
            assertEquals(HttpStatusCode.OK, plexSchemaRes.status)

            val jellyfinSchemaRes = client.get("/schema/jellyfin")
            assertEquals(HttpStatusCode.OK, jellyfinSchemaRes.status)
        }

    @Test
    fun `should return 401 Unauthorized when auth token is missing or invalid`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val response =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Grab"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `should return 400 Bad Request on malformed JSON payload`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val response =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("not a valid json")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `should return 503 Service Unavailable when event rail is full for Servarr, Plex, and Jellyfin`() =
        testApplication {
            val eventRail = EventRail(capacity = 0)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val sonarrRes =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Grab", "downloadId": "h1"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, sonarrRes.status)

            val plexRes =
                client.post("/api/v1/webhook/plex") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event": "library.new", "Metadata": {"title": "Movie"}}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, plexRes.status)

            val jellyfinRes =
                client.post("/api/v1/webhook/jellyfin") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"NotificationType": "ItemAdded", "ItemId": "j1"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, jellyfinRes.status)
        }

    @Test
    fun `should handle malformed JSON and ignored events for Plex and Jellyfin`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val plexMalformed =
                client.post("/api/v1/webhook/plex") {
                    contentType(ContentType.Application.Json)
                    setBody("bad plex json")
                }
            assertEquals(HttpStatusCode.BadRequest, plexMalformed.status)

            val plexEmptyMultipart =
                client.submitFormWithBinaryData(
                    url = "/api/v1/webhook/plex",
                    formData =
                        formData {
                            append("thumb", byteArrayOf(1, 2, 3))
                        }
                )
            assertEquals(HttpStatusCode.BadRequest, plexEmptyMultipart.status)

            val jellyfinMalformed =
                client.post("/api/v1/webhook/jellyfin") {
                    contentType(ContentType.Application.Json)
                    setBody("bad jellyfin json")
                }
            assertEquals(HttpStatusCode.BadRequest, jellyfinMalformed.status)

            val jellyfinIgnored =
                client.post("/api/v1/webhook/jellyfin") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"NotificationType": "PlaybackStart"}""")
                }
            assertEquals(HttpStatusCode.OK, jellyfinIgnored.status)

            val servarrIgnored =
                client.post("/api/v1/webhook/servarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Rename"}""")
                }
            assertEquals(HttpStatusCode.OK, servarrIgnored.status)
        }
}
