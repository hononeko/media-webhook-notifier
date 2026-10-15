package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.EpisodeFormat.formatEpisodeRange
import app.hononeko.notifier.domain.service.MediaFormat.formatDuration
import app.hononeko.notifier.domain.service.MediaFormat.truncateOverview
import java.util.Locale

/** Plex and Jellyfin "now available" cards. */
object MediaServerCards {
    private const val SCORE_FORMAT = "%.1f/10"

    @Suppress("LongParameterList")
    private class MediaServerItemDetails(
        val sourceName: String,
        val actionEmoji: String,
        val itemTitle: String,
        val seriesTitle: String,
        val fullTitle: String,
        val seasonLabel: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val resolvedMediaType: String,
        val year: Int?,
        val overview: String?,
        val posterUrl: String?,
        val artworkBytes: ByteArray? = null,
        val specs: MediaSpecs,
        val deepLinkUrl: String?,
        val mediaServerName: String
    )

    private fun renderMediaServerCard(
        details: MediaServerItemDetails,
        engine: TemplateEngine
    ): NotificationCard {
        val formattedSeason = details.seasonNumber?.let { String.format(Locale.US, "%02d", it) }
        val formattedEpisode = details.episodeNumber?.let { String.format(Locale.US, "%02d", it) }
        val specsSummary =
            listOfNotNull(
                details.specs.resolution,
                details.specs.video,
                details.specs.audio
            ).joinToString(" • ")

        val context =
            mutableMapOf<String, Any?>(
                "title" to details.fullTitle,
                "item_title" to details.itemTitle,
                "series_title" to details.seriesTitle.ifBlank { details.itemTitle },
                "season" to formattedSeason,
                "season_number" to details.seasonNumber?.toString(),
                "season_title" to (details.seasonLabel ?: formattedSeason?.let { "Season $it" }),
                "episode" to formattedEpisode,
                "episode_number" to details.episodeNumber?.toString(),
                "episode_title" to details.itemTitle,
                "media_type" to details.resolvedMediaType,
                "year" to details.year?.toString(),
                "specs" to specsSummary,
                "overview" to truncateOverview(details.overview, engine.theme.maxOverviewLength),
                "video_codec" to details.specs.video,
                "audio_codec" to details.specs.audio,
                "resolution" to details.specs.resolution,
                "rating" to details.specs.score,
                "score" to details.specs.score,
                "duration" to details.specs.duration,
                "deep_link_url" to details.deepLinkUrl,
                "media_server_name" to details.mediaServerName,
                "poster_url" to details.posterUrl,
                "instance_name" to details.mediaServerName,
                "source_name" to details.sourceName
            )

        val defaultActions =
            if (!details.deepLinkUrl.isNullOrBlank()) {
                listOf(
                    ActionLink(
                        label = "${details.actionEmoji} Watch on ${details.sourceName}",
                        url = details.deepLinkUrl,
                        style = ActionStyle.PRIMARY
                    )
                )
            } else {
                emptyList()
            }

        val defaults =
            DefaultCardSpec(
                title = "🍿 ${details.fullTitle} now available on ${details.mediaServerName}",
                body = details.overview,
                artworkUrl = details.posterUrl,
                actions = defaultActions
            )

        val resolved =
            engine.resolveCard(
                eventName = "media_available",
                defaults = defaults,
                context = context
            )

        val finalArtworkBytes = if (resolved.imageEmbedEnabled) details.artworkBytes else null

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = NotificationLevel.SUCCESS,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                artworkBytes = finalArtworkBytes,
                actions = resolved.actions,
                eventType = "media_available",
                silent = resolved.silent
            )
        }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = truncateOverview(defaults.body, engine.theme.maxOverviewLength),
            level = NotificationLevel.SUCCESS,
            mediaSpecs = details.specs,
            artworkUrl = resolved.artworkUrl,
            artworkBytes = finalArtworkBytes,
            actions = resolved.actions,
            eventType = "media_available",
            silent = resolved.silent
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

    private fun formatSeasonTitle(
        seriesTitle: String,
        itemTitle: String,
        seasonLabel: String?
    ): String =
        when {
            seriesTitle.isNotBlank() && seasonLabel != null -> "$seriesTitle - $seasonLabel"
            seriesTitle.isNotBlank() -> "$seriesTitle - $itemTitle"
            else -> itemTitle
        }

    private fun formatEpisodeTitle(
        seriesTitle: String,
        itemTitle: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeNumbers: List<Int>
    ): String {
        if (seriesTitle.isBlank()) return itemTitle
        if (seasonNumber != null && episodeNumbers.size > 1) {
            val epRange = formatEpisodeRange(seasonNumber, episodeNumbers)
            return epRange?.let { "$seriesTitle - $it" } ?: "$seriesTitle - $itemTitle"
        }
        if (seasonNumber != null && episodeNumber != null) {
            val epCode = formatEpisodeCode(seasonNumber, episodeNumber)
            return if (itemTitle.isNotBlank() &&
                !itemTitle.equals(epCode, ignoreCase = true) &&
                !itemTitle.startsWith("Episode ", ignoreCase = true)
            ) {
                "$seriesTitle - $epCode - $itemTitle"
            } else {
                "$seriesTitle - $epCode"
            }
        }
        return "$seriesTitle - $itemTitle"
    }

    private fun formatStandaloneTitle(
        seriesTitle: String,
        itemTitle: String,
        year: Int?
    ): String =
        when {
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

    internal fun buildPlexCard(
        payload: MediaPayload.PlexLibraryNew,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val kind = determinePlexKind(payload)
        val isEpisode = kind.isEpisode
        val isSeason = kind.isSeason
        val seriesTitle = kind.seriesTitle
        val mediaType = kind.mediaType

        val seasonLabel = resolveSeasonLabel(isSeason, payload.title, payload.seasonNumber)
        val fullTitle =
            when {
                isSeason -> formatSeasonTitle(seriesTitle, payload.title, seasonLabel)
                isEpisode ->
                    formatEpisodeTitle(
                        seriesTitle,
                        payload.title,
                        payload.seasonNumber,
                        payload.episodeNumber,
                        payload.episodeNumbers
                    )
                else -> formatStandaloneTitle(seriesTitle, payload.title, payload.year)
            }

        val effectivePosterUrl = payload.posterUrl ?: payload.parentPosterUrl ?: payload.grandparentPosterUrl
        val effectiveDuration =
            if (isSeason || mediaType == "show" || mediaType == "series") {
                null
            } else {
                payload.durationSeconds
            }
        val resolvedMediaType = resolveMediaType(isSeason, isEpisode, mediaType, seriesTitle, payload.year)
        val deepLinkUrl = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        val mediaServerName = payload.instanceName?.takeUnless { it.equals("Plex", ignoreCase = true) } ?: "Plex"

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution,
                score = payload.rating?.let { String.format(Locale.US, SCORE_FORMAT, it) },
                duration = effectiveDuration?.let { formatDuration(it) }
            )

        val details =
            MediaServerItemDetails(
                sourceName = "Plex",
                actionEmoji = "🎬",
                itemTitle = payload.title,
                seriesTitle = seriesTitle,
                fullTitle = fullTitle,
                seasonLabel = seasonLabel,
                seasonNumber = payload.seasonNumber,
                episodeNumber = payload.episodeNumber,
                resolvedMediaType = resolvedMediaType,
                year = payload.year,
                overview = payload.summary,
                posterUrl = effectivePosterUrl,
                artworkBytes = payload.artworkBytes,
                specs = specs,
                deepLinkUrl = deepLinkUrl,
                mediaServerName = mediaServerName
            )

        return renderMediaServerCard(details, engine)
    }

    internal fun buildJellyfinCard(
        payload: MediaPayload.JellyfinItemAdded,
        mediaServerPort: MediaServerPort?,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val type = payload.mediaType?.lowercase()
        val isEpisode =
            type == "episode" ||
                payload.episodeNumber != null
        val isSeason =
            !isEpisode &&
                (
                    type == "season" ||
                        (payload.seriesName != null && payload.seasonNumber != null)
                )

        val seriesTitle = payload.seriesName ?: ""
        val seasonLabel = resolveSeasonLabel(isSeason, payload.title, payload.seasonNumber)
        val fullTitle =
            when {
                isSeason -> formatSeasonTitle(seriesTitle, payload.title, seasonLabel)
                isEpisode ->
                    formatEpisodeTitle(
                        seriesTitle,
                        payload.title,
                        payload.seasonNumber,
                        payload.episodeNumber,
                        payload.episodeNumbers
                    )
                else -> formatStandaloneTitle(seriesTitle, payload.title, payload.year)
            }

        val resolvedMediaType = resolveMediaType(isSeason, isEpisode, type, seriesTitle, payload.year)
        val deepLinkUrl = payload.deepLinkUrl ?: mediaServerPort?.resolveDeepLink(payload)
        val mediaServerName =
            payload.instanceName?.takeUnless { it.equals("Jellyfin", ignoreCase = true) } ?: "Jellyfin"

        val specs =
            MediaSpecs(
                video = payload.videoCodec,
                audio = payload.audioCodec,
                resolution = payload.resolution
            )

        val details =
            MediaServerItemDetails(
                sourceName = "Jellyfin",
                actionEmoji = "🍿",
                itemTitle = payload.title,
                seriesTitle = seriesTitle,
                fullTitle = fullTitle,
                seasonLabel = seasonLabel,
                seasonNumber = payload.seasonNumber,
                episodeNumber = payload.episodeNumber,
                resolvedMediaType = resolvedMediaType,
                year = payload.year,
                overview = payload.overview,
                posterUrl = payload.posterUrl,
                artworkBytes = null,
                specs = specs,
                deepLinkUrl = deepLinkUrl,
                mediaServerName = mediaServerName
            )

        return renderMediaServerCard(details, engine)
    }
}

private class PlexMediaKind(
    val isEpisode: Boolean,
    val isSeason: Boolean,
    val seriesTitle: String,
    val mediaType: String?
)

private fun determinePlexKind(payload: MediaPayload.PlexLibraryNew): PlexMediaKind {
    val mediaType = payload.mediaType?.lowercase()
    val isEpisode =
        mediaType == "episode" ||
            payload.episodeNumber != null ||
            payload.grandParentTitle != null
    val isSeason =
        !isEpisode &&
            (
                mediaType == "season" ||
                    (
                        payload.parentTitle != null &&
                            (payload.title.startsWith("Season", ignoreCase = true) || payload.seasonNumber != null)
                    )
            )

    val seriesTitle =
        when {
            isSeason -> payload.parentTitle ?: ""
            isEpisode -> payload.grandParentTitle ?: payload.parentTitle ?: ""
            else -> ""
        }

    return PlexMediaKind(isEpisode = isEpisode, isSeason = isSeason, seriesTitle = seriesTitle, mediaType = mediaType)
}
