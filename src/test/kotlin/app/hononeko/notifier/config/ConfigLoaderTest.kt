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
        assertEquals(86400L, config.mediaServer.maxAvailableAgeSeconds)

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
                "MEDIA_SERVER_PUBLIC_URL" to "https://jellyfin.example.com",
                "MEDIA_SERVER_MAX_AVAILABLE_AGE_SECONDS" to "43200"
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
        assertEquals(43200L, config.mediaServer.maxAvailableAgeSeconds)
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

    @Test
    fun `should load prepopulated default templates when environment is empty`() {
        val config = ConfigLoader.load(emptyMap())
        assertNotNull(config.templates)
        assertNotNull(config.templates.events["grab"])
        assertEquals(true, config.templates.events["grab"]?.imageEmbed)
        assertEquals(false, config.templates.events["import"]?.imageEmbed)
        assertEquals(false, config.templates.events["manual_interaction"]?.imageEmbed)
        assertEquals(true, config.templates.events["media_available"]?.imageEmbed)
        assertEquals(true, config.templates.events["request"]?.imageEmbed)
        assertEquals(false, config.templates.events["issue"]?.imageEmbed)
        assertNull(config.templates.events["health"]?.imageEmbed)
        assertNull(config.templates.events["download_complete"]?.imageEmbed)
        assertNull(config.templates.events["download_stalled"]?.imageEmbed)
    }

    @Test
    fun `should fail startup if configured templates file does not exist`() {
        val env = mapOf("TEMPLATES_FILE" to "/non/existent/templates.yaml")
        kotlin.test.assertFailsWith<IllegalStateException> {
            ConfigLoader.load(env)
        }
    }

    @Test
    fun `should merge user template overrides with default templates`() {
        val env =
            mapOf(
                "TEMPLATES_YAML" to "events:\n  grab:\n    title: 'Custom Grab'"
            )
        val config = ConfigLoader.load(env)
        assertEquals("Custom Grab", config.templates.events["grab"]?.title)
        assertNotNull(config.templates.events["import"])
        assertNotNull(config.templates.events["media_available"])
    }

    @Test
    fun `should preserve default body when user template override only toggles image_embed`() {
        val env =
            mapOf(
                "TEMPLATES_YAML" to "events:\n  grab:\n    image_embed: false"
            )
        val config = ConfigLoader.load(env)
        val grabTemplate = config.templates.events["grab"]
        assertNotNull(grabTemplate)
        assertEquals(false, grabTemplate.imageEmbed)
        assertEquals("⏳ Downloading {title} from {indexer}", grabTemplate.title)
        assertNotNull(grabTemplate.body)
    }

    @Test
    fun `should preserve default theme settings when user override only specifies events`() {
        val env =
            mapOf(
                "TEMPLATES_YAML" to "events:\n  grab:\n    title: 'Custom Grab'"
            )
        val config = ConfigLoader.load(env)
        assertEquals(240, config.templates.theme.maxOverviewLength)
        assertEquals(10, config.templates.theme.progressBarLength)
        assertEquals("default", config.templates.theme.progressBarStyle)
        assertEquals("yyyy-MM-dd HH:mm", config.templates.theme.dateFormat)
    }

    @Test
    fun `should merge partial theme override with default theme settings`() {
        val env =
            mapOf(
                "TEMPLATES_YAML" to "theme:\n  max_overview_length: 320\nevents:\n  grab:\n    title: 'Custom Grab'"
            )
        val config = ConfigLoader.load(env)
        assertEquals(320, config.templates.theme.maxOverviewLength)
        assertEquals(10, config.templates.theme.progressBarLength)
        assertEquals("default", config.templates.theme.progressBarStyle)
        assertEquals("yyyy-MM-dd HH:mm", config.templates.theme.dateFormat)
    }
}
