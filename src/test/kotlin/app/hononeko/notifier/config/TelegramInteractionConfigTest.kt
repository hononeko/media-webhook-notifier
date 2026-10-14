package app.hononeko.notifier.config

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramInteractionConfigTest {
    private val base =
        mapOf(
            "TELEGRAM_BOT_TOKEN" to "1:T",
            "TELEGRAM_CHAT_ID" to "-1001",
            "TELEGRAM_INTERACTIVE_ENABLED" to "true",
            "TELEGRAM_ALLOWED_USERS" to "42"
        )
    private val webhookBase = base + ("TELEGRAM_UPDATES_MODE" to "webhook")

    private fun assertBootFails(
        env: Map<String, String>,
        variable: String
    ) {
        val e = assertFailsWith<IllegalArgumentException> { ConfigLoader.load(env) }
        assertTrue(e.message!!.contains(variable), "expected '$variable' in: ${e.message}")
    }

    @Test
    fun `flag defaults off and nothing is validated`() {
        val config = ConfigLoader.load(mapOf("TELEGRAM_ALLOWED_USERS" to "@alice", "TELEGRAM_UPDATES_MODE" to "bogus"))

        assertFalse(config.features.telegramInteractive)
    }

    @Test
    fun `enabled flag with valid polling config loads`() {
        val config = ConfigLoader.load(base)

        assertTrue(config.features.telegramInteractive)
        assertEquals(TelegramUpdatesMode.POLLING, config.telegramInteraction.mode)
        assertEquals(setOf(42L), config.telegramInteraction.allowedUserIds)
        assertEquals(25, config.telegramInteraction.pollTimeoutSeconds)
    }

    @Test
    fun `allowlist tolerates spaces and trailing comma`() {
        val config = ConfigLoader.load(base + ("TELEGRAM_ALLOWED_USERS" to " 123, 456,"))

        assertEquals(setOf(123L, 456L), config.telegramInteraction.allowedUserIds)
    }

    @Test
    fun `username, negative id or empty allowlist fails boot naming the variable`() {
        for (bad in listOf("@alice", "-100123", "", " , ", "0")) {
            assertBootFails(base + ("TELEGRAM_ALLOWED_USERS" to bad), "TELEGRAM_ALLOWED_USERS")
        }
        assertBootFails(base - "TELEGRAM_ALLOWED_USERS", "TELEGRAM_ALLOWED_USERS")
    }

    @Test
    fun `unknown mode fails boot`() {
        assertBootFails(base + ("TELEGRAM_UPDATES_MODE" to "push"), "TELEGRAM_UPDATES_MODE")
    }

    @Test
    fun `blank bot token or chat id fails boot when enabled`() {
        assertBootFails(base - "TELEGRAM_BOT_TOKEN", "TELEGRAM_BOT_TOKEN")
        assertBootFails(base - "TELEGRAM_CHAT_ID", "TELEGRAM_CHAT_ID")
    }

    @Test
    fun `webhook mode requires https url and valid secret`() {
        assertBootFails(webhookBase + ("TELEGRAM_WEBHOOK_SECRET" to "s"), "TELEGRAM_WEBHOOK_URL")
        assertBootFails(
            webhookBase + mapOf("TELEGRAM_WEBHOOK_URL" to "http://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "s"),
            "TELEGRAM_WEBHOOK_URL"
        )
        assertBootFails(
            webhookBase + mapOf("TELEGRAM_WEBHOOK_URL" to "https://", "TELEGRAM_WEBHOOK_SECRET" to "s"),
            "TELEGRAM_WEBHOOK_URL"
        )
        assertBootFails(webhookBase + ("TELEGRAM_WEBHOOK_URL" to "https://x.example.com"), "TELEGRAM_WEBHOOK_SECRET")
        assertBootFails(
            webhookBase +
                mapOf("TELEGRAM_WEBHOOK_URL" to "https://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "has space"),
            "TELEGRAM_WEBHOOK_SECRET"
        )
        assertBootFails(
            webhookBase +
                mapOf("TELEGRAM_WEBHOOK_URL" to "https://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "a".repeat(257)),
            "TELEGRAM_WEBHOOK_SECRET"
        )
    }

    @Test
    fun `valid webhook config loads`() {
        val config =
            ConfigLoader.load(
                webhookBase +
                    mapOf("TELEGRAM_WEBHOOK_URL" to "https://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "s3cret_-X")
            )

        assertEquals(TelegramUpdatesMode.WEBHOOK, config.telegramInteraction.mode)
        assertEquals("s3cret_-X", config.telegramInteraction.webhookSecret)
        assertEquals("https://x.example.com/api/v1/telegram/webhook", config.telegramInteraction.webhookEndpoint)
    }

    @Test
    fun `webhook secret can come from a file`() {
        val file = createTempFile("mwn-webhook-secret", ".txt")
        try {
            file.writeText("fromfile123\n")
            val config =
                ConfigLoader.load(
                    webhookBase +
                        mapOf(
                            "TELEGRAM_WEBHOOK_URL" to "https://x.example.com",
                            "TELEGRAM_WEBHOOK_SECRET_FILE" to file.toString()
                        )
                )

            assertEquals("fromfile123", config.telegramInteraction.webhookSecret)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `webhook endpoint never doubles slash or path`() {
        for (url in listOf(
            "https://x.example.com",
            "https://x.example.com/",
            "https://x.example.com/api/v1/telegram/webhook",
            "https://x.example.com/api/v1/telegram/webhook/"
        )) {
            assertEquals(
                "https://x.example.com/api/v1/telegram/webhook",
                TelegramInteractionConfig(webhookUrl = url).webhookEndpoint,
                url
            )
        }
        assertEquals(
            "https://x.example.com/notifier/api/v1/telegram/webhook",
            TelegramInteractionConfig(webhookUrl = "https://x.example.com/notifier").webhookEndpoint
        )
    }

    @Test
    fun `poll timeout outside 1-50 fails boot`() {
        for (bad in listOf("0", "51", "abc")) {
            assertBootFails(base + ("TELEGRAM_POLL_TIMEOUT_SECONDS" to bad), "TELEGRAM_POLL_TIMEOUT_SECONDS")
        }
        assertEquals(
            50,
            ConfigLoader.load(base + ("TELEGRAM_POLL_TIMEOUT_SECONDS" to "50")).telegramInteraction.pollTimeoutSeconds
        )
    }

    @Test
    fun `non-telegram provider disables the feature without failing`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "NOTIFICATION_URL" to "discord://abc",
                    "TELEGRAM_INTERACTIVE_ENABLED" to "true"
                )
            )

        assertFalse(config.features.telegramInteractive)
    }

    @Test
    fun `admin users load and count as allowed`() {
        val config = ConfigLoader.load(base + ("TELEGRAM_ADMIN_USERS" to "7, 42"))

        assertEquals(setOf(7L, 42L), config.telegramInteraction.adminUserIds)
        assertEquals(setOf(42L), config.telegramInteraction.allowedUserIds)
        assertEquals(setOf(7L, 42L), config.telegramInteraction.authorizedUserIds)
    }

    @Test
    fun `admins alone satisfy the allowlist requirement`() {
        val config = ConfigLoader.load(base - "TELEGRAM_ALLOWED_USERS" + ("TELEGRAM_ADMIN_USERS" to "7"))

        assertTrue(config.features.telegramInteractive)
        assertEquals(setOf(7L), config.telegramInteraction.authorizedUserIds)
    }

    @Test
    fun `admin users default to none and reject usernames`() {
        assertEquals(emptySet(), ConfigLoader.load(base).telegramInteraction.adminUserIds)
        assertBootFails(base + ("TELEGRAM_ADMIN_USERS" to "@alice"), "TELEGRAM_ADMIN_USERS")
    }

    @Test
    fun `Seerr loads url and api key, including from a file`() {
        val file = createTempFile("mwn-seerr-key", ".txt")
        try {
            file.writeText("filekey\n")
            val config =
                ConfigLoader.load(
                    mapOf(
                        "SEERR_URL" to "http://seerr:5055/",
                        "SEERR_API_KEY_FILE" to file.toString()
                    )
                )

            assertEquals(SeerrConfig(url = "http://seerr:5055", apiKey = "filekey"), config.seerr)
            assertTrue(config.seerr.isConfigured)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `Seerr is off by default and the key never appears in toString`() {
        assertFalse(ConfigLoader.load(emptyMap()).seerr.isConfigured)
        assertFalse(SeerrConfig("http://seerr", "s3cr3t").toString().contains("s3cr3t"))
    }

    @Test
    fun `half-configured or invalid Seerr fails boot`() {
        assertBootFails(mapOf("SEERR_URL" to "http://seerr:5055"), "SEERR_API_KEY")
        assertBootFails(mapOf("SEERR_API_KEY" to "k"), "SEERR_URL")
        assertBootFails(mapOf("SEERR_URL" to "seerr:5055", "SEERR_API_KEY" to "k"), "SEERR_URL")
        assertBootFails(mapOf("SEERR_URL" to "ftp://seerr", "SEERR_API_KEY" to "k"), "SEERR_URL")
    }
}
