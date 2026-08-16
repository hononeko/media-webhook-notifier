package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.EventType
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

    var templateEngine: TemplateEngine = TemplateEngine()

    fun drawProgressBar(
        percent: Double,
        length: Int = 10,
        style: String = "default"
    ): String {
        val clamped = percent.coerceIn(0.0, 100.0)
        return when (style.lowercase(Locale.US)) {
            "minimal" -> {
                val filled = kotlin.math.round((clamped * length) / 100.0).toInt()
                val empty = length - filled
                "[${"=".repeat(filled)}${"-".repeat(empty)}]"
            }
            "circles" -> {
                val filled = kotlin.math.round((clamped * length) / 100.0).toInt()
                val empty = length - filled
                "[${"●".repeat(filled)}${"○".repeat(empty)}]"
            }
            else -> {
                val totalEighths = kotlin.math.round((clamped * length * 8) / 100.0).toInt()
                val fullBlocks = totalEighths / 8
                val remainder = totalEighths % 8
                val subBlocks = charArrayOf(' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉')
                val sb = StringBuilder()
                sb.append("[")
                sb.append("█".repeat(fullBlocks.coerceAtMost(length)))
                if (fullBlocks < length) {
                    if (remainder > 0) {
                        sb.append(subBlocks[remainder])
                        sb.append("░".repeat(length - fullBlocks - 1))
                    } else {
                        sb.append("░".repeat(length - fullBlocks))
                    }
                }
                sb.append("]")
                sb.toString()
            }
        }
    }

    fun drawProgressBar(
        percent: Int,
        length: Int = 10
    ): String = drawProgressBar(percent.toDouble(), length)

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
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val context =
            mutableMapOf<String, Any?>(
                "title" to titleText,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "quality" to payload.quality,
                "release_group" to payload.releaseGroup,
                "size" to payload.sizeBytes?.let { formatBytes(it) },
                "total_size" to payload.sizeBytes?.let { formatBytes(it) },
                "indexer" to payload.indexer,
                "webui_url" to webUiUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to (payload.instanceName ?: payload.source.displayName),
                "source_name" to payload.source.displayName,
                "download_id" to payload.downloadId
            )

        val customTpl = engine.getEventTemplate("grab")
        val defaultTitle = "⏳ Queueing Download: $titleText"
        val defaultSubtitle = payload.instanceName ?: payload.source.displayName
        val defaultActions = emptyList<ActionLink>()

        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }

        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }
        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.PROGRESS,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val fields = mutableListOf<CardField>()
        payload.quality?.let { fields.add(CardField("Quality", it)) }
        payload.releaseGroup?.let { fields.add(CardField("Group", it)) }
        payload.sizeBytes?.let { fields.add(CardField("Size", formatBytes(it))) }
        payload.indexer?.let { fields.add(CardField("Indexer", it)) }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = NotificationLevel.PROGRESS,
            fields = fields,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildProgressUpdate(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress,
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
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
        val progressBar =
            drawProgressBar(
                progress.progressPercent,
                engine.theme.progressBarLength,
                engine.theme.progressBarStyle
            )

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

        val context =
            mutableMapOf<String, Any?>(
                "title" to titleText,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "quality" to payload.quality,
                "release_group" to payload.releaseGroup,
                "indexer" to payload.indexer,
                "webui_url" to webUiUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to (payload.instanceName ?: payload.source.displayName),
                "source_name" to payload.source.displayName,
                "download_id" to payload.downloadId,
                "progress_percent" to String.format(Locale.US, "%.2f", progress.progressPercent),
                "progress_bar" to progressBar,
                "speed" to speedFormatted,
                "eta" to etaFormatted,
                "downloaded_size" to formatBytes(progress.downloadedBytes),
                "total_size" to formatBytes(progress.totalSizeBytes),
                "peers_info" to peersFormatted,
                "state" to stateLabel
            )

        val customTpl = engine.getEventTemplate("download_progress")
        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: titleText
        val subtitle =
            customTpl?.subtitle?.let { engine.interpolate(it, context) }
                ?: (payload.instanceName ?: payload.source.displayName)
        val defaultActions = emptyList<ActionLink>()
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        return ProgressUpdate(
            trackingKey = payload.downloadId,
            title = title,
            subtitle = subtitle,
            percent = progress.progressPercent,
            progressBar = progressBar,
            speedFormatted = speedFormatted,
            etaFormatted = etaFormatted,
            sizeFormatted = sizeFormatted,
            peersInfo = peersFormatted,
            stateText = stateLabel,
            customBody = customBody,
            actions = actions
        )
    }

    fun buildCompletionCard(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress,
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val context =
            mutableMapOf<String, Any?>(
                "title" to titleText,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "quality" to payload.quality,
                "release_group" to payload.releaseGroup,
                "total_size" to formatBytes(progress.totalSizeBytes),
                "size" to formatBytes(progress.totalSizeBytes),
                "indexer" to payload.indexer,
                "webui_url" to webUiUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to (payload.instanceName ?: payload.source.displayName),
                "source_name" to payload.source.displayName,
                "download_id" to payload.downloadId
            )

        val customTpl = engine.getEventTemplate("download_complete")
        val defaultTitle = "✅ Download Complete: $titleText"
        val defaultSubtitle = payload.instanceName ?: payload.source.displayName
        val defaultActions = emptyList<ActionLink>()

        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val fields =
            mutableListOf(
                CardField("Status", "✅ 100% Downloaded"),
                CardField("Total Size", formatBytes(progress.totalSizeBytes))
            )
        payload.quality?.let { fields.add(CardField("Quality", it)) }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = NotificationLevel.SUCCESS,
            fields = fields,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildStalledCard(
        payload: MediaPayload.ArrGrab,
        progress: TorrentProgress?,
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val progressVal = progress?.progressPercent ?: 0.0
        val progressBar =
            drawProgressBar(
                progressVal,
                engine.theme.progressBarLength,
                engine.theme.progressBarStyle
            )

        val context =
            mutableMapOf<String, Any?>(
                "title" to titleText,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "quality" to payload.quality,
                "progress_percent" to String.format(Locale.US, "%.2f", progressVal),
                "progress_bar" to progressBar,
                "webui_url" to webUiUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to (payload.instanceName ?: payload.source.displayName),
                "source_name" to payload.source.displayName,
                "download_id" to payload.downloadId
            )

        val customTpl = engine.getEventTemplate("download_stalled")
        val defaultTitle = "⚠️ Download Stalled: $titleText"
        val defaultSubtitle = payload.instanceName ?: payload.source.displayName
        val defaultActions = emptyList<ActionLink>()

        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.WARNING,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val fields =
            mutableListOf(
                CardField("Status", "⚠️ Download Stalled (0 B/s)"),
                CardField(
                    "Progress",
                    String.format(Locale.US, "%.2f%% %s", progressVal, progressBar)
                )
            )

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = NotificationLevel.WARNING,
            fields = fields,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildImportCard(
        payload: MediaPayload.ArrDownload,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val fullTitle =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val defaultTitle =
            if (payload.isUpgrade) {
                "⬆️ File Upgraded: $fullTitle"
            } else {
                "📁 File Imported: $fullTitle"
            }

        val defaultSubtitle =
            if (payload.isUpgrade) {
                "${payload.instanceName ?: payload.source.displayName} • Quality Upgrade"
            } else {
                "${payload.instanceName ?: payload.source.displayName} • Library Import"
            }

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to payload.seriesOrMovieTitle,
                "year" to payload.year?.toString(),
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "quality" to payload.quality,
                "video_codec" to payload.videoCodec,
                "audio_codec" to payload.audioCodec,
                "resolution" to payload.resolution,
                "size" to payload.sizeBytes?.let { formatBytes(it) },
                "total_size" to payload.sizeBytes?.let { formatBytes(it) },
                "is_upgrade" to payload.isUpgrade.toString(),
                "import_action" to if (payload.isUpgrade) "File Upgraded" else "File Imported",
                "import_icon" to if (payload.isUpgrade) "⬆️" else "📁",
                "import_type" to if (payload.isUpgrade) "Quality Upgrade" else "Library Import",
                "overview" to truncateOverview(payload.overview, engine.theme.maxOverviewLength),
                "poster_url" to payload.posterUrl,
                "web_url" to payload.webUrl,
                "instance_name" to (payload.instanceName ?: payload.source.displayName),
                "source_name" to payload.source.displayName
            )

        val customTpl = engine.getEventTemplate("import")
        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val defaultActions = emptyList<ActionLink>()
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution,
                sizeFormatted = payload.sizeBytes?.let { formatBytes(it) }
            )

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            overview = truncateOverview(payload.overview, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildAvailableCard(
        payload: MediaPayload,
        mediaServerPort: MediaServerPort? = null,
        engine: TemplateEngine = templateEngine
    ): NotificationCard =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> buildPlexCard(payload, mediaServerPort, engine)
            is MediaPayload.JellyfinItemAdded -> buildJellyfinCard(payload, mediaServerPort, engine)
            is MediaPayload.ArrDownload -> buildImportCard(payload, engine)
            is MediaPayload.ArrGrab -> buildGrabInitialCard(payload, null, engine)
            is MediaPayload.ServarrHealth -> buildHealthCard(payload, engine)
            is MediaPayload.ServarrManualInteraction -> buildManualInteractionCard(payload, engine)
            is MediaPayload.SeerrEvent -> buildSeerrCard(payload, engine)
        }

    private fun buildPlexCard(
        payload: MediaPayload.PlexLibraryNew,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val fullTitle =
            when {
                !payload.grandParentTitle.isNullOrBlank() -> "${payload.grandParentTitle} - ${payload.title}"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val directLink = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        val defaultSubtitle =
            payload.instanceName?.let {
                if (it.equals("plex", ignoreCase = true)) "Plex Media Server" else it
            } ?: "Plex Media Server"

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to (payload.grandParentTitle ?: payload.title),
                "year" to payload.year?.toString(),
                "overview" to truncateOverview(payload.summary, engine.theme.maxOverviewLength),
                "video_codec" to payload.videoCodec,
                "audio_codec" to payload.audioCodec,
                "resolution" to payload.resolution,
                "rating" to payload.rating?.let { String.format(Locale.US, "%.1f/10", it) },
                "score" to payload.rating?.let { String.format(Locale.US, "%.1f/10", it) },
                "duration" to payload.durationSeconds?.let { formatDuration(it) },
                "deep_link_url" to directLink,
                "media_server_name" to defaultSubtitle,
                "poster_url" to payload.posterUrl,
                "instance_name" to defaultSubtitle,
                "source_name" to "Plex"
            )

        val customTpl = engine.getEventTemplate("media_available")
        val defaultTitle = "🍿 Now Available: $fullTitle"
        val defaultActions =
            if (!directLink.isNullOrBlank()) {
                listOf(ActionLink(label = "🎬 Watch on Plex", url = directLink, style = ActionStyle.PRIMARY))
            } else {
                emptyList()
            }

        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution,
                score = payload.rating?.let { String.format(Locale.US, "%.1f/10", it) },
                duration = payload.durationSeconds?.let { formatDuration(it) }
            )

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            overview = truncateOverview(payload.summary, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    private fun buildJellyfinCard(
        payload: MediaPayload.JellyfinItemAdded,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val fullTitle =
            when {
                !payload.seriesName.isNullOrBlank() -> "${payload.seriesName} - ${payload.title}"
                payload.year != null -> "${payload.title} (${payload.year})"
                else -> payload.title
            }

        val directLink = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        val defaultSubtitle =
            payload.instanceName?.let {
                if (it.equals("jellyfin", ignoreCase = true)) "Jellyfin Media Server" else it
            } ?: "Jellyfin Media Server"

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to (payload.seriesName ?: payload.title),
                "year" to payload.year?.toString(),
                "overview" to truncateOverview(payload.overview, engine.theme.maxOverviewLength),
                "video_codec" to payload.videoCodec,
                "audio_codec" to payload.audioCodec,
                "resolution" to payload.resolution,
                "deep_link_url" to directLink,
                "media_server_name" to defaultSubtitle,
                "poster_url" to payload.posterUrl,
                "instance_name" to defaultSubtitle,
                "source_name" to "Jellyfin"
            )

        val customTpl = engine.getEventTemplate("media_available")
        val defaultTitle = "🍿 Now Available: $fullTitle"
        val defaultActions =
            if (!directLink.isNullOrBlank()) {
                listOf(ActionLink(label = "🍿 Watch on Jellyfin", url = directLink, style = ActionStyle.PRIMARY))
            } else {
                emptyList()
            }

        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution
            )

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            overview = truncateOverview(payload.overview, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildHealthCard(
        payload: MediaPayload.ServarrHealth,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val isRestored =
            payload.eventType == EventType.HEALTH_RESTORED ||
                payload.level.equals("ok", ignoreCase = true) ||
                payload.level.equals("restored", ignoreCase = true)
        val isError =
            payload.level.equals("error", ignoreCase = true) ||
                payload.level.equals("critical", ignoreCase = true)

        val (titlePrefix, level, subtitleSuffix, statusEmoji) =
            when {
                isRestored -> Quadruple("Health Restored", NotificationLevel.SUCCESS, "Health Status", "✅")
                isError -> Quadruple("Health Error", NotificationLevel.ERROR, "Health Alert", "🚨")
                else -> Quadruple("Health Warning", NotificationLevel.WARNING, "Health Warning", "⚠️")
            }

        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val defaultTitle = "$statusEmoji $titlePrefix: $instanceLabel"
        val defaultSubtitle = "$instanceLabel • $subtitleSuffix"

        val context =
            mutableMapOf<String, Any?>(
                "title" to defaultTitle,
                "health_status" to titlePrefix,
                "health_icon" to statusEmoji,
                "health_type" to subtitleSuffix,
                "message" to payload.message,
                "issue_type" to payload.type,
                "wiki_url" to payload.wikiUrl,
                "instance_name" to instanceLabel,
                "source_name" to payload.source.displayName
            )

        val customTpl = engine.getEventTemplate("health")
        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val defaultActions = emptyList<ActionLink>()
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = level,
                customBody = customBody,
                actions = actions
            )
        }

        val fields = mutableListOf<CardField>()
        fields.add(CardField("Message", payload.message, inline = false))
        payload.type?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Issue Type", it, inline = true))
        }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = level,
            fields = fields,
            actions = actions
        )
    }

    fun buildManualInteractionCard(
        payload: MediaPayload.ServarrManualInteraction,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val fullTitle =
            when {
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val defaultTitle = "✋ Manual Import Required: $fullTitle"
        val defaultSubtitle = "$instanceLabel • Manual Intervention"

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
                "episode_range" to epRange,
                "reason" to payload.reason,
                "release_title" to payload.releaseTitle,
                "quality" to payload.quality,
                "size" to payload.sizeBytes?.let { formatBytes(it) },
                "total_size" to payload.sizeBytes?.let { formatBytes(it) },
                "indexer" to payload.indexer,
                "download_client" to payload.downloadClient,
                "client" to payload.downloadClient,
                "web_url" to payload.webUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to instanceLabel,
                "source_name" to payload.source.displayName
            )

        val customTpl = engine.getEventTemplate("manual_interaction")
        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.posterUrl
        val defaultActions =
            if (!payload.webUrl.isNullOrBlank()) {
                listOf(
                    ActionLink(
                        label = "📁 Open in ${payload.source.displayName}",
                        url = payload.webUrl,
                        style = ActionStyle.PRIMARY
                    )
                )
            } else {
                emptyList()
            }
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = NotificationLevel.WARNING,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val fields = mutableListOf<CardField>()
        payload.reason?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Reason", it, inline = false))
        }
        payload.releaseTitle?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Release", it, inline = false))
        }
        payload.quality?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Quality", it, inline = true))
        }
        payload.sizeBytes?.let {
            fields.add(CardField("Size", formatBytes(it), inline = true))
        }
        payload.indexer?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Indexer", it, inline = true))
        }
        payload.downloadClient?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Client", it, inline = true))
        }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = NotificationLevel.WARNING,
            fields = fields,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    fun buildSeerrCard(
        payload: MediaPayload.SeerrEvent,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val appName = payload.instanceName ?: payload.source.displayName
        val (defaultTitle, defaultSubtitle, level, requestIcon, requestAction) =
            when (payload.eventType) {
                EventType.REQUEST_PENDING -> {
                    Quintuple(
                        "🛎️ New Request: ${payload.subject}",
                        "$appName • Request Pending",
                        NotificationLevel.WARNING,
                        "🛎️",
                        "New Request"
                    )
                }
                EventType.REQUEST_APPROVED, EventType.REQUEST_AUTO_APPROVED -> {
                    val approvedType =
                        if (payload.eventType ==
                            EventType.REQUEST_AUTO_APPROVED
                        ) {
                            "Auto-Approved"
                        } else {
                            "Approved"
                        }
                    Quintuple(
                        "✅ Request $approvedType: ${payload.subject}",
                        "$appName • Request $approvedType",
                        NotificationLevel.SUCCESS,
                        "✅",
                        "Request $approvedType"
                    )
                }
                EventType.REQUEST_AVAILABLE -> {
                    Quintuple(
                        "🍿 Request Available: ${payload.subject}",
                        "$appName • Media Available",
                        NotificationLevel.SUCCESS,
                        "🍿",
                        "Request Available"
                    )
                }
                EventType.REQUEST_DECLINED -> {
                    Quintuple(
                        "❌ Request Declined: ${payload.subject}",
                        "$appName • Request Declined",
                        NotificationLevel.ERROR,
                        "❌",
                        "Request Declined"
                    )
                }
                EventType.REQUEST_FAILED -> {
                    Quintuple(
                        "🚨 Request Failed: ${payload.subject}",
                        "$appName • Request Processing Failed",
                        NotificationLevel.ERROR,
                        "🚨",
                        "Request Failed"
                    )
                }
                EventType.ISSUE_CREATED -> {
                    Quintuple(
                        "⚠️ Issue Reported: ${payload.subject}",
                        "$appName • Issue Report",
                        NotificationLevel.WARNING,
                        "⚠️",
                        "Issue Reported"
                    )
                }
                EventType.ISSUE_COMMENT -> {
                    Quintuple(
                        "💬 Issue Comment: ${payload.subject}",
                        "$appName • Issue Update",
                        NotificationLevel.INFO,
                        "💬",
                        "Issue Comment"
                    )
                }
                EventType.ISSUE_RESOLVED -> {
                    Quintuple(
                        "✅ Issue Resolved: ${payload.subject}",
                        "$appName • Issue Resolved",
                        NotificationLevel.SUCCESS,
                        "✅",
                        "Issue Resolved"
                    )
                }
                EventType.ISSUE_REOPENED -> {
                    Quintuple(
                        "⚠️ Issue Reopened: ${payload.subject}",
                        "$appName • Issue Reopened",
                        NotificationLevel.WARNING,
                        "⚠️",
                        "Issue Reopened"
                    )
                }
                else -> {
                    Quintuple(
                        "🔔 ${payload.subject}",
                        "$appName • Notification",
                        NotificationLevel.INFO,
                        "🔔",
                        "Notification"
                    )
                }
            }

        val mediaLabel =
            payload.mediaType?.takeIf { it.isNotBlank() }?.let {
                when (it.lowercase()) {
                    "movie" -> "🎬 Movie"
                    "tv" -> "📺 TV Series"
                    else -> it.replaceFirstChar { c -> c.uppercase() }
                }
            }

        val context =
            mutableMapOf<String, Any?>(
                "title" to defaultTitle,
                "subject" to payload.subject,
                "request_icon" to requestIcon,
                "request_action" to requestAction,
                "request_status" to defaultSubtitle.substringAfter(" • "),
                "requested_by" to payload.requestedByUsername,
                "media_type" to mediaLabel,
                "quality" to if (payload.is4k) "4K UHD" else null,
                "issue_type" to payload.issueType,
                "issue_status" to payload.issueStatus,
                "comment" to payload.commentMessage,
                "message" to payload.message?.takeIf { it != payload.subject },
                "web_url" to payload.webUrl,
                "poster_url" to payload.image,
                "instance_name" to appName,
                "source_name" to payload.source.displayName
            )

        val customTpl = engine.getEventTemplate("request")
        val title = customTpl?.title?.let { engine.interpolate(it, context) } ?: defaultTitle
        val subtitle = customTpl?.subtitle?.let { engine.interpolate(it, context) } ?: defaultSubtitle
        val artworkUrl =
            customTpl?.artworkUrl?.let { engine.interpolate(it, context).ifBlank { null } } ?: payload.image
        val defaultActions =
            if (!payload.webUrl.isNullOrBlank()) {
                listOf(ActionLink(label = "🌐 Open in $appName", url = payload.webUrl, style = ActionStyle.PRIMARY))
            } else {
                emptyList()
            }
        val actions =
            if (customTpl != null && customTpl.actions.isNotEmpty()) {
                engine.renderActions(customTpl.actions, context)
            } else {
                defaultActions
            }
        val customBody = customTpl?.body?.let { engine.interpolateBody(it, context) }

        if (customBody != null) {
            return NotificationCard(
                title = title,
                subtitle = subtitle,
                level = level,
                customBody = customBody,
                artworkUrl = artworkUrl,
                actions = actions
            )
        }

        val fields = mutableListOf<CardField>()
        payload.requestedByUsername?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Requested By", it, inline = true))
        }
        mediaLabel?.let {
            fields.add(CardField("Media Type", it, inline = true))
        }
        if (payload.is4k) {
            fields.add(CardField("Quality", "4K UHD", inline = true))
        }
        payload.issueType?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Issue Type", it, inline = true))
        }
        payload.issueStatus?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Issue Status", it, inline = true))
        }
        payload.commentMessage?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Comment", it, inline = false))
        }
        payload.message?.takeIf { it.isNotBlank() && it != payload.subject }?.let {
            fields.add(CardField("Details", it, inline = false))
        }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            level = level,
            fields = fields,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
