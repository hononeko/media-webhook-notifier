package app.hononeko.notifier.config

import app.hononeko.notifier.domain.model.TemplateConfig

data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val mediaServer: MediaServerConfig = MediaServerConfig(),
    val downloadClient: DownloadClientConfig = DownloadClientConfig(),
    val qbittorrent: QBittorrentConfig = QBittorrentConfig(),
    val transmission: TransmissionConfig = TransmissionConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val state: StateConfig = StateConfig(),
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
    val publicUrl: String = "",
    val maxAvailableAgeSeconds: Long = 86_400L
)

enum class DownloadClientType(
    val key: String
) {
    QBITTORRENT("qbittorrent"),
    TRANSMISSION("transmission"),
    SABNZBD("sabnzbd");

    companion object {
        fun fromKey(raw: String): DownloadClientType? = entries.firstOrNull { it.key == raw.trim().lowercase() }

        val supportedKeys: List<String> get() = entries.map { it.key }
    }
}

/** Which download client is active plus the client-agnostic live tracking settings. */
data class DownloadClientConfig(
    val type: DownloadClientType = DownloadClientType.QBITTORRENT,
    val pollIntervalSeconds: Long = 5,
    val maxPollingMinutes: Long = 30,
    val stalledTimeoutMinutes: Long = 15,
    val missingGraceAttempts: Int = 6,
    val debounceSeconds: Long = 5,
    val webuiPublicUrl: String = "",
    val reconciliationEnabled: Boolean = true,
    val reconciliationIntervalMinutes: Long = 5,
    val tagPrefix: String = "mwn_"
)

data class QBittorrentConfig(
    val url: String = "http://localhost:8080",
    val username: String = "",
    val password: String = ""
)

data class TransmissionConfig(
    val url: String = "http://localhost:9091",
    val username: String = "",
    val password: String = ""
)

data class StateConfig(
    val type: String = "memory",
    val url: String = "",
    val keyPrefix: String = "mwn:",
    val timeoutMillis: Long = 2000L,
    val maxPoolSize: Int = 16
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
