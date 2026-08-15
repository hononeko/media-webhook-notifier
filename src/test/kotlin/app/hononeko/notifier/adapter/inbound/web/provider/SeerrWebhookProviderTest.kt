package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeerrWebhookProviderTest {
    private val provider = SeerrWebhookProvider()

    @Test
    fun `should support seerr, overseerr, and jellyseerr keys`() {
        assertEquals(setOf("seerr", "overseerr", "jellyseerr"), provider.providerKeys)
    }

    @Test
    fun `should load non-null schema json`() {
        val schema = provider.getSchemaJson()
        assertNotNull(schema)
        assertTrue(schema.contains("Seerr (Overseerr / Jellyseerr) Webhook Payload"))
    }

    @Test
    fun `should parse MEDIA_PENDING with full media and request metadata`() =
        testApplication {
            var processResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    processResult = provider.process(call, "Seerr-Custom")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "notification_type": "MEDIA_PENDING",
                    "event": "New Request",
                    "subject": "Dune: Part Two (2024)",
                    "message": "Requested by vehkiya",
                    "image": "https://image.tmdb.org/t/p/w500/poster.jpg",
                    "media": {
                        "media_type": "movie",
                        "imdbId": "tt15239678",
                        "tmdbId": "693134",
                        "tvdbId": "12345",
                        "jellyfinMediaId": "jf-uuid-1",
                        "status": "PENDING_APPROVAL",
                        "status4k": "UNKNOWN"
                    },
                    "request": {
                        "request_id": "42",
                        "requestedBy_email": "vehkiya@example.com",
                        "requestedBy_username": "vehkiya",
                        "requestedBy_avatar": "https://example.com/avatar.png",
                        "is4k": "true"
                    },
                    "application_url": "https://seerr.example.com"
                }
                """.trimIndent()

            client.post("/webhook") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertNotNull(processResult)
            assertTrue(processResult is WebhookProcessResult.Queued)
            val queued = processResult as WebhookProcessResult.Queued
            val event = queued.payload as MediaPayload.SeerrEvent

            assertEquals(AppSource.SEERR, event.source)
            assertEquals(EventType.REQUEST_PENDING, event.eventType)
            assertEquals("MEDIA_PENDING", event.notificationType)
            assertEquals("Dune: Part Two (2024)", event.subject)
            assertEquals("movie", event.mediaType)
            assertEquals("tt15239678", event.imdbId)
            assertEquals("693134", event.tmdbId)
            assertEquals("12345", event.tvdbId)
            assertEquals("jf-uuid-1", event.jellyfinMediaId)
            assertEquals("vehkiya", event.requestedByUsername)
            assertEquals("vehkiya@example.com", event.requestedByEmail)
            assertTrue(event.is4k)
            assertEquals("https://seerr.example.com", event.webUrl)
            assertEquals("Seerr-Custom", event.instanceName)
        }

    @Test
    fun `should parse all Seerr request and issue lifecycle events correctly`() =
        testApplication {
            var lastResult: WebhookProcessResult? = null

            routing {
                post("/webhook") {
                    lastResult = provider.process(call, null)
                    call.respondText("OK")
                }
            }

            val eventMap =
                listOf(
                    "MEDIA_APPROVED" to EventType.REQUEST_APPROVED,
                    "MEDIA_AUTO_APPROVED" to EventType.REQUEST_AUTO_APPROVED,
                    "MEDIA_AVAILABLE" to EventType.REQUEST_AVAILABLE,
                    "MEDIA_DECLINED" to EventType.REQUEST_DECLINED,
                    "MEDIA_FAILED" to EventType.REQUEST_FAILED,
                    "ISSUE_CREATED" to EventType.ISSUE_CREATED,
                    "ISSUE_COMMENT" to EventType.ISSUE_COMMENT,
                    "ISSUE_RESOLVED" to EventType.ISSUE_RESOLVED,
                    "ISSUE_REOPENED" to EventType.ISSUE_REOPENED
                )

            for ((notifType, expectedEventType) in eventMap) {
                val payload =
                    """
                    {
                        "notification_type": "$notifType",
                        "subject": "Test Event $notifType",
                        "issue": {
                            "issue_id": 10,
                            "issue_type": "Video",
                            "issue_status": "OPEN"
                        },
                        "comment": {
                            "comment_message": "Fixed in new release"
                        }
                    }
                    """.trimIndent()

                client.post("/webhook") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                assertNotNull(lastResult)
                assertTrue(lastResult is WebhookProcessResult.Queued)
                val queued = lastResult as WebhookProcessResult.Queued
                val event = queued.payload as MediaPayload.SeerrEvent
                assertEquals(expectedEventType, event.eventType)
                assertEquals("Seerr", event.instanceName)
            }
        }

    @Test
    fun `should handle TEST_NOTIFICATION with TestOk result`() =
        testApplication {
            var result: WebhookProcessResult? = null

            routing {
                post("/webhook-test") {
                    result = provider.process(call, "Overseerr-4K")
                    call.respondText("OK")
                }
            }

            val payload = """{ "notification_type": "TEST_NOTIFICATION" }"""

            client.post("/webhook-test") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertNotNull(result)
            assertTrue(result is WebhookProcessResult.TestOk)
            assertEquals("Overseerr-4K", (result as WebhookProcessResult.TestOk).instanceName)
        }

    @Test
    fun `should ignore unknown notification types gracefully`() =
        testApplication {
            var result: WebhookProcessResult? = null

            routing {
                post("/webhook-unknown") {
                    result = provider.process(call, null)
                    call.respondText("OK")
                }
            }

            val payload = """{ "notification_type": "SOME_UNKNOWN_ACTION" }"""

            client.post("/webhook-unknown") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertNotNull(result)
            assertTrue(result is WebhookProcessResult.Ignored)
        }

    @Test
    fun `should return InvalidPayload on malformed json`() =
        testApplication {
            var result: WebhookProcessResult? = null

            routing {
                post("/webhook-malformed") {
                    result = provider.process(call, null)
                    call.respondText("OK")
                }
            }

            client.post("/webhook-malformed") {
                contentType(ContentType.Application.Json)
                setBody("{ not valid json")
            }

            assertNotNull(result)
            assertTrue(result is WebhookProcessResult.InvalidPayload)
        }

    @Test
    fun `should handle fallback to event field when notification_type is absent`() =
        testApplication {
            var result: WebhookProcessResult? = null

            routing {
                post("/webhook-event-field") {
                    result = provider.process(call, "   ")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "event": "MEDIA_APPROVED",
                    "subject": "",
                    "message": "  ",
                    "image": "  ",
                    "url": "   ",
                    "application_url": "https://overseerr.local",
                    "media": {
                        "status4k": "AVAILABLE"
                    },
                    "extra": [
                        { "name": "Requested By", "value": "Charlie" },
                        { "name": null, "value": "ignored" },
                        { "name": "BlankVal", "value": null }
                    ]
                }
                """.trimIndent()

            client.post("/webhook-event-field") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertNotNull(result)
            assertTrue(result is WebhookProcessResult.Queued)
            val event = (result as WebhookProcessResult.Queued).payload as MediaPayload.SeerrEvent
            assertEquals(EventType.REQUEST_APPROVED, event.eventType)
            assertEquals("Media Request", event.subject)
            assertEquals(null, event.message)
            assertEquals(null, event.image)
            assertEquals("https://overseerr.local", event.webUrl)
            assertEquals("Seerr", event.instanceName)
            assertEquals("Charlie", event.requestedByUsername)
            assertTrue(event.is4k)
            assertEquals(1, event.extra.size)
        }

    @Test
    fun `should parse is4k as boolean false and status4k UNKNOWN correctly`() =
        testApplication {
            var result: WebhookProcessResult? = null

            routing {
                post("/webhook-not-4k") {
                    result = provider.process(call, "Overseerr")
                    call.respondText("OK")
                }
            }

            val payload =
                """
                {
                    "notification_type": "MEDIA_PENDING",
                    "request": {
                        "is4k": false
                    },
                    "media": {
                        "status4k": "UNKNOWN"
                    }
                }
                """.trimIndent()

            client.post("/webhook-not-4k") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertNotNull(result)
            assertTrue(result is WebhookProcessResult.Queued)
            val event = (result as WebhookProcessResult.Queued).payload as MediaPayload.SeerrEvent
            assertEquals(false, event.is4k)
        }
}
