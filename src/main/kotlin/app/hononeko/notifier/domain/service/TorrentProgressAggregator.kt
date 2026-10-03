package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState

/**
 * Folds the per-torrent progress of a multi-hash download (e.g. individually grabbed episodes)
 * into a single aggregate [TorrentProgress] whose child items are sorted by episode number.
 */
object TorrentProgressAggregator {
    private const val FULL_PERCENT = 100.0

    fun aggregate(
        hash: String,
        torrents: List<TorrentProgress>
    ): TorrentProgress {
        val totalSize = torrents.sumOf { it.totalSizeBytes }
        val totalDownloaded = torrents.sumOf { it.downloadedBytes }

        val aggregateRatio =
            if (totalSize > 0) {
                (totalDownloaded.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
            } else {
                torrents.map { it.progressRatio }.average().coerceIn(0.0, 1.0)
            }

        val childItems =
            torrents.sortedWith(
                compareBy(
                    { CardFormatterService.extractEpisodeNumber(it.name) ?: Int.MAX_VALUE },
                    { it.name }
                )
            )

        return TorrentProgress(
            hash = hash,
            name = childItems.firstOrNull()?.name ?: "Multi-torrent Download",
            progressPercent = (aggregateRatio * FULL_PERCENT).coerceIn(0.0, FULL_PERCENT),
            progressRatio = aggregateRatio,
            downloadSpeedBytesPerSec = torrents.sumOf { it.downloadSpeedBytesPerSec },
            uploadSpeedBytesPerSec = torrents.sumOf { it.uploadSpeedBytesPerSec },
            etaSeconds = torrents.maxOfOrNull { it.etaSeconds } ?: 0L,
            totalSizeBytes = totalSize,
            downloadedBytes = totalDownloaded,
            seedsCount = torrents.maxOfOrNull { it.seedsCount } ?: 0,
            seedsTotal = torrents.maxOfOrNull { it.seedsTotal } ?: 0,
            peersCount = torrents.maxOfOrNull { it.peersCount } ?: 0,
            peersTotal = torrents.maxOfOrNull { it.peersTotal } ?: 0,
            state = resolveAggregateState(torrents.map { it.state }),
            items = childItems,
            tags = torrents.flatMap { it.tags }.distinct()
        )
    }

    private fun resolveAggregateState(states: List<TorrentState>): TorrentState =
        when {
            states.all { it == TorrentState.COMPLETED } -> TorrentState.COMPLETED
            states.any { it == TorrentState.DOWNLOADING } -> TorrentState.DOWNLOADING
            states.any { it == TorrentState.ALLOCATING_METADATA } -> TorrentState.ALLOCATING_METADATA
            states.any { it == TorrentState.CHECKING } -> TorrentState.CHECKING
            states.all { it == TorrentState.STALLED } -> TorrentState.STALLED
            states.all { it == TorrentState.PAUSED } -> TorrentState.PAUSED
            states.all { it == TorrentState.QUEUED } -> TorrentState.QUEUED
            else -> TorrentState.DOWNLOADING
        }
}
