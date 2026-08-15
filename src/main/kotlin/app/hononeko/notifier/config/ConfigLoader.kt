package app.hononeko.notifier.config

import java.io.File

object ConfigLoader {
    fun load(env: Map<String, String> = System.getenv()): AppConfig {
        fun get(vararg keys: String): String? {
            for (key in keys) {
                val value = env[key]
                if (!value.isNullOrBlank()) return value.trim()
                // Also check normalized uppercase / dot notation alternatives
                val altUpper = key.replace('.', '_').replace('-', '_').uppercase()
                val altVal = env[altUpper]
                if (!altVal.isNullOrBlank()) return altVal.trim()
            }
            return null
        }

        fun getSecret(vararg keys: String): String? {
            for (key in keys) {
                val fileKey = "${key}_FILE"
                val fileKeyAlt = fileKey.replace('.', '_').replace('-', '_').uppercase()
                val filePath = env[fileKey] ?: env[fileKeyAlt]
                if (!filePath.isNullOrBlank()) {
                    val file = File(filePath.trim())
                    if (file.exists() && file.canRead()) {
                        return file.readText().trim()
                    }
                }
                val directVal = get(key)
                if (!directVal.isNullOrBlank()) return directVal
            }
            return null
        }

        fun getInt(
            default: Int,
            vararg keys: String
        ): Int = get(*keys)?.toIntOrNull() ?: default

        fun getLong(
            default: Long,
            vararg keys: String
        ): Long = get(*keys)?.toLongOrNull() ?: default

        fun getBoolean(
            default: Boolean,
            vararg keys: String
        ): Boolean = get(*keys)?.toBooleanStrictOrNull() ?: default

        val server =
            ServerConfig(
                port = getInt(8080, "SERVER_PORT", "server.port"),
                authToken = getSecret("SERVER_AUTH_TOKEN", "server.authToken", "SERVER_TOKEN") ?: "",
                rateLimitPerMinute = getInt(120, "SERVER_RATE_LIMIT_PER_MINUTE", "server.rateLimitPerMinute")
            )

        val mediaServer =
            MediaServerConfig(
                type = get("MEDIA_SERVER_TYPE", "mediaServer.type") ?: "plex",
                url = get("MEDIA_SERVER_URL", "mediaServer.url") ?: "",
                publicUrl = get("MEDIA_SERVER_PUBLIC_URL", "mediaServer.publicUrl") ?: ""
            )

        val qbittorrent =
            QBittorrentConfig(
                url = get("QBITTORRENT_URL", "qbittorrent.url") ?: "http://localhost:8080",
                username = get("QBITTORRENT_USERNAME", "qbittorrent.username", "QBITTORRENT_USER") ?: "",
                password = getSecret("QBITTORRENT_PASSWORD", "qbittorrent.password", "QBITTORRENT_PASS") ?: "",
                pollIntervalSeconds =
                    getLong(
                        5L,
                        "QBITTORRENT_POLL_INTERVAL_SECONDS",
                        "qbittorrent.pollIntervalSeconds"
                    ),
                maxPollingMinutes =
                    getLong(
                        30L,
                        "QBITTORRENT_MAX_POLLING_MINUTES",
                        "qbittorrent.maxPollingMinutes"
                    ),
                stalledTimeoutMinutes =
                    getLong(
                        15L,
                        "QBITTORRENT_STALLED_TIMEOUT_MINUTES",
                        "qbittorrent.stalledTimeoutMinutes"
                    ),
                missingGraceAttempts =
                    getInt(
                        6,
                        "QBITTORRENT_MISSING_GRACE_ATTEMPTS",
                        "qbittorrent.missingGraceAttempts"
                    ),
                debounceSeconds = getLong(5L, "QBITTORRENT_DEBOUNCE_SECONDS", "qbittorrent.debounceSeconds"),
                webuiPublicUrl =
                    get(
                        "QBITTORRENT_WEBUI_PUBLIC_URL",
                        "QBITTORRENT_PUBLIC_URL",
                        "qbittorrent.webuiPublicUrl",
                        "qbittorrent.publicUrl"
                    ) ?: ""
            )

        val notificationProvider =
            get("NOTIFICATION_PROVIDER", "NOTIFICATIONS_PROVIDER", "notifications.provider") ?: "telegram"

        val telegram =
            TelegramConfig(
                enabled = getBoolean(true, "NOTIFICATIONS_TELEGRAM_ENABLED", "notifications.telegram.enabled"),
                botToken =
                    getSecret(
                        "TELEGRAM_BOT_TOKEN",
                        "NOTIFICATIONS_TELEGRAM_BOT_TOKEN",
                        "notifications.telegram.botToken"
                    ) ?: "",
                chatId =
                    get("TELEGRAM_CHAT_ID", "NOTIFICATIONS_TELEGRAM_CHAT_ID", "notifications.telegram.chatId") ?: "",
                topicId =
                    get(
                        "TELEGRAM_TOPIC_ID",
                        "NOTIFICATIONS_TELEGRAM_TOPIC_ID",
                        "notifications.telegram.topicId"
                    )?.toLongOrNull(),
                rateLimitPerMinute =
                    getInt(
                        30,
                        "TELEGRAM_RATE_LIMIT_PER_MINUTE",
                        "NOTIFICATIONS_TELEGRAM_RATE_LIMIT_PER_MINUTE",
                        "notifications.telegram.rateLimitPerMinute"
                    ),
                timeoutSeconds =
                    getLong(
                        5L,
                        "TELEGRAM_TIMEOUT_SECONDS",
                        "NOTIFICATIONS_TELEGRAM_TIMEOUT_SECONDS",
                        "notifications.telegram.timeoutSeconds"
                    ),
                sendPhotos =
                    getBoolean(
                        true,
                        "TELEGRAM_SEND_PHOTOS",
                        "NOTIFICATIONS_TELEGRAM_SEND_PHOTOS",
                        "notifications.telegram.sendPhotos"
                    )
            )

        val discord =
            DiscordConfig(
                enabled = getBoolean(false, "NOTIFICATIONS_DISCORD_ENABLED", "notifications.discord.enabled"),
                webhookUrl =
                    getSecret(
                        "DISCORD_WEBHOOK_URL",
                        "NOTIFICATIONS_DISCORD_WEBHOOK_URL",
                        "notifications.discord.webhookUrl"
                    ) ?: ""
            )

        return AppConfig(
            server = server,
            mediaServer = mediaServer,
            qbittorrent = qbittorrent,
            notifications =
                NotificationsConfig(
                    provider = notificationProvider,
                    telegram = telegram,
                    discord = discord
                )
        )
    }
}
