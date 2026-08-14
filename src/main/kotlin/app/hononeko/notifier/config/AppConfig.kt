package app.hononeko.notifier.config

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val mediaServer: MediaServerConfig = MediaServerConfig(),
    val qbittorrent: QBittorrentConfig = QBittorrentConfig(),
    val notifications: NotificationsConfig = NotificationsConfig()
)

data class ServerConfig(
    val port: Int = 8080,
    val authToken: String = "",
    val rateLimitPerMinute: Int = 120
)

data class MediaServerConfig(
    val type: String = "plex",
    val baseUrl: String = "",
    val plexPublicUrl: String = "",
    val jellyfinPublicUrl: String = ""
)

data class QBittorrentConfig(
    val url: String = "http://localhost:8080",
    val username: String = "",
    val password: String = "",
    val pollIntervalSeconds: Long = 5,
    val maxPollingMinutes: Long = 30,
    val stalledTimeoutMinutes: Long = 15,
    val missingGraceAttempts: Int = 6,
    val debounceSeconds: Long = 5,
    val webuiPublicUrl: String = ""
)

data class NotificationsConfig(
    val telegram: TelegramConfig = TelegramConfig(),
    val discord: DiscordConfig = DiscordConfig()
)

data class TelegramConfig(
    val enabled: Boolean = true,
    val botToken: String = "",
    val chatId: String = "",
    val topicId: Long? = null,
    val rateLimitPerMinute: Int = 30,
    val timeoutSeconds: Long = 5,
    val sendPhotos: Boolean = true
)

data class DiscordConfig(
    val enabled: Boolean = false,
    val webhookUrl: String = ""
)
