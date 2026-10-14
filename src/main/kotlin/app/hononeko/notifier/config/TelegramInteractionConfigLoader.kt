package app.hononeko.notifier.config

import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Loads `TELEGRAM_INTERACTIVE_ENABLED` and the Telegram interaction settings. Validation only runs when the flag is
 * on, so existing deployments never fail on variables they don't use.
 */
internal object TelegramInteractionConfigLoader {
    private val logger = LoggerFactory.getLogger(TelegramInteractionConfigLoader::class.java)

    private const val FLAG = "TELEGRAM_INTERACTIVE_ENABLED"
    private val SECRET_PATTERN = Regex("^[A-Za-z0-9_-]{1,256}$")
    private val POLL_TIMEOUT_RANGE = 1..50

    fun load(
        reader: ConfigLoader.EnvReader,
        notifications: NotificationConfig
    ): Pair<FeatureFlags, TelegramInteractionConfig> {
        val enabled = reader.getBoolean(false, FLAG, "telegram.interactiveEnabled")
        if (!enabled) return FeatureFlags() to TelegramInteractionConfig()

        if (!notifications.provider.equals("telegram", ignoreCase = true)) {
            logger.warn(
                "{} is set but the notification provider is '{}'; Telegram interactions stay disabled and " +
                    "TELEGRAM_UPDATES_MODE, TELEGRAM_ALLOWED_USERS, TELEGRAM_ADMIN_USERS, TELEGRAM_WEBHOOK_URL, " +
                    "TELEGRAM_WEBHOOK_SECRET and TELEGRAM_POLL_TIMEOUT_SECONDS are ignored.",
                FLAG,
                notifications.provider
            )
            return FeatureFlags() to TelegramInteractionConfig()
        }

        require(notifications.botToken.isNotBlank()) {
            "TELEGRAM_BOT_TOKEN (or the bot token in NOTIFICATION_URL) is required when $FLAG=true"
        }
        require(notifications.chatId.isNotBlank()) {
            "TELEGRAM_CHAT_ID (or the chat in NOTIFICATION_URL) is required when $FLAG=true"
        }

        val rawMode = reader.get("TELEGRAM_UPDATES_MODE", "telegram.updatesMode")
        val mode =
            if (rawMode == null) {
                TelegramUpdatesMode.POLLING
            } else {
                requireNotNull(TelegramUpdatesMode.fromKey(rawMode)) {
                    "TELEGRAM_UPDATES_MODE must be 'polling' or 'webhook', got '$rawMode'"
                }
            }

        val allowed =
            parseUserIds(reader.get("TELEGRAM_ALLOWED_USERS", "telegram.allowedUsers"), "TELEGRAM_ALLOWED_USERS")
        val admins = parseUserIds(reader.get("TELEGRAM_ADMIN_USERS", "telegram.adminUsers"), "TELEGRAM_ADMIN_USERS")
        require(allowed.isNotEmpty() || admins.isNotEmpty()) {
            "TELEGRAM_ALLOWED_USERS (or TELEGRAM_ADMIN_USERS) is required when $FLAG=true: " +
                "list the numeric Telegram user IDs allowed to use buttons"
        }

        val config =
            TelegramInteractionConfig(
                mode = mode,
                allowedUserIds = allowed,
                adminUserIds = admins,
                pollTimeoutSeconds =
                    parsePollTimeout(
                        reader.get("TELEGRAM_POLL_TIMEOUT_SECONDS", "telegram.pollTimeoutSeconds")
                    )
            )

        return FeatureFlags(telegramInteractive = true) to
            if (mode == TelegramUpdatesMode.WEBHOOK) withWebhook(config, reader) else config
    }

    private fun parseUserIds(
        raw: String?,
        variable: String
    ): Set<Long> =
        raw
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                requireNotNull(entry.toLongOrNull()?.takeIf { it > 0 }) {
                    "$variable entry '$entry' is not a numeric Telegram user ID. " +
                        "Usernames are not accepted because they can be changed or reclaimed; send the bot a message " +
                        "and read message.from.id from getUpdates to find yours."
                }
            }.toSet()

    private fun parsePollTimeout(raw: String?): Int {
        if (raw == null) return TelegramInteractionConfig().pollTimeoutSeconds
        val value = raw.toIntOrNull()
        require(value != null && value in POLL_TIMEOUT_RANGE) {
            "TELEGRAM_POLL_TIMEOUT_SECONDS must be a whole number between 1 and 50, got '$raw'"
        }
        return value
    }

    private fun withWebhook(
        config: TelegramInteractionConfig,
        reader: ConfigLoader.EnvReader
    ): TelegramInteractionConfig {
        val url =
            requireNotNull(reader.get("TELEGRAM_WEBHOOK_URL", "telegram.webhookUrl")) {
                "TELEGRAM_WEBHOOK_URL is required when TELEGRAM_UPDATES_MODE=webhook"
            }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "TELEGRAM_WEBHOOK_URL must be a public https:// URL with a host, got '$url'"
        }

        val secret =
            requireNotNull(reader.getSecret("TELEGRAM_WEBHOOK_SECRET", "telegram.webhookSecret")) {
                "TELEGRAM_WEBHOOK_SECRET is required when TELEGRAM_UPDATES_MODE=webhook"
            }
        // Never echo the secret itself in the error.
        require(SECRET_PATTERN.matches(secret)) {
            "TELEGRAM_WEBHOOK_SECRET must be 1-256 characters of A-Z, a-z, 0-9, '_' and '-'"
        }

        return config.copy(webhookUrl = url, webhookSecret = secret)
    }
}
