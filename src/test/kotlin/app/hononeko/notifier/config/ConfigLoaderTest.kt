package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @Test
    fun `should load default configuration from application yaml`() {
        val config = ConfigLoader.load()
        assertNotNull(config)

        // Server
        assertEquals(8080, config.server.port)
        assertEquals("", config.server.authToken)

        // Media Server
        assertEquals("plex", config.mediaServer.type)
        assertEquals("", config.mediaServer.baseUrl)
        assertEquals("", config.mediaServer.plexPublicUrl)
        assertEquals("", config.mediaServer.jellyfinPublicUrl)

        // qBittorrent & Tracking
        assertEquals("http://localhost:8080", config.qbittorrent.url)
        assertEquals("", config.qbittorrent.username)
        assertEquals("", config.qbittorrent.password)
        assertEquals(5, config.qbittorrent.pollIntervalSeconds)
        assertEquals(30, config.qbittorrent.maxPollingMinutes)
        assertEquals(15, config.qbittorrent.stalledTimeoutMinutes)
        assertEquals(6, config.qbittorrent.missingGraceAttempts)
        assertEquals(5, config.qbittorrent.debounceSeconds)
        assertEquals("", config.qbittorrent.webuiPublicUrl)

        // Telegram
        assertTrue(config.notifications.telegram.enabled)
        assertEquals("", config.notifications.telegram.botToken)
        assertEquals("", config.notifications.telegram.chatId)
        assertNull(config.notifications.telegram.topicId)
        assertEquals(30, config.notifications.telegram.rateLimitPerMinute)
        assertEquals(5, config.notifications.telegram.timeoutSeconds)
        assertTrue(config.notifications.telegram.sendPhotos)

        // Discord
        assertEquals(false, config.notifications.discord.enabled)
        assertEquals("", config.notifications.discord.webhookUrl)
    }
}
