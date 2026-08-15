package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.config.ServerConfig
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import arrow.core.Either
import arrow.core.right
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
import kotlinx.coroutines.cancel
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

            val healthzRes = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, healthzRes.status)

            val livezRes = client.get("/livez")
            assertEquals(HttpStatusCode.OK, livezRes.status)

            val readyzRes = client.get("/readyz")
            assertEquals(HttpStatusCode.OK, readyzRes.status)

            val startupzRes = client.get("/startupz")
            assertEquals(HttpStatusCode.OK, startupzRes.status)

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

    @Test
    fun `should ingest webhooks with path-based token and instance query param`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = testToken))
            }

            val pathSonarr =
                client.post("/api/v1/webhook/$testToken/sonarr?instance=sonarr-anime") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Grab", "downloadId": "hash-path-1"}""")
                }
            assertEquals(HttpStatusCode.Accepted, pathSonarr.status)

            val pathRadarr =
                client.post("/api/v1/webhook/$testToken/radarr?instance=radarr-4k") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Grab", "downloadId": "hash-path-2"}""")
                }
            assertEquals(HttpStatusCode.Accepted, pathRadarr.status)

            val pathPlex =
                client.post("/api/v1/webhook/$testToken/plex") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event": "library.new", "Metadata": {"title": "Movie"}}""")
                }
            assertEquals(HttpStatusCode.Accepted, pathPlex.status)

            val pathJellyfin =
                client.post("/api/v1/webhook/$testToken/jellyfin") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"NotificationType": "ItemAdded", "ItemId": "j-path-1"}""")
                }
            assertEquals(HttpStatusCode.Accepted, pathJellyfin.status)
        }

    @Test
    fun `should return 429 Too Many Requests with Retry-After when rate limit is exceeded`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(authToken = "", rateLimitPerMinute = 1)
                )
            }

            val firstReq =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Test"}""")
                }
            assertEquals(HttpStatusCode.OK, firstReq.status)

            val secondReq =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Test"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, secondReq.status)
            assertEquals("60", secondReq.headers[io.ktor.http.HttpHeaders.RetryAfter])
            val body = secondReq.bodyAsText()
            assertTrue(body.contains("rate_limited"))
        }

    @Test
    fun `should return 404 for unsupported webhook provider`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val response =
                client.post("/api/v1/webhook/unknown-service") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"test": true}""")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Unsupported webhook provider 'unknown-service'"))

            val pathTokenResponse =
                client.post("/api/v1/webhook/secret-token/unknown-service") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"test": true}""")
                }
            assertEquals(HttpStatusCode.NotFound, pathTokenResponse.status)
            assertTrue(pathTokenResponse.bodyAsText().contains("Unsupported webhook provider 'unknown-service'"))
        }

    @Test
    fun `should return 400 Bad Request when calling webhook root without provider parameter`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val response =
                client.post("/api/v1/webhook") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"test": true}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Missing webhook provider in URL path"))
        }

    @Test
    fun `should support provider aliases and return 404 for unknown schema`() =
        testApplication {
            val eventRail = EventRail(capacity = 50)
            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            val embyRes =
                client.post("/api/v1/webhook/emby") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"NotificationType": "ItemAdded", "ItemId": "e1"}""")
                }
            assertEquals(HttpStatusCode.Accepted, embyRes.status)

            val arrRes =
                client.post("/api/v1/webhook/arr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Test"}""")
                }
            assertEquals(HttpStatusCode.OK, arrRes.status)

            val unknownSchemaRes = client.get("/schema/unknown-schema")
            assertEquals(HttpStatusCode.NotFound, unknownSchemaRes.status)
        }

    @Test
    fun `should resolve instanceName with query param precedence over payload and defaults`() =
        testApplication {
            val testScope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
                )
            val eventRail = EventRail(capacity = 50)
            val capturedPayloads = mutableListOf<MediaPayload>()
            val capturingService =
                object : IngestWebhookUseCase {
                    override suspend fun execute(
                        payload: MediaPayload
                    ): Either<app.hononeko.notifier.domain.error.DomainError, Unit> {
                        capturedPayloads.add(payload)
                        return Unit.right()
                    }
                }
            val job = eventRail.start(testScope, capturingService)

            application {
                install(ServerContentNegotiation) {
                    json(testJson)
                }
                configureWebhookRouting(eventRail, ServerConfig(authToken = ""))
            }

            // 1. Query parameter overrides payload instanceName
            val queryOverrideRes =
                client.post("/api/v1/webhook/sonarr?instance=Sonarr-Anime-Override") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "eventType": "Grab",
                          "instanceName": "Sonarr-Payload-Default",
                          "series": { "id": 1, "title": "Frieren" },
                          "release": { "quality": "1080p", "size": 1000 },
                          "downloadId": "hash-frieren"
                        }
                        """.trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.Accepted, queryOverrideRes.status)

            // 2. Payload instanceName used when query param omitted
            val payloadRes =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "eventType": "Grab",
                          "instanceName": "Sonarr-Payload-Default",
                          "series": { "id": 1, "title": "Frieren" },
                          "release": { "quality": "1080p", "size": 1000 },
                          "downloadId": "hash-frieren-2"
                        }
                        """.trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.Accepted, payloadRes.status)

            // 3. Fallback to default display name when neither is provided
            val defaultRes =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "eventType": "Grab",
                          "series": { "id": 1, "title": "Frieren" },
                          "release": { "quality": "1080p", "size": 1000 },
                          "downloadId": "hash-frieren-3"
                        }
                        """.trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.Accepted, defaultRes.status)

            // Wait for coroutine channel consumption
            kotlinx.coroutines.delay(50)

            assertEquals(3, capturedPayloads.size)
            assertEquals("Sonarr-Anime-Override", capturedPayloads[0].instanceName)
            assertEquals("Sonarr-Payload-Default", capturedPayloads[1].instanceName)
            assertEquals("Sonarr", capturedPayloads[2].instanceName)

            job.cancel()
            testScope.cancel()
        }

    @Test
    fun `should ingest Servarr Health and ManualInteractionRequired webhooks with 202 Accepted`() =
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

            val healthPayload =
                """
                {
                  "eventType": "Health",
                  "level": "warning",
                  "message": "All indexers are unavailable",
                  "type": "IndexersUnavailable",
                  "wikiUrl": "https://wiki.servarr.com/indexers",
                  "instanceName": "Sonarr-TV"
                }
                """.trimIndent()

            val healthResponse =
                jsonClient.post("/api/v1/webhook/sonarr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(healthPayload)
                }
            assertEquals(HttpStatusCode.Accepted, healthResponse.status)

            val healthRestoredPayload =
                """
                {
                  "eventType": "HealthRestored",
                  "level": "ok",
                  "message": "Indexer connection restored",
                  "instanceName": "Sonarr-TV"
                }
                """.trimIndent()

            val healthRestoredResponse =
                jsonClient.post("/api/v1/webhook/sonarr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(healthRestoredPayload)
                }
            assertEquals(HttpStatusCode.Accepted, healthRestoredResponse.status)

            val manualPayload =
                """
                {
                  "eventType": "ManualInteractionRequired",
                  "movie": {
                    "id": 1,
                    "title": "Dune: Part Two"
                  },
                  "release": {
                    "quality": "2160p",
                    "size": 15000000000,
                    "releaseTitle": "Dune.2.2024.UHD"
                  },
                  "reason": "Sample file detected",
                  "downloadClient": "qBittorrent",
                  "applicationUrl": "http://radarr:7878/activity/queue"
                }
                """.trimIndent()

            val manualResponse =
                jsonClient.post("/api/v1/webhook/radarr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(manualPayload)
                }
            assertEquals(HttpStatusCode.Accepted, manualResponse.status)
        }

    @Test
    fun `should ingest Seerr and Overseerr webhooks with 202 Accepted and handle test notification`() =
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

            val requestPendingPayload =
                """
                {
                  "notification_type": "MEDIA_PENDING",
                  "subject": "Dune: Part Two (2024)",
                  "message": "New request submitted by Admin",
                  "media": {
                    "media_type": "movie",
                    "tmdbId": "693134"
                  },
                  "request": {
                    "request_id": 1,
                    "requestedBy_username": "Admin",
                    "is4k": true
                  },
                  "application_url": "https://seerr.example.com"
                }
                """.trimIndent()

            val seerrResponse =
                jsonClient.post("/api/v1/webhook/seerr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestPendingPayload)
                }
            assertEquals(HttpStatusCode.Accepted, seerrResponse.status)

            val overseerrResponse =
                jsonClient.post("/api/v1/webhook/overseerr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestPendingPayload)
                }
            assertEquals(HttpStatusCode.Accepted, overseerrResponse.status)

            val testPayload =
                """
                {
                  "notification_type": "TEST_NOTIFICATION",
                  "subject": "Test Notification",
                  "message": "This is a test notification from Overseerr"
                }
                """.trimIndent()

            val testResponse =
                jsonClient.post("/api/v1/webhook/jellyseerr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(testPayload)
                }
            assertEquals(HttpStatusCode.OK, testResponse.status)

            val fullTemplatePayload =
                """
                {
                    "notification_type": "MEDIA_PENDING",
                    "event": "New Media Pending Approval",
                    "subject": "Dune: Part Two",
                    "message": "A new request has been submitted by vehkiya.",
                    "image": "https://image.tmdb.org/t/p/w600/poster.jpg",
                    "media": {
                        "media_type": "movie",
                        "imdbId": "tt15239678",
                        "tmdbId": "693134",
                        "tvdbId": "12345",
                        "jellyfinMediaId": "jf-uuid-123",
                        "status": "PENDING_APPROVAL",
                        "status4k": "UNKNOWN"
                    },
                    "request": {
                        "request_id": "1",
                        "requestedBy_email": "user@example.com",
                        "requestedBy_username": "vehkiya",
                        "requestedBy_avatar": "https://example.com/avatar.png",
                        "requestedBy_jellyfinUserId": "jf-user-1",
                        "requestedBy_settings_discordIds": "123456789",
                        "requestedBy_settings_telegramChatId": "987654321"
                    },
                    "issue": {
                        "issue_id": "",
                        "issue_type": "",
                        "issue_status": "",
                        "reportedBy_email": "",
                        "reportedBy_username": "",
                        "reportedBy_avatar": "",
                        "reportedBy_settings_discordIds": "",
                        "reportedBy_settings_telegramChatId": ""
                    },
                    "comment": {
                        "comment_message": "",
                        "commentedBy_email": "",
                        "commentedBy_username": "",
                        "commentedBy_avatar": "",
                        "commentedBy_settings_discordIds": "",
                        "commentedBy_settings_telegramChatId": ""
                    },
                    "extra": []
                }
                """.trimIndent()

            val fullTemplateResponse =
                jsonClient.post("/api/v1/webhook/seerr") {
                    header("Authorization", "Bearer $testToken")
                    contentType(ContentType.Application.Json)
                    setBody(fullTemplatePayload)
                }
            assertEquals(HttpStatusCode.Accepted, fullTemplateResponse.status)
        }
}
