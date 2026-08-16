package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class JellyfinAndServarrWebhookProviderTest {
    private val jellyfinProvider = JellyfinWebhookProvider()
    private val sonarrProvider = SonarrWebhookProvider()
    private val radarrProvider = RadarrWebhookProvider()
    private val servarrProvider = ServarrWebhookProvider()

    @Test
    fun `test JellyfinWebhookProvider keys and schema`() {
        assertEquals(setOf("jellyfin", "emby"), jellyfinProvider.providerKeys)
        assertNotNull(jellyfinProvider.getSchemaJson())
    }

    @Test
    fun `test Jellyfin parsing valid ItemAdded, invalid JSON, and unsupported event`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook/jellyfin") {
                    processResult = jellyfinProvider.process(call, "MyJellyfin")
                    call.respondText("OK")
                }
            }

            // 1. Valid ItemAdded
            val validPayload =
                """
                {
                    "NotificationType": "ItemAdded",
                    "ItemType": "Movie",
                    "ItemId": "movie-123",
                    "Name": "Interstellar",
                    "Year": 2014,
                    "Overview": "A team of explorers...",
                    "MediaStreams": [
                        { "Type": "Video", "Codec": "hevc", "Width": 3840, "Height": 2160 },
                        { "Type": "Audio", "Codec": "dts", "Channels": 6 }
                    ],
                    "Resolution": "4k",
                    "VideoCodec": "hevc",
                    "AudioCodec": "dts",
                    "Server": {
                        "Id": "server-1",
                        "Name": "MediaHome"
                    }
                }
                """.trimIndent()

            client.post("/webhook/jellyfin") {
                contentType(ContentType.Application.Json)
                setBody(validPayload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val payload = (processResult as WebhookProcessResult.Queued).payload
            assertIs<MediaPayload.JellyfinItemAdded>(payload)
            assertEquals("Interstellar", payload.title)
            assertEquals("4k", payload.resolution)
            assertEquals("hevc", payload.videoCodec)
            assertEquals("dts", payload.audioCodec)
            assertEquals("MyJellyfin", payload.instanceName)

            // 2. Unsupported event
            client.post("/webhook/jellyfin") {
                contentType(ContentType.Application.Json)
                setBody("""{"NotificationType": "PlaybackStart"}""")
            }
            assertIs<WebhookProcessResult.Ignored>(processResult)

            // 3. Invalid JSON
            client.post("/webhook/jellyfin") {
                contentType(ContentType.Application.Json)
                setBody("invalid-json")
            }
            assertIs<WebhookProcessResult.InvalidPayload>(processResult)
        }

    @Test
    fun `test Servarr providers keys and schema`() {
        assertEquals(setOf("sonarr"), sonarrProvider.providerKeys)
        assertNotNull(sonarrProvider.getSchemaJson())

        assertEquals(setOf("radarr"), radarrProvider.providerKeys)
        assertNotNull(radarrProvider.getSchemaJson())

        assertEquals(
            setOf("servarr", "arr", "lidarr", "readarr", "bazarr", "prowlarr", "whisparr"),
            servarrProvider.providerKeys
        )
        assertNotNull(servarrProvider.getSchemaJson())
    }

    @Test
    fun `test Servarr parsing Movie download with full mediaInfo and poster URL`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook/radarr") {
                    processResult = radarrProvider.process(call, null)
                    call.respondText("OK")
                }
            }

            val moviePayload =
                """
                {
                    "eventType": "Download",
                    "instanceName": "Radarr-4K",
                    "applicationUrl": "http://radarr.lan:7878",
                    "movie": {
                        "id": 1,
                        "title": "Oppenheimer",
                        "year": 2023,
                        "images": [
                            { "coverType": "poster", "remoteUrl": "https://image.tmdb.org/poster.jpg" }
                        ],
                        "movieFile": {
                            "size": 35000000000,
                            "mediaInfo": {
                                "videoCodec": "hevc",
                                "audioCodec": "truehd",
                                "resolution": "3840x2160"
                            }
                        }
                    },
                    "isUpgrade": true
                }
                """.trimIndent()

            client.post("/webhook/radarr") {
                contentType(ContentType.Application.Json)
                setBody(moviePayload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val payload = (processResult as WebhookProcessResult.Queued).payload
            assertIs<MediaPayload.ArrDownload>(payload)
            assertEquals("Oppenheimer", payload.title)
            assertEquals(true, payload.isUpgrade)
            assertEquals("hevc", payload.videoCodec)
            assertEquals("truehd", payload.audioCodec)
            assertEquals("3840x2160", payload.resolution)
            assertEquals("https://image.tmdb.org/poster.jpg", payload.posterUrl)
            assertEquals("Radarr-4K", payload.instanceName)
            assertEquals("http://radarr.lan:7878", payload.webUrl)
        }

    @Test
    fun `test Servarr parsing HealthRestored and ManualInteraction events`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook/servarr") {
                    processResult = servarrProvider.process(call, "Master-Servarr")
                    call.respondText("OK")
                }
            }

            // Health restored
            val healthRestoredPayload =
                """
                {
                    "eventType": "HealthRestored",
                    "message": "All indexers operational",
                    "wikiUrl": "https://wiki.servarr.com"
                }
                """.trimIndent()

            client.post("/webhook/servarr") {
                contentType(ContentType.Application.Json)
                setBody(healthRestoredPayload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val health = (processResult as WebhookProcessResult.Queued).payload
            assertIs<MediaPayload.ServarrHealth>(health)
            assertEquals(EventType.HEALTH_RESTORED, health.eventType)
            assertEquals("ok", health.level)
            assertEquals("All indexers operational", health.message)

            // Manual Interaction
            val manualPayload =
                """
                {
                    "eventType": "ManualInteractionRequired",
                    "series": { "title": "Severance" },
                    "episodes": [{ "episodeNumber": 1, "seasonNumber": 2 }],
                    "release": {
                        "releaseTitle": "Severance.S02E01.1080p",
                        "size": 2000000000,
                        "indexer": "TL",
                        "quality": "1080p"
                    },
                    "downloadClient": "qBittorrent",
                    "downloadId": "hash999",
                    "message": "Unknown series folder"
                }
                """.trimIndent()

            client.post("/webhook/servarr") {
                contentType(ContentType.Application.Json)
                setBody(manualPayload)
            }

            assertIs<WebhookProcessResult.Queued>(processResult)
            val manual = (processResult as WebhookProcessResult.Queued).payload
            assertIs<MediaPayload.ServarrManualInteraction>(manual)
            assertEquals("Severance", manual.seriesOrMovieTitle)
            assertEquals(2, manual.seasonNumber)
            assertEquals(listOf(1), manual.episodeNumbers)
            assertEquals("Unknown series folder", manual.reason)
            assertEquals("qBittorrent", manual.downloadClient)
        }
}
