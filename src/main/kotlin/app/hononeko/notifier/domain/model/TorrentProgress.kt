package app.hononeko.notifier.domain.model

data class TorrentProgress(
    val hash: String,
    val name: String,
    val progressPercent: Double,
    val progressRatio: Double,
    val downloadSpeedBytesPerSec: Long,
    val uploadSpeedBytesPerSec: Long,
    val etaSeconds: Long,
    val totalSizeBytes: Long,
    val downloadedBytes: Long,
    val seedsCount: Int = 0,
    val seedsTotal: Int = 0,
    val peersCount: Int = 0,
    val peersTotal: Int = 0,
    val state: TorrentState = TorrentState.DOWNLOADING
)

enum class TorrentState(
    val isComplete: Boolean,
    val isStalled: Boolean
) {
    DOWNLOADING(isComplete = false, isStalled = false),
    STALLED(isComplete = false, isStalled = true),
    COMPLETED(isComplete = true, isStalled = false),
    UPLOADING(isComplete = true, isStalled = false),
    PAUSED(isComplete = false, isStalled = false),
    QUEUED(isComplete = false, isStalled = false),
    ALLOCATING_METADATA(isComplete = false, isStalled = false),
    CHECKING(isComplete = false, isStalled = false),
    UNKNOWN(isComplete = false, isStalled = false)
}
