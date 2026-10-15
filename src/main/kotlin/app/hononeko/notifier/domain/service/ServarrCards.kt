package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.EpisodeFormat.formatEpisodeRange
import app.hononeko.notifier.domain.service.MediaFormat.formatBytes
import app.hononeko.notifier.domain.service.MediaFormat.truncateOverview
import java.util.Locale

/** Sonarr / Radarr cards: imports, health issues and manual interaction. */
object ServarrCards {
    fun buildImportCard(
        payload: MediaPayload.ArrDownload,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val singleEpisodeTitle = payload.episodeTitle?.takeIf { payload.episodeNumbers.size == 1 }
        val fullTitle =
            formatServarrFullTitle(
                seriesOrMovieTitle = payload.seriesOrMovieTitle,
                title = payload.title,
                epRange = epRange,
                singleEpisodeTitle = singleEpisodeTitle,
                year = payload.year
            )

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

        val context =
            buildImportContext(
                payload,
                fullTitle,
                epRange,
                specsSummary,
                engine.theme.maxOverviewLength
            )

        val resolved =
            engine.resolveCard(
                eventName = "import",
                defaults =
                    DefaultCardSpec(
                        title = defaultTitle,
                        subtitle = defaultSubtitle,
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
                level = NotificationLevel.SUCCESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = "import",
                silent = resolved.silent
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
            eventType = "import",
            silent = resolved.silent
        )
    }

    fun buildHealthCard(
        payload: MediaPayload.ServarrHealth,
        engine: TemplateEngine = templateEngine
    ): NotificationCard = renderHealthCard(payload, engine).withAcknowledgeIf(!isRestoredHealth(payload))

    private fun isRestoredHealth(payload: MediaPayload.ServarrHealth): Boolean =
        payload.eventType == EventType.HEALTH_RESTORED ||
            payload.level.equals("ok", ignoreCase = true) ||
            payload.level.equals("restored", ignoreCase = true)

    private fun renderHealthCard(
        payload: MediaPayload.ServarrHealth,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val isRestored = isRestoredHealth(payload)
        val isError =
            payload.level.equals("error", ignoreCase = true) ||
                payload.level.equals("critical", ignoreCase = true)

        val meta =
            when {
                isRestored -> HealthMetadata("Health Restored", NotificationLevel.SUCCESS, "Health Status", "✅")
                isError -> HealthMetadata("Health Error", NotificationLevel.ERROR, "Health Alert", "🚨")
                else -> HealthMetadata("Health Warning", NotificationLevel.WARNING, "Health Warning", "⚠️")
            }

        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val defaultTitle = "${meta.statusEmoji} ${meta.titlePrefix}: $instanceLabel"
        val defaultSubtitle = "$instanceLabel • ${meta.subtitleSuffix}"

        val context =
            mutableMapOf<String, Any?>(
                "title" to defaultTitle,
                "health_status" to meta.titlePrefix,
                "health_icon" to meta.statusEmoji,
                "health_type" to meta.subtitleSuffix,
                "message" to payload.message,
                "issue_type" to payload.type,
                "wiki_url" to payload.wikiUrl,
                "instance_name" to instanceLabel,
                "source_name" to payload.source.displayName
            )

        val resolved =
            engine.resolveCard(
                eventName = "health",
                defaults =
                    DefaultCardSpec(
                        title = defaultTitle,
                        subtitle = defaultSubtitle,
                        artworkUrl = null,
                        actions = emptyList()
                    ),
                context = context
            )

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = meta.level,
                customBody = resolved.customBody,
                actions = resolved.actions,
                eventType = "health",
                silent = resolved.silent
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
            level = meta.level,
            fields = fields,
            actions = resolved.actions,
            eventType = "health",
            silent = resolved.silent
        )
    }

    fun buildManualInteractionCard(
        payload: MediaPayload.ServarrManualInteraction,
        engine: TemplateEngine = templateEngine
    ): NotificationCard = renderManualInteractionCard(payload, engine).withAcknowledgeIf(true)

    private fun renderManualInteractionCard(
        payload: MediaPayload.ServarrManualInteraction,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val epRange = formatEpisodeRange(payload.seasonNumber, payload.episodeNumbers)
        val singleEpisodeTitle = payload.episodeTitle?.takeIf { payload.episodeNumbers.size == 1 }
        val fullTitle =
            formatServarrFullTitle(
                seriesOrMovieTitle = payload.seriesOrMovieTitle,
                title = payload.title,
                epRange = epRange,
                singleEpisodeTitle = singleEpisodeTitle
            )

        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val defaultTitle = "✋ Manual Import Required: $fullTitle"
        val defaultSubtitle = "$instanceLabel • Manual Intervention"

        val context = buildManualInteractionContext(payload, fullTitle, epRange, instanceLabel)

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
                defaults =
                    DefaultCardSpec(
                        title = defaultTitle,
                        subtitle = defaultSubtitle,
                        artworkUrl = payload.posterUrl,
                        actions = defaultActions
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
                eventType = "manual_interaction",
                silent = resolved.silent
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
            eventType = "manual_interaction",
            silent = resolved.silent
        )
    }
}

private data class HealthMetadata(
    val titlePrefix: String,
    val level: NotificationLevel,
    val subtitleSuffix: String,
    val statusEmoji: String
)

private fun formatServarrFullTitle(
    seriesOrMovieTitle: String,
    title: String,
    epRange: String?,
    singleEpisodeTitle: String?,
    year: Int? = null
): String =
    when {
        epRange != null && !singleEpisodeTitle.isNullOrBlank() -> {
            val epTitle = singleEpisodeTitle.trim()
            if (!epTitle.equals(epRange, ignoreCase = true) &&
                !epTitle.startsWith("Episode ", ignoreCase = true)
            ) {
                "$seriesOrMovieTitle - $epRange - $epTitle"
            } else {
                "$seriesOrMovieTitle - $epRange"
            }
        }
        epRange != null -> "$seriesOrMovieTitle ($epRange)"
        year != null -> "$title ($year)"
        else -> title
    }

private fun buildImportContext(
    payload: MediaPayload.ArrDownload,
    fullTitle: String,
    epRange: String?,
    specsSummary: String,
    maxOverviewLength: Int
): Map<String, Any?> {
    val formattedSeason = payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) }
    val firstEpisode = payload.episodeNumbers.firstOrNull()
    val formattedEpisode = firstEpisode?.let { String.format(Locale.US, "%02d", it) }

    return mapOf(
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
        "size" to payload.sizeBytes?.let { MediaFormat.formatBytes(it) },
        "total_size" to payload.sizeBytes?.let { MediaFormat.formatBytes(it) },
        "is_upgrade" to payload.isUpgrade.toString(),
        "import_action" to if (payload.isUpgrade) "File Upgraded" else "File Imported",
        "import_icon" to if (payload.isUpgrade) "⬆️" else "📁",
        "import_type" to if (payload.isUpgrade) "Quality Upgrade" else "Library Import",
        "overview" to MediaFormat.truncateOverview(payload.overview, maxOverviewLength),
        "poster_url" to payload.posterUrl,
        "web_url" to payload.webUrl,
        "instance_name" to (payload.instanceName ?: payload.source.displayName),
        "source_name" to payload.source.displayName
    )
}

private fun buildManualInteractionContext(
    payload: MediaPayload.ServarrManualInteraction,
    fullTitle: String,
    epRange: String?,
    instanceLabel: String
): Map<String, Any?> {
    val formattedSeason = payload.seasonNumber?.let { String.format(Locale.US, "%02d", it) }
    val firstEpisode = payload.episodeNumbers.firstOrNull()
    val formattedEpisode = firstEpisode?.let { String.format(Locale.US, "%02d", it) }

    return mapOf(
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
        "size" to payload.sizeBytes?.let { MediaFormat.formatBytes(it) },
        "total_size" to payload.sizeBytes?.let { MediaFormat.formatBytes(it) },
        "indexer" to payload.indexer,
        "download_client" to payload.downloadClient,
        "client" to payload.downloadClient,
        "download_id" to payload.downloadId,
        "web_url" to payload.webUrl,
        "poster_url" to payload.posterUrl,
        "instance_name" to instanceLabel,
        "source_name" to payload.source.displayName
    )
}
