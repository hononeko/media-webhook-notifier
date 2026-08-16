package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexWebhookProviderTest {
    private val provider = PlexWebhookProvider()

    @Test
    fun `should support plex provider key`() {
        assertEquals(setOf("plex"), provider.providerKeys)
    }

    @Test
    fun `should load non-null schema json`() {
        val schema = provider.getSchemaJson()
        assertNotNull(schema)
        assertTrue(schema.contains("Plex Webhook Payload"))
    }

    @Test
    fun `should parse Plex library new webhook from JSON payload`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Kerrlab-Plex")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "event": "library.new",
                    "user": true,
                    "owner": true,
                    "Server": {
                        "title": "Kerrlab Plex",
                        "uuid": "uuid-99"
                    },
                    "Metadata": {
                        "ratingKey": "1001",
                        "title": "Black Widow",
                        "year": 2021,
                        "summary": "Natasha Romanoff...",
                        "rating": 7.9,
                        "duration": 8040000,
                        "thumb": "https://example.com/poster.jpg",
                        "Media": [
                            {
                                "videoCodec": "hevc",
                                "audioCodec": "truehd",
                                "videoResolution": "4k"
                            }
                        ]
                    }
                }
                """.trimIndent()

            val response =
                client.post("/webhook") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            assertEquals(io.ktor.http.HttpStatusCode.OK, response.status)
            assertIs<WebhookProcessResult.Queued>(processResult)
            val queued = processResult as WebhookProcessResult.Queued
            assertEquals("library.new", queued.eventType)

            val plex = queued.payload as MediaPayload.PlexLibraryNew
            assertEquals(AppSource.PLEX, plex.source)
            assertEquals(EventType.MEDIA_AVAILABLE, plex.eventType)
            assertEquals("Black Widow", plex.title)
            assertEquals(2021, plex.year)
            assertEquals("Natasha Romanoff...", plex.summary)
            assertEquals(7.9, plex.rating)
            assertEquals(8040L, plex.durationSeconds)
            assertEquals("hevc", plex.videoCodec)
            assertEquals("truehd", plex.audioCodec)
            assertEquals("4k", plex.resolution)
            assertEquals("https://example.com/poster.jpg", plex.posterUrl)
            assertNull(plex.artworkBytes)
            assertEquals("Kerrlab-Plex", plex.instanceName)
        }

    @Test
    fun `should sanitize relative thumb URL to null posterUrl`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, null)
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "event": "library.new",
                    "Metadata": {
                        "ratingKey": "1001",
                        "title": "Severance",
                        "thumb": "/library/metadata/1001/thumb/123456"
                    }
                }
                """.trimIndent()

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val plex = (processResult as WebhookProcessResult.Queued).payload as MediaPayload.PlexLibraryNew
            assertNull(plex.posterUrl)
            assertEquals("Plex", plex.instanceName)
        }

    @Test
    fun `should parse multipart with file thumb part`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Plex")
                    call.respondText("OK")
                }
            }

            val jsonPayload =
                """
                {
                    "event": "library.new",
                    "Metadata": {
                        "ratingKey": "1002",
                        "title": "Movie with Poster"
                    }
                }
                """.trimIndent()

            val thumbBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

            client.submitFormWithBinaryData(
                url = "/webhook",
                formData =
                    formData {
                        append("payload", jsonPayload)
                        append(
                            "thumb",
                            thumbBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"thumb.jpg\"")
                            }
                        )
                    }
            )

            assertIs<WebhookProcessResult.Queued>(processResult)
            val plex = (processResult as WebhookProcessResult.Queued).payload as MediaPayload.PlexLibraryNew
            assertEquals("Movie with Poster", plex.title)
            assertNotNull(plex.artworkBytes)
            assertContentEquals(thumbBytes, plex.artworkBytes)
        }

    @Test
    fun `should ignore unhandled Plex events`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Plex")
                    call.respondText("OK")
                }
            }

            val payload = """{"event": "media.pause"}"""

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertIs<WebhookProcessResult.Ignored>(processResult)
            val ignored = processResult as WebhookProcessResult.Ignored
            assertEquals("media.pause", ignored.eventType)
        }

    @Test
    fun `should return InvalidPayload on malformed JSON`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Plex")
                    call.respondText("OK")
                }
            }

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody("{invalid-json}")
            }

            assertIs<WebhookProcessResult.InvalidPayload>(processResult)
        }

    @Test
    fun `should parse Plex season added webhook with season metadata and parent posters`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Kerrlab-Plex")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "event": "library.new",
                    "Server": {
                        "title": "Kerrlab Plex",
                        "uuid": "uuid-99"
                    },
                    "Metadata": {
                        "ratingKey": "2001",
                        "title": "Season 3",
                        "parentTitle": "Futurama",
                        "type": "season",
                        "index": 3,
                        "parentYear": 1999,
                        "summary": "Season 3 adventures of Planet Express",
                        "rating": 8.8,
                        "thumb": "https://example.com/season3_thumb.jpg",
                        "parentThumb": "https://example.com/series_thumb.jpg"
                    }
                }
                """.trimIndent()

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val plex = (processResult as WebhookProcessResult.Queued).payload as MediaPayload.PlexLibraryNew
            assertEquals("Season 3", plex.title)
            assertEquals("season", plex.mediaType)
            assertEquals("Futurama", plex.parentTitle)
            assertNull(plex.grandParentTitle)
            assertEquals(3, plex.seasonNumber)
            assertNull(plex.episodeNumber)
            assertEquals(1999, plex.year)
            assertEquals("https://example.com/season3_thumb.jpg", plex.posterUrl)
            assertEquals("https://example.com/series_thumb.jpg", plex.parentPosterUrl)
        }

    @Test
    fun `should parse Plex episode added webhook with episode metadata and grandparent titles`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Kerrlab-Plex")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "event": "library.new",
                    "Server": {
                        "title": "Kerrlab Plex",
                        "uuid": "uuid-99"
                    },
                    "Metadata": {
                        "ratingKey": "3001",
                        "title": "Roswell That Ends Well",
                        "parentTitle": "Season 3",
                        "grandparentTitle": "Futurama",
                        "type": "episode",
                        "index": 1,
                        "parentIndex": 3,
                        "duration": 1320000,
                        "thumb": "https://example.com/ep_thumb.jpg",
                        "parentThumb": "https://example.com/season_thumb.jpg",
                        "grandparentThumb": "https://example.com/series_thumb.jpg"
                    }
                }
                """.trimIndent()

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val plex = (processResult as WebhookProcessResult.Queued).payload as MediaPayload.PlexLibraryNew
            assertEquals("Roswell That Ends Well", plex.title)
            assertEquals("episode", plex.mediaType)
            assertEquals("Season 3", plex.parentTitle)
            assertEquals("Futurama", plex.grandParentTitle)
            assertEquals(3, plex.seasonNumber)
            assertEquals(1, plex.episodeNumber)
            assertEquals(1320L, plex.durationSeconds)
            assertEquals("https://example.com/ep_thumb.jpg", plex.posterUrl)
            assertEquals("https://example.com/season_thumb.jpg", plex.parentPosterUrl)
            assertEquals("https://example.com/series_thumb.jpg", plex.grandparentPosterUrl)
        }
}
