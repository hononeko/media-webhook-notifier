package app.hononeko.notifier.domain.model

enum class AppSource(
    val displayName: String
) {
    SONARR("Sonarr"),
    RADARR("Radarr"),
    LIDARR("Lidarr"),
    READARR("Readarr"),
    BAZARR("Bazarr"),
    PROWLARR("Prowlarr"),
    WHISPARR("Whisparr"),
    PLEX("Plex"),
    JELLYFIN("Jellyfin"),
    UNKNOWN("Unknown")
}

enum class EventType {
    GRAB,
    DOWNLOAD,
    UPGRADE,
    MEDIA_AVAILABLE,
    HEALTH_ISSUE,
    HEALTH_RESTORED,
    MANUAL_INTERACTION,
    TEST,
    UNKNOWN
}
