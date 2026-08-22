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
    private const val SCORE_FORMAT = "%.1f/10"

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

    private fun buildArrGrabContext(
        payload: MediaPayload.ArrGrab,
        webUiUrl: String?,
        titleText: String,
        epRange: String?
    ): MutableMap<String, Any?> {
        val releaseName = payload.releaseTitle ?: payload.title
        return mutableMapOf(
            "title" to titleText,
            "series_title" to payload.seriesOrMovieTitle,
            "season" to payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) },
            "episode_range" to epRange,
            "quality" to payload.quality,
            "release_group" to payload.releaseGroup,
            "release_title" to releaseName,
            "release_name" to releaseName,
            "indexer" to payload.indexer,
            "webui_url" to webUiUrl,
            "poster_url" to payload.posterUrl,
            "instance_name" to (payload.instanceName ?: payload.source.displayName),
            "source_name" to payload.source.displayName,
            "download_id" to payload.downloadId
        )
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

        val context = buildArrGrabContext(payload, webUiUrl, titleText, epRange)
        val formattedSize = payload.sizeBytes?.let { formatBytes(it) }
        context["size"] = formattedSize
        context["total_size"] = formattedSize

        val resolved =
            engine.resolveCard(
                eventName = "grab",
                defaultTitle = "⏳ Downloading: $titleText",
                defaultSubtitle = payload.instanceName ?: payload.source.displayName,
                defaultArtworkUrl = payload.posterUrl,
                defaultActions = emptyList(),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.PROGRESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "grab"
            )
        }

        val fields = mutableListOf<CardField>()
        (payload.releaseTitle ?: payload.title).let { fields.add(CardField("Release", it)) }
        payload.quality?.let { fields.add(CardField("Quality", it)) }
        payload.releaseGroup?.let { fields.add(CardField("Group", it)) }
        payload.sizeBytes?.let { fields.add(CardField("Size", formatBytes(it))) }
        payload.indexer?.let { fields.add(CardField("Indexer", it)) }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = NotificationLevel.PROGRESS,
            fields = fields,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = "grab"
        )
    }

    private val EPISODE_REGEXES =
        listOf(
            Regex("""(?i)S\d+E(\d+)"""),
            Regex("""(?i)[._ -]E(\d+)"""),
            Regex("""(?i)(\d+)x(\d+)"""),
            Regex("""(?i)Episode\s*(\d+)""")
        )

    fun extractEpisodeLabel(
        torrentName: String,
        fallbackIndex: Int = 1
    ): String {
        for (regex in EPISODE_REGEXES) {
            val match = regex.find(torrentName)
            if (match != null) {
                val groupIdx = if (regex.pattern.contains("x")) 2 else 1
                val epNum = match.groupValues.getOrNull(groupIdx)?.toIntOrNull()
                if (epNum != null) {
                    return "E%02d".format(Locale.US, epNum)
                }
            }
        }
        return "E%02d".format(Locale.US, fallbackIndex)
    }

    fun formatEpisodeTracks(
        items: List<TorrentProgress>,
        engine: TemplateEngine = templateEngine,
        maxItems: Int = 8
    ): String? {
        if (items.isEmpty() || items.size <= 1) return null

        val sb = StringBuilder()
        val displayItems = items.take(maxItems)
        for ((idx, item) in displayItems.withIndex()) {
            val epLabel = extractEpisodeLabel(item.name, idx + 1)
            val miniBar = drawProgressBar(item.progressPercent, 8, engine.theme.progressBarStyle)
            val percentStr =
                if (item.progressPercent >= 100.0 || item.state.isComplete) {
                    "100%"
                } else {
                    String.format(Locale.US, "%.1f%%", item.progressPercent)
                }

            val statusInfo =
                when {
                    item.state.isComplete || item.progressPercent >= 100.0 ->
                        formatBytes(item.totalSizeBytes)
                    item.state == TorrentState.DOWNLOADING -> {
                        val speed = formatSpeed(item.downloadSpeedBytesPerSec)
                        if (item.etaSeconds > 0) {
                            "$speed (ETA: ${formatDuration(item.etaSeconds)})"
                        } else {
                            speed
                        }
                    }
                    item.state == TorrentState.STALLED -> "Stalled"
                    item.state == TorrentState.QUEUED -> "Queued"
                    item.state == TorrentState.PAUSED -> "Paused"
                    item.state == TorrentState.ALLOCATING_METADATA -> "Allocating"
                    else -> "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalSizeBytes)}"
                }

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

        if (items.size > maxItems) {
            val remaining = items.size - maxItems
            sb.append("<i>...and ").append(remaining).append(" more episodes</i>\n")
        }

        return sb.toString().trimEnd()
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

        val episodeTracks = formatEpisodeTracks(progress.items, engine)
        val releaseName = progress.name.ifBlank { payload.releaseTitle ?: payload.title }
        val context = buildArrGrabContext(payload, webUiUrl, titleText, epRange)
        context["release_title"] = releaseName
        context["release_name"] = releaseName
        context["torrent_name"] = progress.name
        context["progress_percent"] = String.format(Locale.US, "%.2f", progress.progressPercent)
        context["progress_bar"] = progressBar
        context["speed"] = speedFormatted
        context["eta"] = etaFormatted
        context["downloaded_size"] = formatBytes(progress.downloadedBytes)
        context["total_size"] = formatBytes(progress.totalSizeBytes)
        context["peers_info"] = peersFormatted
        context["state"] = stateLabel
        context["episode_tracks"] = episodeTracks
        context["has_multiple_tracks"] = progress.items.size > 1
        context["episode_count"] = progress.items.size

        val resolved =
            engine.resolveProgress(
                eventName = "download_progress",
                defaultTitle = "⏳ Downloading: $titleText",
                defaultSubtitle = payload.instanceName ?: payload.source.displayName,
                defaultActions = emptyList(),
                context = context
            )

        return ProgressUpdate(
            trackingKey = payload.downloadId,
            title = resolved.title,
            subtitle = resolved.subtitle,
            percent = progress.progressPercent,
            progressBar = progressBar,
            speedFormatted = speedFormatted,
            etaFormatted = etaFormatted,
            sizeFormatted = sizeFormatted,
            peersInfo = peersFormatted,
            stateText = stateLabel,
            customBody = resolved.customBody,
            episodeTracks = episodeTracks,
            actions = resolved.actions
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

        val releaseName = progress.name.ifBlank { payload.releaseTitle ?: payload.title }
        val context = buildArrGrabContext(payload, webUiUrl, titleText, epRange)
        val totalSizeFormatted = formatBytes(progress.totalSizeBytes)
        context["total_size"] = totalSizeFormatted
        context["size"] = totalSizeFormatted
        context["release_title"] = releaseName
        context["release_name"] = releaseName
        context["torrent_name"] = progress.name
        context["episode_count"] = progress.items.size

        val resolved =
            engine.resolveCard(
                eventName = "download_complete",
                defaultTitle = "✅ Download Complete: $titleText",
                defaultSubtitle = payload.instanceName ?: payload.source.displayName,
                defaultArtworkUrl = payload.posterUrl,
                defaultActions = emptyList(),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "download_complete"
            )
        }

        val fields = mutableListOf<CardField>()
        fields.add(CardField("Release", releaseName))
        if (progress.items.size > 1) {
            val epSummary = progress.items.joinToString(", ") { extractEpisodeLabel(it.name) }
            fields.add(CardField("Episodes", "$epSummary (${progress.items.size} episodes)"))
        }
        fields.add(CardField("Status", "✅ 100% Downloaded"))
        fields.add(CardField("Total Size", formatBytes(progress.totalSizeBytes)))
        payload.quality?.let { fields.add(CardField("Quality", it)) }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = NotificationLevel.SUCCESS,
            fields = fields,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = "download_complete"
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

        val releaseName = progress?.name?.ifBlank { null } ?: payload.releaseTitle ?: payload.title
        val context = buildArrGrabContext(payload, webUiUrl, titleText, epRange)
        context["progress_percent"] = String.format(Locale.US, "%.2f", progressVal)
        context["progress_bar"] = progressBar
        context["release_title"] = releaseName
        context["release_name"] = releaseName
        context["torrent_name"] = progress?.name

        val resolved =
            engine.resolveCard(
                eventName = "download_stalled",
                defaultTitle = "⚠️ Download Stalled: $titleText",
                defaultSubtitle = payload.instanceName ?: payload.source.displayName,
                defaultArtworkUrl = payload.posterUrl,
                defaultActions = emptyList(),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.WARNING,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "download_stalled"
            )
        }

        val fields =
            mutableListOf(
                CardField("Release", releaseName),
                CardField("Status", "⚠️ Download Stalled (0 B/s)"),
                CardField(
                    "Progress",
                    "${String.format(Locale.US, "%.2f", progressVal)}% $progressBar"
                )
            )
        payload.quality?.let { fields.add(CardField("Quality", it)) }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = NotificationLevel.WARNING,
            fields = fields,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = "download_stalled"
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

        val specsSummary =
            listOfNotNull(
                payload.quality ?: payload.resolution,
                payload.videoCodec,
                payload.audioCodec
            ).joinToString(" • ")

        val mediaSpecs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.quality ?: payload.resolution
            )

        val formattedSeason = payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) }
        val firstEpisode = payload.episodeNumbers.firstOrNull()
        val formattedEpisode = firstEpisode?.let { String.format(Locale.US, "%02d", it) }

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to payload.seriesOrMovieTitle.ifBlank { payload.title },
                "year" to payload.year?.toString(),
                "season" to formattedSeason,
                "season_number" to payload.seasonNumber?.toString(),
                "episode" to formattedEpisode,
                "episode_number" to firstEpisode?.toString(),
                "episode_title" to payload.episodeTitle,
                "episode_name" to payload.episodeTitle,
                "episode_range" to epRange,
                "quality" to payload.quality,
                "specs" to specsSummary,
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

        val resolved =
            engine.resolveCard(
                eventName = "import",
                defaultTitle = defaultTitle,
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = payload.posterUrl,
                defaultActions = emptyList(),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "import"
            )
        }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = truncateOverview(payload.overview, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = mediaSpecs,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = "import"
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

    private fun buildMediaServerCard(
        sourceName: String,
        actionEmoji: String,
        fullTitle: String,
        itemTitle: String,
        seriesTitle: String,
        seasonNumber: Int?,
        seasonLabel: String?,
        episodeNumber: Int?,
        mediaType: String,
        year: Int?,
        overview: String?,
        posterUrl: String?,
        artworkBytes: ByteArray? = null,
        videoCodec: String?,
        audioCodec: String?,
        resolution: String?,
        rating: Double?,
        durationSeconds: Long?,
        deepLinkUrl: String?,
        instanceName: String?,
        engine: TemplateEngine
    ): NotificationCard {
        val mediaServerName =
            instanceName?.takeUnless { it.equals(sourceName, ignoreCase = true) }
                ?: sourceName

        val defaultSubtitle: String? = null

        val specsSummary =
            listOfNotNull(
                resolution,
                videoCodec,
                audioCodec
            ).joinToString(" • ")

        val formattedSeason = seasonNumber?.let { String.format(Locale.US, "%02d", it) }
        val formattedEpisode = episodeNumber?.let { String.format(Locale.US, "%02d", it) }

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "item_title" to itemTitle,
                "series_title" to seriesTitle.ifBlank { itemTitle },
                "season" to formattedSeason,
                "season_number" to seasonNumber?.toString(),
                "season_title" to (seasonLabel ?: formattedSeason?.let { "Season $it" }),
                "episode" to formattedEpisode,
                "episode_number" to episodeNumber?.toString(),
                "episode_title" to itemTitle,
                "media_type" to mediaType,
                "year" to year?.toString(),
                "specs" to specsSummary,
                "overview" to truncateOverview(overview, engine.theme.maxOverviewLength),
                "video_codec" to videoCodec,
                "audio_codec" to audioCodec,
                "resolution" to resolution,
                "rating" to rating?.let { String.format(Locale.US, SCORE_FORMAT, it) },
                "score" to rating?.let { String.format(Locale.US, SCORE_FORMAT, it) },
                "duration" to durationSeconds?.let { formatDuration(it) },
                "deep_link_url" to deepLinkUrl,
                "media_server_name" to mediaServerName,
                "poster_url" to posterUrl,
                "instance_name" to mediaServerName,
                "source_name" to sourceName
            )

        val defaultActions =
            if (!deepLinkUrl.isNullOrBlank()) {
                listOf(
                    ActionLink(
                        label = "$actionEmoji Watch on $sourceName",
                        url = deepLinkUrl,
                        style = ActionStyle.PRIMARY
                    )
                )
            } else {
                emptyList()
            }

        val resolved =
            engine.resolveCard(
                eventName = "media_available",
                defaultTitle = "🍿 $fullTitle now available on $mediaServerName",
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = posterUrl,
                defaultActions = defaultActions,
                context = context
            )

        val finalArtworkBytes = if (resolved.imageEmbedEnabled) artworkBytes else null

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                artworkBytes = finalArtworkBytes,
                actions = resolved.actions,
                eventType = "media_available"
            )
        }

        val specs =
            MediaSpecs(
                video = videoCodec,
                audio = audioCodec,
                resolution = resolution,
                score = rating?.let { String.format(Locale.US, SCORE_FORMAT, it) },
                duration = durationSeconds?.let { formatDuration(it) }
            )

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = truncateOverview(overview, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = specs,
            artworkUrl = resolved.artworkUrl,
            artworkBytes = finalArtworkBytes,
            actions = resolved.actions,
            eventType = "media_available"
        )
    }

    private fun resolveSeasonLabel(
        isSeason: Boolean,
        title: String,
        seasonNumber: Int?
    ): String? =
        when {
            isSeason && title.startsWith("Season", ignoreCase = true) -> title
            isSeason && seasonNumber != null -> "Season $seasonNumber"
            else -> null
        }

    private fun formatEpisodeCode(
        seasonNumber: Int,
        episodeNumber: Int
    ): String = "S%02dE%02d".format(Locale.US, seasonNumber, episodeNumber)

    private fun formatMediaServerFullTitle(
        isSeason: Boolean,
        isEpisode: Boolean,
        seriesTitle: String,
        itemTitle: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        seasonLabel: String?,
        year: Int?
    ): String =
        when {
            isSeason && seriesTitle.isNotBlank() && seasonLabel != null -> "$seriesTitle - $seasonLabel"
            isSeason && seriesTitle.isNotBlank() -> "$seriesTitle - $itemTitle"
            isEpisode &&
                seriesTitle.isNotBlank() &&
                seasonNumber != null &&
                episodeNumber != null -> {
                val epCode = formatEpisodeCode(seasonNumber, episodeNumber)
                if (itemTitle.isNotBlank() &&
                    !itemTitle.equals(epCode, ignoreCase = true) &&
                    !itemTitle.startsWith("Episode ", ignoreCase = true)
                ) {
                    "$seriesTitle - $epCode - $itemTitle"
                } else {
                    "$seriesTitle - $epCode"
                }
            }
            isEpisode && seriesTitle.isNotBlank() -> "$seriesTitle - $itemTitle"
            seriesTitle.isNotBlank() && seriesTitle != itemTitle -> "$seriesTitle - $itemTitle"
            year != null -> "$itemTitle ($year)"
            else -> itemTitle
        }

    private fun resolveMediaType(
        isSeason: Boolean,
        isEpisode: Boolean,
        rawType: String?,
        seriesTitle: String,
        year: Int?
    ): String =
        when {
            isSeason -> "season"
            isEpisode -> "episode"
            rawType == "movie" || (seriesTitle.isBlank() && year != null) -> "movie"
            rawType == "show" || rawType == "series" -> "show"
            else -> rawType ?: "media"
        }

    private fun buildPlexCard(
        payload: MediaPayload.PlexLibraryNew,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val mediaType = payload.mediaType?.lowercase()
        val isSeason =
            mediaType == "season" ||
                (
                    payload.parentTitle != null &&
                        payload.grandParentTitle == null &&
                        (payload.title.startsWith("Season", ignoreCase = true) || payload.seasonNumber != null)
                )
        val isEpisode =
            mediaType == "episode" ||
                payload.grandParentTitle != null ||
                (payload.parentTitle != null && payload.episodeNumber != null)

        val seriesTitle =
            when {
                isSeason -> payload.parentTitle ?: ""
                isEpisode -> payload.grandParentTitle ?: payload.parentTitle ?: ""
                else -> ""
            }

        val seasonLabel = resolveSeasonLabel(isSeason, payload.title, payload.seasonNumber)
        val fullTitle =
            formatMediaServerFullTitle(
                isSeason = isSeason,
                isEpisode = isEpisode,
                seriesTitle = seriesTitle,
                itemTitle = payload.title,
                seasonNumber = payload.seasonNumber,
                episodeNumber = payload.episodeNumber,
                seasonLabel = seasonLabel,
                year = payload.year
            )

        val effectivePosterUrl = payload.posterUrl ?: payload.parentPosterUrl ?: payload.grandparentPosterUrl
        val effectiveDuration = if (isSeason) null else payload.durationSeconds
        val resolvedMediaType = resolveMediaType(isSeason, isEpisode, mediaType, seriesTitle, payload.year)

        return buildMediaServerCard(
            sourceName = "Plex",
            actionEmoji = "🎬",
            fullTitle = fullTitle,
            itemTitle = payload.title,
            seriesTitle = seriesTitle.ifBlank { payload.title },
            seasonNumber = payload.seasonNumber,
            seasonLabel = seasonLabel,
            episodeNumber = payload.episodeNumber,
            mediaType = resolvedMediaType,
            year = payload.year,
            overview = payload.summary,
            posterUrl = effectivePosterUrl,
            artworkBytes = payload.artworkBytes,
            videoCodec = payload.videoCodec,
            audioCodec = payload.audioCodec,
            resolution = payload.resolution,
            rating = payload.rating,
            durationSeconds = effectiveDuration,
            deepLinkUrl = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload),
            instanceName = payload.instanceName,
            engine = engine
        )
    }

    private fun buildJellyfinCard(
        payload: MediaPayload.JellyfinItemAdded,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val type = payload.mediaType?.lowercase()
        val isSeason =
            type == "season" ||
                (payload.seriesName != null && payload.seasonNumber != null && payload.episodeNumber == null)
        val isEpisode =
            type == "episode" ||
                (payload.seriesName != null && payload.episodeNumber != null)

        val seriesTitle = payload.seriesName ?: ""
        val seasonLabel = resolveSeasonLabel(isSeason, payload.title, payload.seasonNumber)
        val fullTitle =
            formatMediaServerFullTitle(
                isSeason = isSeason,
                isEpisode = isEpisode,
                seriesTitle = seriesTitle,
                itemTitle = payload.title,
                seasonNumber = payload.seasonNumber,
                episodeNumber = payload.episodeNumber,
                seasonLabel = seasonLabel,
                year = payload.year
            )

        val resolvedMediaType = resolveMediaType(isSeason, isEpisode, type, seriesTitle, payload.year)

        return buildMediaServerCard(
            sourceName = "Jellyfin",
            actionEmoji = "🍿",
            fullTitle = fullTitle,
            itemTitle = payload.title,
            seriesTitle = seriesTitle.ifBlank { payload.title },
            seasonNumber = payload.seasonNumber,
            seasonLabel = seasonLabel,
            episodeNumber = payload.episodeNumber,
            mediaType = resolvedMediaType,
            year = payload.year,
            overview = payload.overview,
            posterUrl = payload.posterUrl,
            artworkBytes = null,
            videoCodec = payload.videoCodec,
            audioCodec = payload.audioCodec,
            resolution = payload.resolution,
            rating = null,
            durationSeconds = null,
            deepLinkUrl = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload),
            instanceName = payload.instanceName,
            engine = engine
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

        val resolved =
            engine.resolveCard(
                eventName = "health",
                defaultTitle = defaultTitle,
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = null,
                defaultActions = emptyList(),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = level,
                customBody = resolved.customBody,
                actions = resolved.actions,
                eventType = "health"
            )
        }

        val fields = mutableListOf<CardField>()
        fields.add(CardField("Message", payload.message, inline = false))
        payload.type?.takeIf { it.isNotBlank() }?.let {
            fields.add(CardField("Issue Type", it, inline = true))
        }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = level,
            fields = fields,
            actions = resolved.actions,
            eventType = "health"
        )
    }

    fun buildManualInteractionCard(
        payload: MediaPayload.ServarrManualInteraction,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val fullTitle =
            when {
                epRange != null && !payload.episodeTitle.isNullOrBlank() && payload.episodeNumbers.size == 1 -> {
                    val epTitle = payload.episodeTitle.trim()
                    if (!epTitle.equals(epRange, ignoreCase = true) &&
                        !epTitle.startsWith("Episode ", ignoreCase = true)
                    ) {
                        "${payload.seriesOrMovieTitle} - $epRange - $epTitle"
                    } else {
                        "${payload.seriesOrMovieTitle} - $epRange"
                    }
                }
                epRange != null -> "${payload.seriesOrMovieTitle} ($epRange)"
                else -> payload.title
            }

        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val defaultTitle = "✋ Manual Import Required: $fullTitle"
        val defaultSubtitle = "$instanceLabel • Manual Intervention"

        val formattedSeason = payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) }
        val firstEpisode = payload.episodeNumbers.firstOrNull()
        val formattedEpisode = firstEpisode?.let { String.format(Locale.US, "%02d", it) }

        val context =
            mutableMapOf<String, Any?>(
                "title" to fullTitle,
                "series_title" to payload.seriesOrMovieTitle,
                "season" to formattedSeason,
                "season_number" to payload.seasonNumber?.toString(),
                "episode" to formattedEpisode,
                "episode_number" to firstEpisode?.toString(),
                "episode_title" to payload.episodeTitle,
                "episode_name" to payload.episodeTitle,
                "episode_range" to epRange,
                "reason" to payload.reason,
                "release_title" to payload.releaseTitle,
                "release_name" to payload.releaseTitle,
                "quality" to payload.quality,
                "size" to payload.sizeBytes?.let { formatBytes(it) },
                "total_size" to payload.sizeBytes?.let { formatBytes(it) },
                "indexer" to payload.indexer,
                "download_client" to payload.downloadClient,
                "client" to payload.downloadClient,
                "download_id" to payload.downloadId,
                "web_url" to payload.webUrl,
                "poster_url" to payload.posterUrl,
                "instance_name" to instanceLabel,
                "source_name" to payload.source.displayName
            )

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

        val resolved =
            engine.resolveCard(
                eventName = "manual_interaction",
                defaultTitle = defaultTitle,
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = payload.posterUrl,
                defaultActions = defaultActions,
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.WARNING,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "manual_interaction"
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
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = NotificationLevel.WARNING,
            fields = fields,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = "manual_interaction"
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

        val isIssue =
            payload.eventType in
                setOf(
                    EventType.ISSUE_CREATED,
                    EventType.ISSUE_COMMENT,
                    EventType.ISSUE_RESOLVED,
                    EventType.ISSUE_REOPENED
                )
        val eventName = if (isIssue) "issue" else "request"

        val defaultActions =
            if (!payload.webUrl.isNullOrBlank()) {
                val actionLabel = if (isIssue) "⚠️ View Issue in $appName" else "🌐 Open in $appName"
                listOf(ActionLink(label = actionLabel, url = payload.webUrl, style = ActionStyle.PRIMARY))
            } else {
                emptyList()
            }

        val resolved =
            engine.resolveCard(
                eventName = eventName,
                defaultTitle = defaultTitle,
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = payload.image,
                defaultActions = defaultActions,
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = level,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = eventName
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
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = level,
            fields = fields,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = eventName
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
