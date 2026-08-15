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
        assertEquals("", config.notifications.botToken)
        assertEquals("", config.notifications.chatId)
        assertNull(config.notifications.topicId)
        assertEquals(30, config.notifications.rateLimitPerMinute)
        assertEquals(5, config.notifications.timeoutSeconds)
        assertTrue(config.notifications.sendPhotos)
    }

    @Test
    fun `should override configuration with environment variables and custom property sources`() {
        val env =
            mapOf(
                "SERVER_AUTH_TOKEN" to "env-supplied-token",
                "SERVER_PORT" to "9090",
                "NOTIFICATION_URL" to
                    "telegram://tg-bot-12345@-100123456?topic=42&photos=false&rate_limit=45&timeout=10",
                "QBITTORRENT_URL" to "http://qbittorrent:8080",
                "MEDIA_SERVER_TYPE" to "jellyfin",
                "MEDIA_SERVER_URL" to "http://jellyfin:8096",
                "MEDIA_SERVER_PUBLIC_URL" to "https://jellyfin.example.com"
            )

        val config = ConfigLoader.load(env)

        assertEquals("env-supplied-token", config.server.authToken)
        assertEquals(9090, config.server.port)
        assertEquals("telegram", config.notifications.provider)
        assertEquals("tg-bot-12345", config.notifications.botToken)
        assertEquals("-100123456", config.notifications.chatId)
        assertEquals(42L, config.notifications.topicId)
        assertEquals(false, config.notifications.sendPhotos)
        assertEquals(45, config.notifications.rateLimitPerMinute)
        assertEquals(10L, config.notifications.timeoutSeconds)
        assertEquals("http://qbittorrent:8080", config.qbittorrent.url)
        assertEquals("jellyfin", config.mediaServer.type)
        assertEquals("http://jellyfin:8096", config.mediaServer.url)
        assertEquals("https://jellyfin.example.com", config.mediaServer.publicUrl)
    }

    @Test
    fun `should resolve file-based secret mounts correctly`() {
        val tempSecretFile = java.io.File.createTempFile("auth_secret_", ".txt")
        tempSecretFile.writeText("super-secret-from-file\n")
        tempSecretFile.deleteOnExit()

        val tempNotificationUrlFile = java.io.File.createTempFile("notification_url_", ".txt")
        tempNotificationUrlFile.writeText("telegram://secret-bot-token@-100999\n")
        tempNotificationUrlFile.deleteOnExit()

        val env =
            mapOf(
                "SERVER_AUTH_TOKEN_FILE" to tempSecretFile.absolutePath,
                "NOTIFICATION_URL_FILE" to tempNotificationUrlFile.absolutePath
            )

        val config = ConfigLoader.load(env)
        assertEquals("super-secret-from-file", config.server.authToken)
        assertEquals("secret-bot-token", config.notifications.botToken)
        assertEquals("-100999", config.notifications.chatId)
    }
}
