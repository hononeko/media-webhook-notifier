package app.hononeko.notifier.domain.model

import kotlinx.coroutines.Job

enum class TrackerStatus {
    INITIALIZING,
    TRACKING,
    STALLED,
    COMPLETING,
    CANCELLED
}

data class TrackerSnapshot(
    val hash: String,
    val title: String,
    val source: AppSource,
    val handle: NotificationHandle,
    val isPhoto: Boolean,
    val startedAtMillis: Long,
    val lastProgressPercent: Double,
    val downloadSpeedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: TrackerStatus
)

class ActiveTrackerSession(
    val hash: String,
    val payload: MediaPayload.ArrGrab,
    val handle: NotificationHandle,
    val isPhoto: Boolean,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val job: Job
) {
    @Volatile var status: TrackerStatus = TrackerStatus.TRACKING

    @Volatile var lastProgress: TorrentProgress? = null

    @Volatile var stalledDurationSeconds: Long = 0L

    fun toSnapshot(): TrackerSnapshot =
        TrackerSnapshot(
            hash = hash,
            title = payload.seriesOrMovieTitle.ifBlank { payload.title },
            source = payload.source,
            handle = handle,
            isPhoto = isPhoto,
            startedAtMillis = startedAtMillis,
            lastProgressPercent = lastProgress?.progressPercent ?: 0.0,
            downloadSpeedBytesPerSec = lastProgress?.downloadSpeedBytesPerSec ?: 0L,
            etaSeconds = lastProgress?.etaSeconds ?: 0L,
            status = status
        )
}
