package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.EpisodeFormat.extractEpisodeLabel
import app.hononeko.notifier.domain.service.EpisodeFormat.formatEpisodeRange
import app.hononeko.notifier.domain.service.EpisodeFormat.formatEpisodeTracks
import app.hononeko.notifier.domain.service.MediaFormat.drawProgressBar
import app.hononeko.notifier.domain.service.MediaFormat.formatBytes
import app.hononeko.notifier.domain.service.MediaFormat.formatDuration
import app.hononeko.notifier.domain.service.MediaFormat.formatPeers
import app.hononeko.notifier.domain.service.MediaFormat.formatSpeed
import java.util.Locale

/** Cards for a download: the grab card, its live progress updates, completion and stall. */
object DownloadCards {
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
                defaults =
                    DefaultCardSpec(
                        title = "⏳ Downloading: $titleText",
                        subtitle = payload.instanceName ?: payload.source.displayName,
                        artworkUrl = payload.posterUrl,
                        actions = emptyList(),
                        silent = true
                    ),
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
                eventType = "grab",
                silent = resolved.silent
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
            eventType = "grab",
            silent = resolved.silent
        )
    }

    fun buildProgressUpdate(
        payload: MediaPayload.ArrGrab,
        progress: DownloadItemProgress,
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
        // Only swarm-based (torrent) clients report peers; the empty value suppresses the template line otherwise.
        val peersFormatted =
            progress.swarm?.let { formatPeers(it.seedsCount, it.seedsTotal, it.peersCount, it.peersTotal) }.orEmpty()
        val progressBar =
            drawProgressBar(
                progress.progressPercent,
                engine.theme.progressBarLength,
                engine.theme.progressBarStyle
            )

        val stateLabel =
            when (progress.state) {
                DownloadState.DOWNLOADING -> "Downloading"
                DownloadState.STALLED -> "Stalled (No seeds)"
                DownloadState.COMPLETED, DownloadState.UPLOADING -> "Completed"
                DownloadState.ALLOCATING_METADATA -> "Allocating metadata"
                DownloadState.PAUSED -> "Paused"
                DownloadState.QUEUED -> "Queued"
                DownloadState.CHECKING -> "Checking"
                DownloadState.UNKNOWN -> "Active"
            }

        val episodeTracks = formatEpisodeTracks(progress.items, engine)
        val releaseName =
            if (progress.items.size > 1) {
                payload.releaseTitle ?: payload.title
            } else {
                progress.name.ifBlank { payload.releaseTitle ?: payload.title }
            }
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
        context["size_formatted"] = sizeFormatted
        context["size"] = sizeFormatted
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
        progress: DownloadItemProgress,
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
        context["size_formatted"] = totalSizeFormatted
        context["release_title"] = releaseName
        context["release_name"] = releaseName
        context["torrent_name"] = progress.name
        context["episode_count"] = progress.items.size

        val resolved =
            engine.resolveCard(
                eventName = "download_complete",
                defaults =
                    DefaultCardSpec(
                        title = "✅ Download Complete: $titleText",
                        subtitle = payload.instanceName ?: payload.source.displayName,
                        artworkUrl = payload.posterUrl,
                        actions = emptyList()
                    ),
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
                eventType = "download_complete",
                silent = resolved.silent
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
            eventType = "download_complete",
            silent = resolved.silent
        )
    }

    fun buildStalledCard(
        payload: MediaPayload.ArrGrab,
        progress: DownloadItemProgress?,
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard = renderStalledCard(payload, progress, webUiUrl, engine).withAcknowledgeIf(true)

    private fun renderStalledCard(
        payload: MediaPayload.ArrGrab,
        progress: DownloadItemProgress?,
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
        val totalSize = progress?.totalSizeBytes?.let { formatBytes(it) } ?: "Unknown"
        context["total_size"] = totalSize
        context["size"] = totalSize
        context["size_formatted"] = totalSize
        context["release_title"] = releaseName
        context["release_name"] = releaseName
        context["torrent_name"] = progress?.name

        val resolved =
            engine.resolveCard(
                eventName = "download_stalled",
                defaults =
                    DefaultCardSpec(
                        title = "⚠️ Download Stalled: $titleText",
                        subtitle = payload.instanceName ?: payload.source.displayName,
                        artworkUrl = payload.posterUrl,
                        actions = emptyList()
                    ),
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
                eventType = "download_stalled",
                silent = resolved.silent
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
            eventType = "download_stalled",
            silent = resolved.silent
        )
    }
}
