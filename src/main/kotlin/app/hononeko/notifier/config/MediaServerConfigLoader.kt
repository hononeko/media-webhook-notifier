package app.hononeko.notifier.config

/** `MEDIA_SERVER_*`: the media server, and the pinned status message (`MEDIA_SERVER_STATUS_*`). */
internal object MediaServerConfigLoader {
    fun load(reader: ConfigLoader.EnvReader): MediaServerConfig =
        MediaServerConfig(
            type = reader.get("MEDIA_SERVER_TYPE", "mediaServer.type") ?: "plex",
            url = reader.get("MEDIA_SERVER_URL", "mediaServer.url") ?: "",
            publicUrl = reader.get("MEDIA_SERVER_PUBLIC_URL", "mediaServer.publicUrl") ?: "",
            maxAvailableAgeSeconds =
                reader.getLong(
                    86_400L,
                    "MEDIA_SERVER_MAX_AVAILABLE_AGE_SECONDS",
                    "MEDIA_SERVER_MAX_AGE_SECONDS",
                    "mediaServer.maxAvailableAgeSeconds",
                    "mediaServer.maxAgeSeconds"
                ),
            name = reader.get("MEDIA_SERVER_NAME", "mediaServer.name") ?: "",
            status = loadStatusSettings(reader)
        )

    /** Fails boot on a non-positive interval, but only when the monitor is on. */
    private fun loadStatusSettings(reader: ConfigLoader.EnvReader): MediaServerStatusSettings {
        val defaults = MediaServerStatusSettings()
        val settings =
            MediaServerStatusSettings(
                enabled = reader.getBoolean(defaults.enabled, "MEDIA_SERVER_STATUS_MONITOR_ENABLED"),
                pollIntervalSeconds =
                    reader.getLong(defaults.pollIntervalSeconds, "MEDIA_SERVER_STATUS_POLL_INTERVAL_SECONDS"),
                graceSeconds =
                    reader.getLong(
                        defaults.graceSeconds,
                        "MEDIA_SERVER_STATUS_GRACE_SECONDS",
                        "MEDIA_SERVER_HEALTH_GRACE_SECONDS"
                    ),
                refreshSeconds = reader.getLong(defaults.refreshSeconds, "MEDIA_SERVER_STATUS_REFRESH_SECONDS"),
                recoverySeconds = reader.getLong(defaults.recoverySeconds, "MEDIA_SERVER_STATUS_RECOVERY_SECONDS"),
                pinEnabled = reader.getBoolean(defaults.pinEnabled, "MEDIA_SERVER_STATUS_PIN_ENABLED")
            )
        if (settings.enabled) {
            require(settings.pollIntervalSeconds > 0) { "MEDIA_SERVER_STATUS_POLL_INTERVAL_SECONDS must be positive" }
            require(settings.graceSeconds >= 0) { "MEDIA_SERVER_STATUS_GRACE_SECONDS must not be negative" }
            require(settings.refreshSeconds > 0) { "MEDIA_SERVER_STATUS_REFRESH_SECONDS must be positive" }
            require(settings.recoverySeconds >= 0) { "MEDIA_SERVER_STATUS_RECOVERY_SECONDS must not be negative" }
        }
        return settings
    }
}
