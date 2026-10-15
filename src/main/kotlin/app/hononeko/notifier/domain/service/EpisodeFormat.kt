package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.MediaFormat.drawProgressBar
import app.hononeko.notifier.domain.service.MediaFormat.formatBytes
import app.hononeko.notifier.domain.service.MediaFormat.formatDuration
import app.hononeko.notifier.domain.service.MediaFormat.formatSpeed
import java.util.Locale

/** Episode numbering for cards: `S01E02` ranges, episode labels parsed from release names, per-episode tracks. */
object EpisodeFormat {
    fun formatEpisodeRange(
        seasonNumber: Int?,
        episodes: List<Int>
    ): String? {
        if (seasonNumber == null || episodes.isEmpty()) return null
        val formattedSeason = String.format(Locale.US, "S%02d", seasonNumber)
        val sortedEpisodes = episodes.distinct().sorted()

        return if (sortedEpisodes.size == 1) {
            String.format(Locale.US, "%sE%02d", formattedSeason, sortedEpisodes.first())
        } else if (sortedEpisodes.size > 1 &&
            sortedEpisodes.last() - sortedEpisodes.first() == sortedEpisodes.size - 1
        ) {
            String.format(Locale.US, "%sE%02d-E%02d", formattedSeason, sortedEpisodes.first(), sortedEpisodes.last())
        } else {
            val epList = sortedEpisodes.joinToString(",") { String.format(Locale.US, "E%02d", it) }
            "$formattedSeason$epList"
        }
    }

    private val EPISODE_REGEXES =
        listOf(
            Regex("""(?i)S\d+E(\d+)"""),
            Regex("""(?i)[._ -]E(\d+)"""),
            Regex("""(?i)(\d+)x(\d+)"""),
            Regex("""(?i)Episode\s*(\d+)""")
        )

    private val SEASON_REGEX = Regex("""(?i)[._ -]?S(\d+)""")

    fun extractEpisodeNumber(torrentName: String): Int? {
        for (regex in EPISODE_REGEXES) {
            val match = regex.find(torrentName)
            if (match != null) {
                val groupIdx = if (regex.pattern.contains("x")) 2 else 1
                val epNum = match.groupValues.getOrNull(groupIdx)?.toIntOrNull()
                if (epNum != null) {
                    return epNum
                }
            }
        }
        return null
    }

    fun extractSeasonNumber(torrentName: String): Int? {
        val match = SEASON_REGEX.find(torrentName)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractEpisodeLabel(
        torrentName: String,
        fallbackIndex: Int = 1
    ): String {
        val epNum = extractEpisodeNumber(torrentName)
        return if (epNum != null) {
            "E%02d".format(Locale.US, epNum)
        } else {
            "E%02d".format(Locale.US, fallbackIndex)
        }
    }

    fun formatEpisodeTracks(
        items: List<DownloadItemProgress>,
        engine: TemplateEngine = templateEngine,
        maxItems: Int = 8
    ): String? {
        if (items.isEmpty() || items.size <= 1) return null

        val sortedItems =
            items.sortedWith(
                compareBy(
                    { extractEpisodeNumber(it.name) ?: Int.MAX_VALUE },
                    { it.name }
                )
            )
        val sb = StringBuilder()
        val displayItems = sortedItems.take(maxItems)
        for ((idx, item) in displayItems.withIndex()) {
            val epLabel = extractEpisodeLabel(item.name, idx + 1)
            val miniBar = drawProgressBar(item.progressPercent, 8, engine.theme.progressBarStyle)
            val percentStr = formatTrackPercent(item)
            val statusInfo = formatTrackStatusInfo(item)

            sb
                .append("<code>")
                .append(miniBar)
                .append("</code> <b>")
                .append(percentStr)
                .append("</b> • <b>")
                .append(epLabel)
                .append(":</b> ")
                .append(statusInfo)
                .append("\n")
        }

        if (sortedItems.size > maxItems) {
            val remaining = sortedItems.size - maxItems
            sb.append("<i>...and ").append(remaining).append(" more episodes</i>\n")
        }

        return sb.toString().trimEnd()
    }
}

private fun formatTrackPercent(item: DownloadItemProgress): String =
    if (item.progressPercent >= 100.0 || item.state.isComplete) {
        "100%"
    } else {
        String.format(Locale.US, "%.1f%%", item.progressPercent)
    }

private fun formatTrackStatusInfo(item: DownloadItemProgress): String =
    when {
        item.state.isComplete || item.progressPercent >= 100.0 ->
            MediaFormat.formatBytes(item.totalSizeBytes)
        item.state == DownloadState.DOWNLOADING -> {
            val speed = MediaFormat.formatSpeed(item.downloadSpeedBytesPerSec)
            if (item.etaSeconds > 0) {
                "$speed (ETA: ${MediaFormat.formatDuration(item.etaSeconds)})"
            } else {
                speed
            }
        }
        item.state == DownloadState.STALLED -> "Stalled"
        item.state == DownloadState.QUEUED -> "Queued"
        item.state == DownloadState.PAUSED -> "Paused"
        item.state == DownloadState.ALLOCATING_METADATA -> "Allocating"
        else -> "${MediaFormat.formatBytes(
            item.downloadedBytes
        )} / ${MediaFormat.formatBytes(item.totalSizeBytes)}"
    }
