package app.hononeko.notifier.config

import java.io.File

object ConfigLoader {
    fun load(env: Map<String, String> = System.getenv()): AppConfig {
        val reader = EnvReader(env)
        return AppConfig(
            server = loadServerConfig(reader),
            mediaServer = loadMediaServerConfig(reader),
            qbittorrent = loadQBittorrentConfig(reader),
            notifications = loadNotificationsConfig(reader)
        )
    }

    private fun loadServerConfig(reader: EnvReader): ServerConfig =
        ServerConfig(
            port = reader.getInt(8080, "SERVER_PORT", "server.port"),
            authToken = reader.getSecret("SERVER_AUTH_TOKEN", "server.authToken", "SERVER_TOKEN") ?: "",
            rateLimitPerMinute = reader.getInt(120, "SERVER_RATE_LIMIT_PER_MINUTE", "server.rateLimitPerMinute")
        )

    private fun loadMediaServerConfig(reader: EnvReader): MediaServerConfig =
        MediaServerConfig(
            type = reader.get("MEDIA_SERVER_TYPE", "mediaServer.type") ?: "plex",
            url = reader.get("MEDIA_SERVER_URL", "mediaServer.url") ?: "",
            publicUrl = reader.get("MEDIA_SERVER_PUBLIC_URL", "mediaServer.publicUrl") ?: ""
        )

    private fun loadQBittorrentConfig(reader: EnvReader): QBittorrentConfig =
        QBittorrentConfig(
            url = reader.get("QBITTORRENT_URL", "qbittorrent.url") ?: "http://localhost:8080",
            username = reader.get("QBITTORRENT_USERNAME", "qbittorrent.username", "QBITTORRENT_USER") ?: "",
            password = reader.getSecret("QBITTORRENT_PASSWORD", "qbittorrent.password", "QBITTORRENT_PASS") ?: "",
            pollIntervalSeconds =
                reader.getLong(
                    5L,
                    "QBITTORRENT_POLL_INTERVAL_SECONDS",
                    "qbittorrent.pollIntervalSeconds"
                ),
            maxPollingMinutes =
                reader.getLong(
                    30L,
                    "QBITTORRENT_MAX_POLLING_MINUTES",
                    "qbittorrent.maxPollingMinutes"
                ),
            stalledTimeoutMinutes =
                reader.getLong(
                    15L,
                    "QBITTORRENT_STALLED_TIMEOUT_MINUTES",
                    "qbittorrent.stalledTimeoutMinutes"
                ),
            missingGraceAttempts =
                reader.getInt(
                    6,
                    "QBITTORRENT_MISSING_GRACE_ATTEMPTS",
                    "qbittorrent.missingGraceAttempts"
                ),
            debounceSeconds =
                reader.getLong(
                    5L,
                    "QBITTORRENT_DEBOUNCE_SECONDS",
                    "qbittorrent.debounceSeconds"
                ),
            webuiPublicUrl =
                reader.get(
                    "QBITTORRENT_WEBUI_PUBLIC_URL",
                    "QBITTORRENT_PUBLIC_URL",
                    "qbittorrent.webuiPublicUrl",
                    "qbittorrent.publicUrl"
                ) ?: ""
        )

    private fun loadNotificationsConfig(reader: EnvReader): NotificationConfig =
        NotificationConfig(
            provider =
                reader.get(
                    "NOTIFICATION_PROVIDER",
                    "notifications.provider"
                ) ?: "telegram",
            botToken =
                reader.getSecret(
                    "TELEGRAM_BOT_TOKEN",
                    "NOTIFICATION_BOT_TOKEN",
                    "notifications.botToken",
                    "notifications.telegram.botToken"
                ) ?: "",
            chatId =
                reader.get(
                    "TELEGRAM_CHAT_ID",
                    "NOTIFICATION_CHAT_ID",
                    "notifications.chatId",
                    "notifications.telegram.chatId"
                ) ?: "",
            topicId =
                reader
                    .get(
                        "TELEGRAM_TOPIC_ID",
                        "NOTIFICATION_TOPIC_ID",
                        "notifications.topicId",
                        "notifications.telegram.topicId"
                    )?.toLongOrNull(),
            sendPhotos =
                reader.getBoolean(
                    true,
                    "NOTIFICATION_SEND_PHOTOS",
                    "TELEGRAM_SEND_PHOTOS",
                    "notifications.sendPhotos",
                    "notifications.telegram.sendPhotos"
                ),
            rateLimitPerMinute =
                reader.getInt(
                    30,
                    "NOTIFICATION_RATE_LIMIT_PER_MINUTE",
                    "TELEGRAM_RATE_LIMIT_PER_MINUTE",
                    "notifications.rateLimitPerMinute",
                    "notifications.telegram.rateLimitPerMinute"
                ),
            timeoutSeconds =
                reader.getLong(
                    5L,
                    "NOTIFICATION_TIMEOUT_SECONDS",
                    "TELEGRAM_TIMEOUT_SECONDS",
                    "notifications.timeoutSeconds",
                    "notifications.telegram.timeoutSeconds"
                )
        )

    internal class EnvReader(
        private val env: Map<String, String>
    ) {
        fun get(vararg keys: String): String? {
            for (key in keys) {
                val value = env[key]
                if (!value.isNullOrBlank()) return value.trim()
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
    }
}
