package app.hononeko.notifier.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addMapSource
import com.sksamuel.hoplite.addResourceSource
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

    @Test
    fun `should override configuration with environment variables and custom property sources`() {
        val config =
            ConfigLoaderBuilder
                .default()
                .addMapSource(
                    mapOf(
                        "server.authToken" to "env-supplied-token",
                        "server.port" to "9090",
                        "notifications.telegram.botToken" to "tg-bot-12345",
                        "notifications.telegram.chatId" to "-100123456"
                    )
                ).addResourceSource("/application.yaml", optional = true)
                .build()
                .loadConfigOrThrow<AppConfig>()

        assertEquals("env-supplied-token", config.server.authToken)
        assertEquals(9090, config.server.port)
        assertEquals("tg-bot-12345", config.notifications.telegram.botToken)
        assertEquals("-100123456", config.notifications.telegram.chatId)
    }
}
