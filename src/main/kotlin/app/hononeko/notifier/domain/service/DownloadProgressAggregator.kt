package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.SwarmStats
import app.hononeko.notifier.domain.model.UsenetStats

/**
 * Folds the per-item progress of a multi-item download (e.g. individually grabbed episodes)
 * into a single aggregate [DownloadItemProgress] whose child items are sorted by episode number.
 */
object DownloadProgressAggregator {
    private const val FULL_PERCENT = 100.0

    fun aggregate(
        id: String,
        items: List<DownloadItemProgress>
    ): DownloadItemProgress {
        val totalSize = items.sumOf { it.totalSizeBytes }
        val totalDownloaded = items.sumOf { it.downloadedBytes }

        val aggregateRatio =
            if (totalSize > 0) {
                (totalDownloaded.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
            } else {
                items.map { it.progressRatio }.average().coerceIn(0.0, 1.0)
            }

        val childItems =
            items.sortedWith(
                compareBy(
                    { CardFormatterService.extractEpisodeNumber(it.name) ?: Int.MAX_VALUE },
                    { it.name }
                )
            )

        return DownloadItemProgress(
            id = id,
            name = childItems.firstOrNull()?.name ?: "Multi-item Download",
            progressPercent = (aggregateRatio * FULL_PERCENT).coerceIn(0.0, FULL_PERCENT),
            progressRatio = aggregateRatio,
            downloadSpeedBytesPerSec = items.sumOf { it.downloadSpeedBytesPerSec },
            uploadSpeedBytesPerSec = items.sumOf { it.uploadSpeedBytesPerSec },
            etaSeconds = items.maxOfOrNull { it.etaSeconds } ?: 0L,
            totalSizeBytes = totalSize,
            downloadedBytes = totalDownloaded,
            state = resolveAggregateState(items.map { it.state }),
            items = childItems,
            tags = items.flatMap { it.tags }.distinct(),
            swarm = aggregateSwarm(items.mapNotNull { it.swarm }),
            usenet = aggregateUsenet(childItems.mapNotNull { it.usenet })
        )
    }

    private fun aggregateSwarm(stats: List<SwarmStats>): SwarmStats? =
        stats.takeIf { it.isNotEmpty() }?.let {
            SwarmStats(
                seedsCount = it.maxOf { s -> s.seedsCount },
                seedsTotal = it.maxOf { s -> s.seedsTotal },
                peersCount = it.maxOf { s -> s.peersCount },
                peersTotal = it.maxOf { s -> s.peersTotal }
            )
        }

    private fun aggregateUsenet(stats: List<UsenetStats>): UsenetStats? =
        stats.takeIf { it.isNotEmpty() }?.let {
            UsenetStats(
                stage = it.firstNotNullOfOrNull { s -> s.stage },
                missingBlocks = it.sumOf { s -> s.missingBlocks }
            )
        }

    private fun resolveAggregateState(states: List<DownloadState>): DownloadState =
        when {
            states.all { it == DownloadState.COMPLETED } -> DownloadState.COMPLETED
            states.any { it == DownloadState.DOWNLOADING } -> DownloadState.DOWNLOADING
            states.any { it == DownloadState.ALLOCATING_METADATA } -> DownloadState.ALLOCATING_METADATA
            states.any { it == DownloadState.CHECKING } -> DownloadState.CHECKING
            states.all { it == DownloadState.STALLED } -> DownloadState.STALLED
            states.all { it == DownloadState.PAUSED } -> DownloadState.PAUSED
            states.all { it == DownloadState.QUEUED } -> DownloadState.QUEUED
            else -> DownloadState.DOWNLOADING
        }
}
