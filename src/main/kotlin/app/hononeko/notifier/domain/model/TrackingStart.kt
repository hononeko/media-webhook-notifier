package app.hononeko.notifier.domain.model

/** What a request to track a download led to. */
enum class TrackingStart {
    STARTED,

    /** This instance already runs a tracking loop for the download. */
    ALREADY_TRACKED,

    /** Another instance holds the download's tracking lease, so no card was sent and no loop started. */
    TRACKED_ELSEWHERE
}
