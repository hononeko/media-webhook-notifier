package app.hononeko.notifier.config

import app.hononeko.notifier.adapter.outbound.notification.NotificationUrlParser
import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.TemplateConfig
import java.io.File

object ConfigLoader {
    fun load(env: Map<String, String> = System.getenv()): AppConfig {
        val reader = EnvReader(env)
        return AppConfig(
            server = loadServerConfig(reader),
            mediaServer = loadMediaServerConfig(reader),
            qbittorrent = loadQBittorrentConfig(reader),
            notifications = loadNotificationsConfig(reader),
            templates = loadTemplatesConfig(reader)
        )
    }

    private fun loadServerConfig(reader: EnvReader): ServerConfig =
        ServerConfig(
            port = reader.getInt(8080, "SERVER_PORT", "server.port"),
            authToken = reader.getSecret("SERVER_AUTH_TOKEN", "server.authToken") ?: "",
            rateLimitPerMinute = reader.getInt(120, "SERVER_RATE_LIMIT_PER_MINUTE", "server.rateLimitPerMinute"),
            enablePreview = reader.getBoolean(false, "ENABLE_PREVIEW", "server.enablePreview", "SERVER_ENABLE_PREVIEW"),
            eventRailWorkers =
                reader.getInt(
                    4,
                    "EVENT_RAIL_WORKERS",
                    "server.eventRailWorkers",
                    "SERVER_EVENT_RAIL_WORKERS"
                )
        )

    private fun loadTemplatesConfig(reader: EnvReader): TemplateConfig {
        val defaultStream =
            ConfigLoader::class.java.classLoader.getResourceAsStream("templates.default.yaml")
                ?: throw IllegalStateException(
                    "Default templates resource 'templates.default.yaml' not found on classpath!"
                )
        val defaultContent = defaultStream.bufferedReader().use { it.readText() }
        val defaultConfig = YamlParser.parseTemplateConfig(defaultContent)

        val directYaml = reader.get("TEMPLATES_YAML", "templates.yaml")
        if (!directYaml.isNullOrBlank()) {
            val userConfig = YamlParser.parseTemplateConfig(directYaml, defaultTheme = defaultConfig.theme)
            return mergeTemplates(defaultConfig, userConfig)
        }

        val filePath =
            reader.get(
                "TEMPLATES_FILE",
                "TEMPLATES_FILE_PATH",
                "TEMPLATES_CONFIG_PATH",
                "templates.file",
                "templates.path"
            )
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            check(file.exists() && file.canRead()) {
                "Configured templates file '$filePath' does not exist or is not readable"
            }
            val userConfig = YamlParser.parseTemplateConfig(file.readText(), defaultTheme = defaultConfig.theme)
            return mergeTemplates(defaultConfig, userConfig)
        }

        val secretFile = reader.getSecret("TEMPLATES")
        if (!secretFile.isNullOrBlank()) {
            val userConfig = YamlParser.parseTemplateConfig(secretFile, defaultTheme = defaultConfig.theme)
            return mergeTemplates(defaultConfig, userConfig)
        }

        val defaultFiles = listOf("templates.yaml", "templates.yml", "/config/templates.yaml", "/config/templates.yml")
        for (defaultPath in defaultFiles) {
            val file = File(defaultPath)
            if (file.exists() && file.canRead()) {
                val userConfig = YamlParser.parseTemplateConfig(file.readText(), defaultTheme = defaultConfig.theme)
                return mergeTemplates(defaultConfig, userConfig)
            }
        }

        return defaultConfig
    }

    private fun mergeTemplates(
        base: TemplateConfig,
        override: TemplateConfig
    ): TemplateConfig {
        val mergedEvents = base.events.toMutableMap()
        for ((key, tpl) in override.events) {
            val baseTpl = mergedEvents[key]
            mergedEvents[key] =
                if (baseTpl != null) {
                    EventTemplate(
                        title = tpl.title ?: baseTpl.title,
                        subtitle = tpl.subtitle ?: baseTpl.subtitle,
                        body = tpl.body ?: baseTpl.body,
                        artworkUrl = tpl.artworkUrl ?: baseTpl.artworkUrl,
                        imageEmbed = tpl.imageEmbed ?: baseTpl.imageEmbed,
                        stateText = tpl.stateText ?: baseTpl.stateText,
                        actions = if (tpl.actions.isNotEmpty()) tpl.actions else baseTpl.actions
                    )
                } else {
                    tpl
                }
        }
        return TemplateConfig(theme = override.theme, events = mergedEvents)
    }

    private fun loadMediaServerConfig(reader: EnvReader): MediaServerConfig =
        MediaServerConfig(
            type = reader.get("MEDIA_SERVER_TYPE", "mediaServer.type") ?: "plex",
            url = reader.get("MEDIA_SERVER_URL", "mediaServer.url") ?: "",
            publicUrl = reader.get("MEDIA_SERVER_PUBLIC_URL", "mediaServer.publicUrl") ?: ""
        )

    private fun loadQBittorrentConfig(reader: EnvReader): QBittorrentConfig =
        QBittorrentConfig(
            url = reader.get("QBITTORRENT_URL", "qbittorrent.url") ?: "http://localhost:8080",
            username = reader.get("QBITTORRENT_USERNAME", "qbittorrent.username") ?: "",
            password = reader.getSecret("QBITTORRENT_PASSWORD", "qbittorrent.password") ?: "",
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
                    "qbittorrent.webuiPublicUrl"
                ) ?: "",
            reconciliationEnabled =
                reader.getBoolean(
                    true,
                    "QBITTORRENT_RECONCILIATION_ENABLED",
                    "qbittorrent.reconciliationEnabled"
                ),
            reconciliationIntervalMinutes =
                reader.getLong(
                    5L,
                    "QBITTORRENT_RECONCILIATION_INTERVAL_MINUTES",
                    "qbittorrent.reconciliationIntervalMinutes"
                )
        )

    private fun loadNotificationsConfig(reader: EnvReader): NotificationConfig {
        val rawUrl = reader.getSecret("NOTIFICATION_URL", "notifications.url") ?: ""
        val baseConfig =
            if (rawUrl.isNotBlank()) {
                NotificationUrlParser.parse(rawUrl)
            } else {
                NotificationConfig()
            }

        val botToken = reader.getSecret("TELEGRAM_BOT_TOKEN", "telegram.botToken")
        val chatId = reader.get("TELEGRAM_CHAT_ID", "telegram.chatId")
        val topicId = reader.getLong(0L, "TELEGRAM_TOPIC_ID", "telegram.topicId").takeIf { it > 0 }
        val sendPhotos =
            reader.getBoolean(
                baseConfig.sendPhotos,
                "NOTIFICATION_SEND_PHOTOS",
                "NOTIFICATION_PHOTOS",
                "TELEGRAM_SEND_PHOTOS",
                "notifications.sendPhotos"
            )
        val rateLimit =
            reader.getInt(
                baseConfig.rateLimitPerMinute,
                "NOTIFICATION_RATE_LIMIT_PER_MINUTE",
                "notifications.rateLimitPerMinute"
            )
        val timeout =
            reader.getLong(
                baseConfig.timeoutSeconds,
                "NOTIFICATION_TIMEOUT_SECONDS",
                "notifications.timeoutSeconds"
            )

        return baseConfig.copy(
            botToken = botToken ?: baseConfig.botToken,
            chatId = chatId ?: baseConfig.chatId,
            topicId = topicId ?: baseConfig.topicId,
            sendPhotos = sendPhotos,
            rateLimitPerMinute = rateLimit,
            timeoutSeconds = timeout
        )
    }

    internal class EnvReader(
        val env: Map<String, String>
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
