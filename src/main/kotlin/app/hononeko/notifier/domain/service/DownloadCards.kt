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
        val titleText = downloadTitle(payload, epRange)

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

        return resolved.toCard(NotificationLevel.PROGRESS, "grab") { copy(fields = grabFields(payload)) }
    }

    private fun grabFields(payload: MediaPayload.ArrGrab): List<CardField> =
        listOfNotNull(
            CardField("Release", payload.releaseTitle ?: payload.title),
            payload.quality?.let { CardField("Quality", it) },
            payload.releaseGroup?.let { CardField("Group", it) },
            payload.sizeBytes?.let { CardField("Size", formatBytes(it)) },
            payload.indexer?.let { CardField("Indexer", it) }
        )

    fun buildProgressUpdate(
        payload: MediaPayload.ArrGrab,
        progress: DownloadItemProgress,
        webUiUrl: String?,
        engine: TemplateEngine = templateEngine
    ): ProgressUpdate {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val titleText = downloadTitle(payload, epRange)
        val shown = formatProgress(progress, engine)
        val episodeTracks = formatEpisodeTracks(progress.items, engine)
        val releaseName =
            if (progress.items.size > 1) {
                payload.releaseTitle ?: payload.title
            } else {
                progress.name.ifBlank { payload.releaseTitle ?: payload.title }
            }
        val context = buildArrGrabContext(payload, webUiUrl, titleText, epRange)
        context.putAll(progressContext(progress, releaseName, shown, episodeTracks))

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
            progressBar = shown.progressBar,
            speedFormatted = shown.speed,
            etaFormatted = shown.eta,
            sizeFormatted = shown.size,
            peersInfo = shown.peers,
            stateText = shown.state,
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
        val titleText = downloadTitle(payload, epRange)

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

        return resolved.toCard(NotificationLevel.SUCCESS, "download_complete") {
            copy(fields = completionFields(payload, progress, releaseName))
        }
    }

    private fun completionFields(
        payload: MediaPayload.ArrGrab,
        progress: DownloadItemProgress,
        releaseName: String
    ): List<CardField> {
        val episodes =
            progress.items.takeIf { it.size > 1 }?.let { items ->
                val summary = items.joinToString(", ") { extractEpisodeLabel(it.name) }
                CardField("Episodes", "$summary (${items.size} episodes)")
            }
        return listOfNotNull(
            CardField("Release", releaseName),
            episodes,
            CardField("Status", "✅ 100% Downloaded"),
            CardField("Total Size", formatBytes(progress.totalSizeBytes)),
            payload.quality?.let { CardField("Quality", it) }
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
        val titleText = downloadTitle(payload, epRange)

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

        return resolved.toCard(NotificationLevel.WARNING, "download_stalled") {
            copy(
                fields =
                    listOfNotNull(
                        CardField("Release", releaseName),
                        CardField("Status", "⚠️ Download Stalled (0 B/s)"),
                        CardField("Progress", "${String.format(Locale.US, "%.2f", progressVal)}% $progressBar"),
                        payload.quality?.let { CardField("Quality", it) }
                    )
            )
        }
    }

    /** "Series (S01E02)" when the episodes are known, otherwise the release title. */
    private fun downloadTitle(
        payload: MediaPayload.ArrGrab,
        epRange: String?
    ): String = if (epRange != null) "${payload.seriesOrMovieTitle} ($epRange)" else payload.title
}

/** The values a live progress card shows, formatted once for both the template context and the card. */
private class FormattedProgress(
    val size: String,
    val speed: String,
    val eta: String,
    val peers: String,
    val progressBar: String,
    val state: String
)

private fun formatProgress(
    progress: DownloadItemProgress,
    engine: TemplateEngine
): FormattedProgress =
    FormattedProgress(
        size = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalSizeBytes)}",
        speed = formatSpeed(progress.downloadSpeedBytesPerSec),
        eta = formatDuration(progress.etaSeconds),
        // Only swarm-based (torrent) clients report peers; the empty value suppresses the template line otherwise.
        peers =
            progress.swarm
                ?.let {
                    formatPeers(
                        it.seedsCount,
                        it.seedsTotal,
                        it.peersCount,
                        it.peersTotal
                    )
                }.orEmpty(),
        progressBar =
            drawProgressBar(
                progress.progressPercent,
                engine.theme.progressBarLength,
                engine.theme.progressBarStyle
            ),
        state = stateLabel(progress.state)
    )

private fun stateLabel(state: DownloadState): String =
    when (state) {
        DownloadState.DOWNLOADING -> "Downloading"
        DownloadState.STALLED -> "Stalled (No seeds)"
        DownloadState.COMPLETED, DownloadState.UPLOADING -> "Completed"
        DownloadState.ALLOCATING_METADATA -> "Allocating metadata"
        DownloadState.PAUSED -> "Paused"
        DownloadState.QUEUED -> "Queued"
        DownloadState.CHECKING -> "Checking"
        DownloadState.UNKNOWN -> "Active"
    }

private fun progressContext(
    progress: DownloadItemProgress,
    releaseName: String,
    shown: FormattedProgress,
    episodeTracks: String?
): Map<String, Any?> =
    mapOf(
        "release_title" to releaseName,
        "release_name" to releaseName,
        "torrent_name" to progress.name,
        "progress_percent" to String.format(Locale.US, "%.2f", progress.progressPercent),
        "progress_bar" to shown.progressBar,
        "speed" to shown.speed,
        "eta" to shown.eta,
        "downloaded_size" to formatBytes(progress.downloadedBytes),
        "total_size" to formatBytes(progress.totalSizeBytes),
        "size_formatted" to shown.size,
        "size" to shown.size,
        "peers_info" to shown.peers,
        "state" to shown.state,
        "episode_tracks" to episodeTracks,
        "has_multiple_tracks" to (progress.items.size > 1),
        "episode_count" to progress.items.size
    )
