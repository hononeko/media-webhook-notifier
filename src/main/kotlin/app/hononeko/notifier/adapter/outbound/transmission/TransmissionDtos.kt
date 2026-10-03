package app.hononeko.notifier.adapter.outbound.transmission

import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val FULL_PERCENT = 100.0
private const val ETA_NOT_AVAILABLE = -1L

// tr_torrent_activity values reported in the `status` field
private const val STATUS_STOPPED = 0
private const val STATUS_CHECK_WAIT = 1
private const val STATUS_CHECK = 2
private const val STATUS_DOWNLOAD_WAIT = 3
private const val STATUS_DOWNLOAD = 4
private const val STATUS_SEED_WAIT = 5
private const val STATUS_SEED = 6

@Serializable
internal data class RpcRequest(
    val method: String,
    val arguments: JsonObject
)

@Serializable
internal data class RpcResponse(
    val result: String = "",
    val arguments: JsonObject? = null
)

@Serializable
internal data class TorrentGetArguments(
    val torrents: List<TransmissionTorrentDto> = emptyList()
)

@Serializable
internal data class TransmissionTorrentDto(
    val id: Int = 0,
    val hashString: String = "",
    val name: String? = null,
    val percentDone: Double = 0.0,
    val rateDownload: Long = 0,
    val rateUpload: Long = 0,
    val eta: Long = ETA_NOT_AVAILABLE,
    val status: Int = -1,
    val peersConnected: Int = 0,
    val peersSendingToUs: Int = 0,
    val sizeWhenDone: Long = 0,
    val leftUntilDone: Long = 0,
    val isStalled: Boolean = false,
    val metadataPercentComplete: Double = 1.0,
    val labels: List<String> = emptyList(),
    val trackerStats: List<TransmissionTrackerStatDto> = emptyList()
)

@Serializable
internal data class TransmissionTrackerStatDto(
    val seederCount: Int = -1,
    val leecherCount: Int = -1
)

internal val TransmissionTorrentDto.isDone: Boolean
    get() = percentDone >= 1.0

internal fun TransmissionTorrentDto.toTorrentProgress(): TorrentProgress {
    val ratio = percentDone.coerceIn(0.0, 1.0)
    return TorrentProgress(
        hash = hashString.lowercase(),
        name = name ?: "Unknown",
        progressPercent = ratio * FULL_PERCENT,
        progressRatio = ratio,
        downloadSpeedBytesPerSec = rateDownload,
        uploadSpeedBytesPerSec = rateUpload,
        etaSeconds = eta,
        totalSizeBytes = sizeWhenDone,
        downloadedBytes = (sizeWhenDone - leftUntilDone).coerceAtLeast(0L),
        // Mirrors qBittorrent's split: seeds we pull from vs. every other connected peer.
        seedsCount = peersSendingToUs,
        seedsTotal = trackerStats.maxOfOrNull { it.seederCount }?.coerceAtLeast(0) ?: 0,
        peersCount = (peersConnected - peersSendingToUs).coerceAtLeast(0),
        peersTotal = trackerStats.maxOfOrNull { it.leecherCount }?.coerceAtLeast(0) ?: 0,
        state = resolveState(),
        tags = labels.map { it.trim() }.filter { it.isNotBlank() }
    )
}

internal fun TransmissionTorrentDto.matchesFilter(filter: String): Boolean =
    when (filter.trim().lowercase()) {
        "downloading" -> !isDone
        "completed", "seeding" -> isDone
        "paused", "stopped" -> status == STATUS_STOPPED
        else -> true
    }

private fun TransmissionTorrentDto.resolveState(): TorrentState =
    when (status) {
        STATUS_STOPPED -> if (isDone) TorrentState.COMPLETED else TorrentState.PAUSED
        STATUS_CHECK_WAIT, STATUS_CHECK -> TorrentState.CHECKING
        STATUS_DOWNLOAD_WAIT -> TorrentState.QUEUED
        STATUS_DOWNLOAD ->
            when {
                metadataPercentComplete < 1.0 -> TorrentState.ALLOCATING_METADATA
                isStalled -> TorrentState.STALLED
                else -> TorrentState.DOWNLOADING
            }
        STATUS_SEED_WAIT, STATUS_SEED -> TorrentState.COMPLETED
        else -> TorrentState.UNKNOWN
    }
