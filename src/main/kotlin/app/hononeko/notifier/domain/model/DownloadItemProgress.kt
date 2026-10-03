package app.hononeko.notifier.domain.model

/**
 * Client-agnostic progress of a download (a torrent, an NZB job, or a `|`-joined group of them).
 *
 * [id] is the client's identifier for the item: the info hash for torrent clients, the NZO id for SABnzbd.
 * Protocol-specific counters live in [swarm] (BitTorrent) or [usenet] (Usenet); clients leave the other null.
 */
data class DownloadItemProgress(
    val id: String,
    val name: String,
    val progressPercent: Double,
    val progressRatio: Double,
    val downloadSpeedBytesPerSec: Long,
    val uploadSpeedBytesPerSec: Long,
    val etaSeconds: Long,
    val totalSizeBytes: Long,
    val downloadedBytes: Long,
    val state: DownloadState = DownloadState.DOWNLOADING,
    val items: List<DownloadItemProgress> = emptyList(),
    val tags: List<String> = emptyList(),
    val swarm: SwarmStats? = null,
    val usenet: UsenetStats? = null
)

/** BitTorrent swarm counters: connected peers versus the tracker-reported swarm size. */
data class SwarmStats(
    val seedsCount: Int = 0,
    val seedsTotal: Int = 0,
    val peersCount: Int = 0,
    val peersTotal: Int = 0
)

/** Usenet job details: the current post-processing stage (e.g. "Verifying", "Repairing") and missing articles. */
data class UsenetStats(
    val stage: String? = null,
    val missingBlocks: Long = 0
)

enum class DownloadState(
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
