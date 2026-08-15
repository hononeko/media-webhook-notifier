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
    SEERR("Seerr"),
    OVERSEERR("Overseerr"),
    JELLYSEERR("Jellyseerr"),
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
    REQUEST_PENDING,
    REQUEST_APPROVED,
    REQUEST_AUTO_APPROVED,
    REQUEST_AVAILABLE,
    REQUEST_DECLINED,
    REQUEST_FAILED,
    ISSUE_CREATED,
    ISSUE_COMMENT,
    ISSUE_RESOLVED,
    ISSUE_REOPENED,
    TEST,
    UNKNOWN
}
