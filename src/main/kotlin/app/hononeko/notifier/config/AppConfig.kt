package app.hononeko.notifier.config

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val mediaServer: MediaServerConfig = MediaServerConfig(),
    val qbittorrent: QBittorrentConfig = QBittorrentConfig(),
    val notifications: NotificationsConfig = NotificationsConfig()
)

data class ServerConfig(
    val port: Int = 8080,
    val authToken: String = ""
)

data class MediaServerConfig(
    val type: String = "plex",
    val baseUrl: String = "",
    val jellyfinUrl: String = ""
)

data class QBittorrentConfig(
    val url: String = "http://localhost:8080",
    val pollIntervalSeconds: Long = 5,
    val maxPollingMinutes: Long = 30,
    val stalledTimeoutMinutes: Long = 15,
    val webuiPublicUrl: String = ""
)

data class NotificationsConfig(
    val telegram: TelegramConfig = TelegramConfig()
)

data class TelegramConfig(
    val enabled: Boolean = true,
    val botToken: String = "",
    val chatId: String = "",
    val rateLimitPerMinute: Int = 30,
    val timeoutSeconds: Long = 5
)
