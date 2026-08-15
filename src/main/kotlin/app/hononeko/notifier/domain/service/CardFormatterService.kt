package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import java.util.Locale

object CardFormatterService {
    private const val OPEN_WEBUI_LABEL = "🌐 Open WebUI"

    fun drawProgressBar(
        percent: Int,
        length: Int = 10
    ): String {
        val clamped = percent.coerceIn(0, 100)
        val totalEighths = (clamped * length * 8) / 100
        val fullBlocks = totalEighths / 8
        val remainder = totalEighths % 8
        val subBlocks = charArrayOf(' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉')
        val sb = StringBuilder()
        sb.append("[")
        sb.append("█".repeat(fullBlocks))
        if (fullBlocks < length) {
            if (remainder > 0) {
                sb.append(subBlocks[remainder])
                sb.append("░".repeat(length - fullBlocks - 1))
            } else {
                sb.append("░".repeat(length - fullBlocks))
            }
        }
        sb.append("]")
        return sb.toString()
    }

    fun formatDuration(seconds: Long): String =
        when {
            seconds < 0 || seconds >= 8640000 -> "∞"
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.US, "%.2f TB", tb)
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    fun formatPeers(
        seedsCount: Int,
        seedsTotal: Int,
        peersCount: Int,
        peersTotal: Int
    ): String =
        when {
            seedsTotal > 0 || peersTotal > 0 -> "$seedsCount ($seedsTotal) seeds • $peersCount ($peersTotal) peers"
            else -> "$seedsCount seeds • $peersCount peers"
        }

    fun truncateOverview(
        overview: String?,
        maxLength: Int = 220
    ): String? {
        if (overview.isNullOrBlank()) return null
        val trimmed = overview.trim()
        if (trimmed.length <= maxLength) return trimmed

        val truncated = trimmed.take(maxLength)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > maxLength / 2) {
            "${truncated.take(lastSpace)}..."
        } else {
            "$truncated..."
        }
    }

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

    fun buildGrabInitialCard(
        payload: MediaPayload.ArrGrab,
        webUiUrl: String?
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val fields = mutableListOf<CardField>()
        payload.quality?.let { fields.add(CardField("Quality", it)) }
        payload.releaseGroup?.let { fields.add(CardField("Group", it)) }
        payload.sizeBytes?.let { fields.add(CardField("Size", formatBytes(it))) }
        payload.indexer?.let { fields.add(CardField("Indexer", it)) }

        val actions = mutableListOf<ActionLink>()
        if (!webUiUrl.isNullOrBlank()) {
            actions.add(ActionLink(label = OPEN_WEBUI_LABEL, url = webUiUrl, style = ActionStyle.PRIMARY))
        }

        return NotificationCard(
            title = "⏳ Queueing Download: $titleText",
            subtitle = payload.instanceName ?: payload.source.displayName,
            level = NotificationLevel.PROGRESS,
            fields = fields,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }

    fun buildProgressUpdate(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress,
        webUiUrl: String?
    ): ProgressUpdate {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val sizeFormatted = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalSizeBytes)}"
        val speedFormatted = formatSpeed(progress.downloadSpeedBytesPerSec)
        val etaFormatted = formatDuration(progress.etaSeconds)
        val peersFormatted =
            formatPeers(
                progress.seedsCount,
                progress.seedsTotal,
                progress.peersCount,
                progress.peersTotal
            )

        val actions = mutableListOf<ActionLink>()
        if (!webUiUrl.isNullOrBlank()) {
            actions.add(ActionLink(label = OPEN_WEBUI_LABEL, url = webUiUrl, style = ActionStyle.PRIMARY))
        }

        val stateLabel =
            when (progress.state) {
                TorrentState.DOWNLOADING -> "Downloading"
                TorrentState.STALLED -> "Stalled (No seeds)"
                TorrentState.COMPLETED, TorrentState.UPLOADING -> "Completed"
                TorrentState.ALLOCATING_METADATA -> "Allocating metadata"
                TorrentState.PAUSED -> "Paused"
                TorrentState.QUEUED -> "Queued"
                TorrentState.CHECKING -> "Checking"
                TorrentState.UNKNOWN -> "Active"
            }

        return ProgressUpdate(
            trackingKey = payload.downloadId,
            title = titleText,
            subtitle = payload.instanceName ?: payload.source.displayName,
            percent = progress.progressPercent,
            progressBar = drawProgressBar(progress.progressPercent),
            speedFormatted = speedFormatted,
            etaFormatted = etaFormatted,
            sizeFormatted = sizeFormatted,
            peersInfo = peersFormatted,
            stateText = stateLabel,
            actions = actions
        )
    }

    fun buildCompletionCard(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress,
        webUiUrl: String?
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val fields =
            mutableListOf(
                CardField("Status", "✅ 100% Downloaded"),
                CardField("Total Size", formatBytes(progress.totalSizeBytes))
            )
        payload.quality?.let { fields.add(CardField("Quality", it)) }

        val actions = mutableListOf<ActionLink>()
        if (!webUiUrl.isNullOrBlank()) {
            actions.add(ActionLink(label = OPEN_WEBUI_LABEL, url = webUiUrl, style = ActionStyle.SUCCESS))
        }

        return NotificationCard(
            title = "✅ Download Complete: $titleText",
            subtitle = payload.instanceName ?: payload.source.displayName,
            level = NotificationLevel.SUCCESS,
            fields = fields,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }

    fun buildStalledCard(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress?,
        webUiUrl: String?
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val fields =
            mutableListOf(
                CardField("Status", "⚠️ Download Stalled (0 B/s)"),
                CardField(
                    "Progress",
                    "${progress?.progressPercent ?: 0}% [${drawProgressBar(progress?.progressPercent ?: 0)}]"
                )
            )

        val actions = mutableListOf<ActionLink>()
        if (!webUiUrl.isNullOrBlank()) {
            actions.add(ActionLink(label = OPEN_WEBUI_LABEL, url = webUiUrl, style = ActionStyle.DANGER))
        }

        return NotificationCard(
            title = "⚠️ Download Stalled: $titleText",
            subtitle = payload.instanceName ?: payload.source.displayName,
            level = NotificationLevel.WARNING,
            fields = fields,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }

    fun buildImportCard(payload: MediaPayload.ArrDownload): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val fullTitle =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val title =
            if (payload.isUpgrade) {
                "⬆️ File Upgraded: $fullTitle"
            } else {
                "📁 File Imported: $fullTitle"
            }

        val subtitle =
            if (payload.isUpgrade) {
                "${payload.instanceName ?: payload.source.displayName} • Quality Upgrade"
            } else {
                "${payload.instanceName ?: payload.source.displayName} • Library Import"
            }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution,
                sizeFormatted = payload.sizeBytes?.let { formatBytes(it) }
            )

        val actions = mutableListOf<ActionLink>()
        if (!payload.webUrl.isNullOrBlank()) {
            actions.add(
                ActionLink(
                    label = "📁 Open in ${payload.source.displayName}",
                    url = payload.webUrl,
                    style = ActionStyle.DEFAULT
                )
            )
        }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            overview = truncateOverview(payload.overview),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }

    fun buildAvailableCard(
        payload: MediaPayload,
        mediaServerPort: MediaServerPort? = null
    ): NotificationCard =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> buildPlexCard(payload, mediaServerPort)
            is MediaPayload.JellyfinItemAdded -> buildJellyfinCard(payload, mediaServerPort)
            is MediaPayload.ArrDownload -> buildImportCard(payload)
            is MediaPayload.ArrGrab -> buildGrabInitialCard(payload, null)
        }

    private fun buildPlexCard(
        payload: MediaPayload.PlexLibraryNew,
        mediaServerPort: MediaServerPort?
    ): NotificationCard {
        val fullTitle =
            when {
                !payload.grandParentTitle.isNullOrBlank() -> "${payload.grandParentTitle} - ${payload.title}"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution,
                score = payload.rating?.let { String.format(Locale.US, "%.1f/10", it) },
                duration = payload.durationSeconds?.let { formatDuration(it) }
            )

        val actions = mutableListOf<ActionLink>()
        val directLink = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        if (!directLink.isNullOrBlank()) {
            actions.add(ActionLink(label = "🎬 Watch on Plex", url = directLink, style = ActionStyle.PRIMARY))
        }

        val subtitle =
            payload.instanceName?.let {
                if (it.equals("plex", ignoreCase = true)) "Plex Media Server" else it
            } ?: "Plex Media Server"

        return NotificationCard(
            title = "🍿 Now Available: $fullTitle",
            subtitle = subtitle,
            overview = truncateOverview(payload.summary),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }

    private fun buildJellyfinCard(
        payload: MediaPayload.JellyfinItemAdded,
        mediaServerPort: MediaServerPort?
    ): NotificationCard {
        val fullTitle =
            when {
                !payload.seriesName.isNullOrBlank() -> "${payload.seriesName} - ${payload.title}"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution
            )

        val actions = mutableListOf<ActionLink>()
        val directLink = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        if (!directLink.isNullOrBlank()) {
            actions.add(ActionLink(label = "🍿 Watch on Jellyfin", url = directLink, style = ActionStyle.PRIMARY))
        }

        val subtitle =
            payload.instanceName?.let {
                if (it.equals("jellyfin", ignoreCase = true)) "Jellyfin Media Server" else it
            } ?: "Jellyfin Media Server"

        return NotificationCard(
            title = "🍿 Now Available: $fullTitle",
            subtitle = subtitle,
            overview = truncateOverview(payload.overview),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = payload.posterUrl,
            actions = actions
        )
    }
}
