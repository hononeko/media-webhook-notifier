package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationUrlParserTest {
    @Test
    fun `should parse standard telegram notification URL with at symbol`() {
        val url = "telegram://123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11@-1001234567890"
        val parsed = NotificationUrlParser.parse(url)

        assertEquals("telegram", parsed.provider)
        assertEquals("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", parsed.botToken)
        assertEquals("-1001234567890", parsed.chatId)
        assertNull(parsed.topicId)
        assertTrue(parsed.sendPhotos)
        assertEquals(30, parsed.rateLimitPerMinute)
        assertEquals(5L, parsed.timeoutSeconds)
    }

    @Test
    fun `should parse telegram notification URL with slash separator`() {
        val url = "telegram://123456:ABC-DEF/-1001234567890"
        val parsed = NotificationUrlParser.parse(url)

        assertEquals("telegram", parsed.provider)
        assertEquals("123456:ABC-DEF", parsed.botToken)
        assertEquals("-1001234567890", parsed.chatId)
        assertNull(parsed.topicId)
    }

    @Test
    fun `should parse query parameters for topic, photos, rate limit and timeout`() {
        val url = "telegram://bot-token@target-chat?topic=42&photos=false&rate_limit=60&timeout=12"
        val parsed = NotificationUrlParser.parse(url)

        assertEquals("telegram", parsed.provider)
        assertEquals("bot-token", parsed.botToken)
        assertEquals("target-chat", parsed.chatId)
        assertEquals(42L, parsed.topicId)
        assertEquals(false, parsed.sendPhotos)
        assertEquals(60, parsed.rateLimitPerMinute)
        assertEquals(12L, parsed.timeoutSeconds)
    }

    @Test
    fun `should handle alternate query parameter aliases`() {
        val url =
            "telegram://bot-token@target-chat" +
                "?thread=99&send_photos=false&rate_limit_per_minute=45&timeout_seconds=8"
        val parsed = NotificationUrlParser.parse(url)

        assertEquals(99L, parsed.topicId)
        assertEquals(false, parsed.sendPhotos)
        assertEquals(45, parsed.rateLimitPerMinute)
        assertEquals(8L, parsed.timeoutSeconds)
    }

    @Test
    fun `should handle empty or blank string gracefully`() {
        val parsed = NotificationUrlParser.parse("")
        assertEquals("telegram", parsed.provider)
        assertEquals("", parsed.botToken)
        assertEquals("", parsed.chatId)
        assertNull(parsed.topicId)
    }
}
