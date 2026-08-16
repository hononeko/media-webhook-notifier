package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.config.YamlParser
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.service.CardFormatterService
import app.hononeko.notifier.domain.service.TemplateEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class TemplatePreviewRequestDto(
    @SerialName("template_yaml") val templateYaml: String? = null,
    @SerialName("event_type") val eventType: String = "grab"
)

@Serializable
data class TemplatePreviewResponseDto(
    val status: String = "success",
    @SerialName("event_type") val eventType: String,
    @SerialName("rendered_card") val renderedCard: RenderedCardDto,
    @SerialName("telegram_html") val telegramHtml: String,
    @SerialName("tags_available") val tagsAvailable: List<String> = emptyList()
)

@Serializable
data class RenderedCardDto(
    val title: String,
    val subtitle: String? = null,
    val level: String,
    @SerialName("custom_body") val customBody: String? = null,
    val overview: String? = null,
    val fields: List<CardFieldDto> = emptyList(),
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val actions: List<ActionLinkDto> = emptyList()
)

@Serializable
data class CardFieldDto(
    val name: String,
    val value: String,
    val inline: Boolean = true
)

@Serializable
data class ActionLinkDto(
    val label: String,
    val url: String,
    val style: String
)

class TemplatePreviewController {
    suspend fun handlePreview(call: ApplicationCall) {
        val request =
            try {
                call.receive<TemplatePreviewRequestDto>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("status" to "error", "message" to "Invalid JSON payload: ${e.message}")
                )
                return
            }

        val templateConfig =
            if (!request.templateYaml.isNullOrBlank()) {
                try {
                    YamlParser.parseTemplateConfig(request.templateYaml)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("status" to "error", "message" to "YAML parsing error: ${e.message}")
                    )
                    return
                }
            } else {
                CardFormatterService.templateEngine.let { null } ?: app.hononeko.notifier.domain.model
                    .TemplateConfig()
            }

        val engine = TemplateEngine(templateConfig)
        val eventType = request.eventType.lowercase(Locale.US).trim()

        val (card, progressUpdate, availableTags) = renderMockEvent(eventType, engine)

        val renderedCardDto =
            if (card != null) {
                RenderedCardDto(
                    title = card.title,
                    subtitle = card.subtitle,
                    level = card.level.name,
                    customBody = card.customBody,
                    overview = card.overview,
                    fields = card.fields.map { CardFieldDto(it.name, it.value, it.inline) },
                    artworkUrl = card.artworkUrl,
                    actions = card.actions.map { ActionLinkDto(it.label, it.url, it.style.name) }
                )
            } else {
                RenderedCardDto(
                    title = progressUpdate!!.title,
                    subtitle = progressUpdate.subtitle,
                    level = "PROGRESS",
                    customBody = progressUpdate.customBody,
                    overview = null,
                    fields = emptyList(),
                    artworkUrl = null,
                    actions = progressUpdate.actions.map { ActionLinkDto(it.label, it.url, it.style.name) }
                )
            }

        val htmlOutput =
            if (card != null) {
                app.hononeko.notifier.adapter.outbound.telegram.TelegramHtmlFormatter
                    .buildCardHtml(card)
            } else {
                app.hononeko.notifier.adapter.outbound.telegram.TelegramHtmlFormatter.buildProgressHtml(
                    progressUpdate!!
                )
            }

        call.respond(
            HttpStatusCode.OK,
            TemplatePreviewResponseDto(
                status = "success",
                eventType = eventType,
                renderedCard = renderedCardDto,
                telegramHtml = htmlOutput,
                tagsAvailable = availableTags
            )
        )
    }

    private fun renderMockEvent(
        eventType: String,
        engine: TemplateEngine
    ): Triple<NotificationCard?, ProgressUpdate?, List<String>> =
        when (eventType) {
            "download_progress" -> {
                val grab = mockGrab()
                val progress = mockProgress(68.5, TorrentState.DOWNLOADING)
                val update =
                    CardFormatterService.buildProgressUpdate(
                        grab,
                        progress,
                        "http://qbittorrent.lan:8080",
                        engine
                    )
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "season",
                        "episode_range",
                        "quality",
                        "release_group",
                        "indexer",
                        "webui_url",
                        "poster_url",
                        "instance_name",
                        "source_name",
                        "download_id",
                        "progress_percent",
                        "progress_bar",
                        "speed",
                        "eta",
                        "downloaded_size",
                        "total_size",
                        "peers_info",
                        "state"
                    )
                Triple(null, update, tags)
            }
            "download_complete" -> {
                val grab = mockGrab()
                val progress = mockProgress(100.0, TorrentState.COMPLETED)
                val card =
                    CardFormatterService.buildCompletionCard(
                        grab,
                        progress,
                        "http://qbittorrent.lan:8080",
                        engine
                    )
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "season",
                        "episode_range",
                        "quality",
                        "release_group",
                        "total_size",
                        "size",
                        "indexer",
                        "webui_url",
                        "poster_url",
                        "instance_name",
                        "source_name",
                        "download_id"
                    )
                Triple(card, null, tags)
            }
            "download_stalled" -> {
                val grab = mockGrab()
                val progress = mockProgress(42.0, TorrentState.STALLED)
                val card = CardFormatterService.buildStalledCard(grab, progress, "http://qbittorrent.lan:8080", engine)
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "season",
                        "episode_range",
                        "quality",
                        "progress_percent",
                        "progress_bar",
                        "webui_url",
                        "poster_url",
                        "instance_name",
                        "source_name",
                        "download_id"
                    )
                Triple(card, null, tags)
            }
            "import" -> {
                val download = mockDownload()
                val card = CardFormatterService.buildImportCard(download, engine)
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "year",
                        "season",
                        "episode_range",
                        "quality",
                        "video_codec",
                        "audio_codec",
                        "resolution",
                        "size",
                        "total_size",
                        "is_upgrade",
                        "import_action",
                        "import_icon",
                        "import_type",
                        "overview",
                        "poster_url",
                        "web_url",
                        "instance_name",
                        "source_name"
                    )
                Triple(card, null, tags)
            }
            "media_available" -> {
                val plex = mockPlex()
                val card = CardFormatterService.buildAvailableCard(plex, engine = engine)
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "year",
                        "overview",
                        "video_codec",
                        "audio_codec",
                        "resolution",
                        "rating",
                        "score",
                        "duration",
                        "deep_link_url",
                        "media_server_name",
                        "poster_url",
                        "instance_name",
                        "source_name"
                    )
                Triple(card, null, tags)
            }
            "health" -> {
                val health = mockHealth()
                val card = CardFormatterService.buildHealthCard(health, engine)
                val tags =
                    listOf(
                        "title",
                        "health_status",
                        "health_icon",
                        "health_type",
                        "message",
                        "issue_type",
                        "wiki_url",
                        "instance_name",
                        "source_name"
                    )
                Triple(card, null, tags)
            }
            "manual_interaction" -> {
                val manual = mockManual()
                val card = CardFormatterService.buildManualInteractionCard(manual, engine)
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "season",
                        "episode_range",
                        "reason",
                        "release_title",
                        "quality",
                        "size",
                        "total_size",
                        "indexer",
                        "download_client",
                        "client",
                        "web_url",
                        "poster_url",
                        "instance_name",
                        "source_name"
                    )
                Triple(card, null, tags)
            }
            "request" -> {
                val seerr = mockSeerr()
                val card = CardFormatterService.buildSeerrCard(seerr, engine)
                val tags =
                    listOf(
                        "title",
                        "subject",
                        "request_icon",
                        "request_action",
                        "request_status",
                        "requested_by",
                        "media_type",
                        "quality",
                        "issue_type",
                        "issue_status",
                        "comment",
                        "message",
                        "web_url",
                        "poster_url",
                        "instance_name",
                        "source_name"
                    )
                Triple(card, null, tags)
            }
            else -> {
                // Default to grab
                val grab = mockGrab()
                val card = CardFormatterService.buildGrabInitialCard(grab, "http://qbittorrent.lan:8080", engine)
                val tags =
                    listOf(
                        "title",
                        "series_title",
                        "season",
                        "episode_range",
                        "quality",
                        "release_group",
                        "size",
                        "total_size",
                        "indexer",
                        "webui_url",
                        "poster_url",
                        "instance_name",
                        "source_name",
                        "download_id"
                    )
                Triple(card, null, tags)
            }
        }

    private fun mockGrab() =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = "mock-download-123",
            title = "Breaking Bad - S01E01 - Pilot",
            seriesOrMovieTitle = "Breaking Bad",
            seasonNumber = 1,
            episodeNumbers = listOf(1),
            releaseGroup = "FLUX",
            quality = "WEBDL-1080p",
            sizeBytes = 2147483648L,
            indexer = "TorrentLeech",
            posterUrl = "https://image.tmdb.org/t/p/w500/sample.jpg",
            instanceName = "Sonarr-4K"
        )

    private fun mockProgress(
        percent: Double,
        state: TorrentState
    ) = TorrentProgress(
        hash = "mock-hash-123456",
        name = "Breaking Bad - S01E01 - Pilot",
        progressPercent = percent,
        progressRatio = percent / 100.0,
        downloadSpeedBytesPerSec = 15728640L,
        uploadSpeedBytesPerSec = 1048576L,
        downloadedBytes = (2147483648L * (percent / 100.0)).toLong(),
        totalSizeBytes = 2147483648L,
        etaSeconds = 45L,
        seedsCount = 42,
        seedsTotal = 120,
        peersCount = 8,
        peersTotal = 25,
        state = state
    )

    private fun mockDownload() =
        MediaPayload.ArrDownload(
            source = AppSource.RADARR,
            title = "Dune: Part Two",
            seriesOrMovieTitle = "Dune: Part Two",
            year = 2024,
            quality = "Remux-2160p",
            videoCodec = "HEVC",
            audioCodec = "TrueHD Atmos",
            resolution = "2160p (4K)",
            sizeBytes = 45097156608L,
            posterUrl = "https://image.tmdb.org/t/p/w500/dune2.jpg",
            overview =
                "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                    "against the conspirators who destroyed his family.",
            instanceName = "Radarr-4K",
            webUrl = "http://radarr.lan:7878/movie/1"
        )

    private fun mockPlex() =
        MediaPayload.PlexLibraryNew(
            title = "Dune: Part Two",
            year = 2024,
            summary =
                "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                    "against the conspirators who destroyed his family.",
            rating = 8.6,
            durationSeconds = 9960L,
            videoCodec = "HEVC",
            audioCodec = "TrueHD Atmos",
            resolution = "2160p (4K)",
            posterUrl = "https://image.tmdb.org/t/p/w500/dune2.jpg",
            deepLinkUrl = "https://app.plex.tv/desktop#!/server/abc/details?key=/library/metadata/12345",
            instanceName = "Plex Media Server"
        )

    private fun mockHealth() =
        MediaPayload.ServarrHealth(
            source = AppSource.SONARR,
            level = "error",
            message = "Indexers are unavailable: TorrentLeech",
            type = "Indexers",
            wikiUrl = "https://wiki.servarr.com/sonarr/system#indexers-unavailable",
            instanceName = "Sonarr-4K"
        )

    private fun mockManual() =
        MediaPayload.ServarrManualInteraction(
            source = AppSource.RADARR,
            title = "Dune: Part Two (2024)",
            seriesOrMovieTitle = "Dune: Part Two",
            releaseTitle = "Dune.Part.Two.2024.2160p.UHD.Remux.TrueHD.Atmos-FLUX.mkv",
            quality = "Remux-2160p",
            sizeBytes = 45097156608L,
            indexer = "TorrentLeech",
            downloadClient = "qBittorrent",
            reason = "Found unknown movie file",
            posterUrl = "https://image.tmdb.org/t/p/w500/dune2.jpg",
            webUrl = "http://radarr.lan:7878/activity/queue",
            instanceName = "Radarr-4K"
        )

    private fun mockSeerr() =
        MediaPayload.SeerrEvent(
            eventType = EventType.REQUEST_PENDING,
            notificationType = "MEDIA_PENDING",
            subject = "Dune: Part Two (2024)",
            requestedByUsername = "alice",
            mediaType = "movie",
            is4k = true,
            image = "https://image.tmdb.org/t/p/w500/dune2.jpg",
            webUrl = "http://overseerr.lan:5055/movie/1",
            instanceName = "Overseerr"
        )
}
