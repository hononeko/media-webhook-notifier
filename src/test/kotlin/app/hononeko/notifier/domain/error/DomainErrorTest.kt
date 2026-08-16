package app.hononeko.notifier.domain.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainErrorTest {
    @Test
    fun `test all WebhookError variants`() {
        val unauthorized = DomainError.WebhookError.Unauthorized("Invalid secret token")
        assertEquals("Invalid secret token", unauthorized.reason)
        assertTrue(unauthorized is DomainError.WebhookError)
        assertTrue(unauthorized is DomainError)

        val invalidPayload = DomainError.WebhookError.InvalidPayload("JSON syntax error at line 5")
        assertEquals("JSON syntax error at line 5", invalidPayload.details)

        val unsupported = DomainError.WebhookError.UnsupportedEventType("UserRename")
        assertEquals("UserRename", unsupported.event)

        val missingHash = DomainError.WebhookError.MissingTorrentHash
        assertTrue(missingHash is DomainError.WebhookError)
    }

    @Test
    fun `test all TorrentClientError variants`() {
        val cause = RuntimeException("Connection refused")
        val connFailed = DomainError.TorrentClientError.ConnectionFailed("http://localhost:8080", cause)
        assertEquals("http://localhost:8080", connFailed.url)
        assertEquals(cause, connFailed.cause)

        val connFailedNoCause = DomainError.TorrentClientError.ConnectionFailed("http://localhost:8080")
        assertNull(connFailedNoCause.cause)

        val notFound = DomainError.TorrentClientError.TorrentNotFound("hash123")
        assertEquals("hash123", notFound.hash)

        val authFailed = DomainError.TorrentClientError.AuthenticationFailed("Invalid credentials")
        assertEquals("Invalid credentials", authFailed.reason)

        val invalidResp = DomainError.TorrentClientError.InvalidResponse("Empty payload")
        assertEquals("Empty payload", invalidResp.details)
    }

    @Test
    fun `test all NotificationError variants`() {
        val rateLimited = DomainError.NotificationError.RateLimited("telegram", 45)
        assertEquals("telegram", rateLimited.provider)
        assertEquals(45, rateLimited.retryAfterSeconds)

        val cause = RuntimeException("Socket closed")
        val deliveryFailed = DomainError.NotificationError.DeliveryFailed("telegram", "Bad Request", cause)
        assertEquals("telegram", deliveryFailed.provider)
        assertEquals("Bad Request", deliveryFailed.message)
        assertEquals(cause, deliveryFailed.cause)

        val timeout = DomainError.NotificationError.ConnectionTimeout("telegram", 5L)
        assertEquals("telegram", timeout.provider)
        assertEquals(5L, timeout.timeoutSeconds)

        val imgFetch = DomainError.NotificationError.ImageFetchFailed("telegram", "http://image.url", cause)
        assertEquals("telegram", imgFetch.provider)
        assertEquals("http://image.url", imgFetch.url)
        assertEquals(cause, imgFetch.cause)
    }
}
