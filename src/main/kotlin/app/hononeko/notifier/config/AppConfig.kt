package app.hononeko.notifier.config

import app.hononeko.notifier.domain.model.TemplateConfig

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val mediaServer: MediaServerConfig = MediaServerConfig(),
    val qbittorrent: QBittorrentConfig = QBittorrentConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val templates: TemplateConfig = TemplateConfig()
)

data class ServerConfig(
    val port: Int = 8080,
    val authToken: String = "",
    val rateLimitPerMinute: Int = 120,
    val enablePreview: Boolean = false,
    val eventRailWorkers: Int = 4
)

data class MediaServerConfig(
    val type: String = "plex",
    val url: String = "",
    val publicUrl: String = ""
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
    val webuiPublicUrl: String = "",
    val reconciliationEnabled: Boolean = true,
    val reconciliationIntervalMinutes: Long = 5
)

data class NotificationConfig(
    val url: String = "",
    val provider: String = "telegram",
    val botToken: String = "",
    val chatId: String = "",
    val topicId: Long? = null,
    val sendPhotos: Boolean = true,
    val rateLimitPerMinute: Int = 30,
    val timeoutSeconds: Long = 5
)
