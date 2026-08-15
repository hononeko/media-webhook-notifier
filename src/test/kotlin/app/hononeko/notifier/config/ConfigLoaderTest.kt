package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @Test
    fun `should load default configuration when environment is empty`() {
        val config = ConfigLoader.load(emptyMap())
        assertNotNull(config)

        // Server
        assertEquals(8080, config.server.port)
        assertEquals("", config.server.authToken)
        assertEquals(120, config.server.rateLimitPerMinute)

        // Media Server
        assertEquals("plex", config.mediaServer.type)
        assertEquals("", config.mediaServer.url)
        assertEquals("", config.mediaServer.publicUrl)

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

        // Notifications
        assertEquals("telegram", config.notifications.provider)
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
        val env =
            mapOf(
                "SERVER_AUTH_TOKEN" to "env-supplied-token",
                "SERVER_PORT" to "9090",
                "TELEGRAM_BOT_TOKEN" to "tg-bot-12345",
                "TELEGRAM_CHAT_ID" to "-100123456",
                "TELEGRAM_TOPIC_ID" to "42",
                "QBITTORRENT_URL" to "http://qbittorrent:8080",
                "MEDIA_SERVER_TYPE" to "jellyfin",
                "MEDIA_SERVER_URL" to "http://jellyfin:8096",
                "MEDIA_SERVER_PUBLIC_URL" to "https://jellyfin.example.com",
                "NOTIFICATION_PROVIDER" to "discord",
                "DISCORD_WEBHOOK_URL" to "https://discord.com/api/webhooks/123/xyz"
            )

        val config = ConfigLoader.load(env)

        assertEquals("env-supplied-token", config.server.authToken)
        assertEquals(9090, config.server.port)
        assertEquals("tg-bot-12345", config.notifications.telegram.botToken)
        assertEquals("-100123456", config.notifications.telegram.chatId)
        assertEquals(42L, config.notifications.telegram.topicId)
        assertEquals("http://qbittorrent:8080", config.qbittorrent.url)
        assertEquals("jellyfin", config.mediaServer.type)
        assertEquals("http://jellyfin:8096", config.mediaServer.url)
        assertEquals("https://jellyfin.example.com", config.mediaServer.publicUrl)
        assertEquals("discord", config.notifications.provider)
        assertEquals("https://discord.com/api/webhooks/123/xyz", config.notifications.discord.webhookUrl)
    }

    @Test
    fun `should resolve file-based secret mounts correctly`() {
        val tempSecretFile = java.io.File.createTempFile("auth_secret_", ".txt")
        tempSecretFile.writeText("super-secret-from-file\n")
        tempSecretFile.deleteOnExit()

        val env =
            mapOf(
                "SERVER_AUTH_TOKEN_FILE" to tempSecretFile.absolutePath
            )

        val config = ConfigLoader.load(env)
        assertEquals("super-secret-from-file", config.server.authToken)
    }
}
