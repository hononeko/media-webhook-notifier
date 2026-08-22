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
    @SerialName("template_yaml")
    val templateYaml: String? = null,
    @SerialName("event_type")
    val eventType: String = "grab",
    @SerialName("multi_track")
    val multiTrack: Boolean = false
)

@Serializable
data class TemplatePreviewResponseDto(
    val status: String = "success",
    @SerialName("event_type")
    val eventType: String,
    @SerialName("rendered_card")
    val renderedCard: RenderedCardDto,
    @SerialName("telegram_html")
    val telegramHtml: String,
    @SerialName("tags_available")
    val tagsAvailable: List<String>
)

@Serializable
data class RenderedCardDto(
    val title: String,
    val subtitle: String? = null,
    val level: String = "INFO",
    @SerialName("custom_body")
    val customBody: String? = null,
    val overview: String? = null,
    val fields: List<CardFieldDto> = emptyList(),
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
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
    val style: String = "DEFAULT"
)

class TemplatePreviewController {
    companion object {
        private const val MOCK_QBIT_URL = "http://qbittorrent.lan:8080"
        private const val MOCK_DUNE_TITLE = "Dune: Part Two"
        private const val MOCK_DUNE_POSTER = "https://image.tmdb.org/t/p/w500/dune2.jpg"

        private val BASE_TAGS =
            listOf("title", "series_title", "season", "episode_range", "poster_url", "instance_name", "source_name")
        private val TORRENT_TAGS =
            BASE_TAGS +
                listOf(
                    "season_number",
                    "episode",
                    "episode_number",
                    "episode_title",
                    "episode_name",
                    "quality",
                    "release_group",
                    "release_title",
                    "release_name",
                    "indexer",
                    "webui_url",
                    "download_id"
                )
        private val GRAB_TAGS = TORRENT_TAGS + listOf("size", "total_size")
        private val PROGRESS_TAGS =
            TORRENT_TAGS +
                listOf(
                    "progress_percent",
                    "progress_bar",
                    "speed",
                    "eta",
                    "downloaded_size",
                    "total_size",
                    "peers_info",
                    "state",
                    "episode_tracks",
                    "has_multiple_tracks",
                    "episode_count"
                )
        private val STALLED_TAGS = TORRENT_TAGS + listOf("progress_percent", "progress_bar")
        private val IMPORT_TAGS =
            BASE_TAGS +
                listOf(
                    "season_number",
                    "episode",
                    "episode_number",
                    "episode_title",
                    "episode_name",
                    "year",
                    "quality",
                    "specs",
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
                    "web_url"
                )
        private val AVAILABLE_TAGS =
            listOf(
                "title",
                "series_title",
                "year",
                "specs",
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
        private val HEALTH_TAGS =
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
        private val MANUAL_TAGS =
            TORRENT_TAGS +
                listOf(
                    "reason",
                    "size",
                    "total_size",
                    "download_client",
                    "client",
                    "web_url"
                )
        private val REQUEST_TAGS =
            listOf(
                "title",
                "subject",
                "request_icon",
                "request_action",
                "request_status",
                "requested_by",
                "media_type",
                "quality",
                "message",
                "web_url",
                "poster_url",
                "instance_name",
                "source_name"
            )
        private val ISSUE_TAGS =
            listOf(
                "title",
                "subject",
                "request_icon",
                "request_action",
                "request_status",
                "requested_by",
                "media_type",
                "issue_type",
                "issue_status",
                "comment",
                "message",
                "web_url",
                "poster_url",
                "instance_name",
                "source_name"
            )
    }

    suspend fun handlePreview(call: ApplicationCall) {
        val request =
            try {
                call.receive<TemplatePreviewRequestDto>()
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("status" to "error", "message" to "Invalid JSON payload")
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

        val (card, progressUpdate, availableTags) = renderMockEvent(eventType, request.multiTrack, engine)

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
        multiTrack: Boolean,
        engine: TemplateEngine
    ): Triple<NotificationCard?, ProgressUpdate?, List<String>> =
        when (eventType) {
            "download_progress", "download_progress_multi" -> {
                val isMulti = multiTrack || eventType == "download_progress_multi"
                val grab = if (isMulti) mockMultiGrab() else mockGrab()
                val progress = if (isMulti) mockMultiProgress() else mockProgress(68.5, TorrentState.DOWNLOADING)
                val update =
                    CardFormatterService.buildProgressUpdate(
                        grab,
                        progress,
                        MOCK_QBIT_URL,
                        engine
                    )
                Triple(null, update, PROGRESS_TAGS)
            }
            "download_complete" -> {
                val grab = if (multiTrack) mockMultiGrab() else mockGrab()
                val progress =
                    if (multiTrack) {
                        mockMultiProgress().copy(
                            state = TorrentState.COMPLETED
                        )
                    } else {
                        mockProgress(100.0, TorrentState.COMPLETED)
                    }
                val card =
                    CardFormatterService.buildCompletionCard(
                        grab,
                        progress,
                        MOCK_QBIT_URL,
                        engine
                    )
                Triple(card, null, GRAB_TAGS)
            }
            "download_stalled" -> {
                val grab = mockGrab()
                val progress = mockProgress(42.0, TorrentState.STALLED)
                val card = CardFormatterService.buildStalledCard(grab, progress, MOCK_QBIT_URL, engine)
                Triple(card, null, STALLED_TAGS)
            }
            "import" -> {
                val download = mockDownload()
                val card = CardFormatterService.buildImportCard(download, engine)
                Triple(card, null, IMPORT_TAGS)
            }
            "media_available" -> {
                val plex = mockPlex()
                val card = CardFormatterService.buildAvailableCard(plex, engine = engine)
                Triple(card, null, AVAILABLE_TAGS)
            }
            "health" -> {
                val health = mockHealth()
                val card = CardFormatterService.buildHealthCard(health, engine)
                Triple(card, null, HEALTH_TAGS)
            }
            "manual_interaction" -> {
                val manual = mockManual()
                val card = CardFormatterService.buildManualInteractionCard(manual, engine)
                Triple(card, null, MANUAL_TAGS)
            }
            "issue" -> {
                val issue = mockIssue()
                val card = CardFormatterService.buildSeerrCard(issue, engine)
                Triple(card, null, ISSUE_TAGS)
            }
            "request" -> {
                val seerr = mockSeerr()
                val card = CardFormatterService.buildSeerrCard(seerr, engine)
                Triple(card, null, REQUEST_TAGS)
            }
            else -> {
                val grab = mockGrab()
                val card = CardFormatterService.buildGrabInitialCard(grab, MOCK_QBIT_URL, engine)
                Triple(card, null, GRAB_TAGS)
            }
        }

    private fun mockMultiGrab() =
        MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = "hash_lib_01|hash_lib_02|hash_lib_03|hash_lib_04|hash_lib_05",
            title = "Love.Is.Blind.UK.S03.1080p.WEB.H264-DEFENESTRATE",
            seriesOrMovieTitle = "Love is Blind: UK",
            seasonNumber = 3,
            episodeNumbers = listOf(1, 2, 3, 4, 5),
            releaseGroup = "DEFENESTRATE",
            releaseTitle = "Love.Is.Blind.UK.S03.1080p.WEB.H264-DEFENESTRATE",
            quality = "WEB-DL-1080p",
            sizeBytes = 14173388800L,
            indexer = "DigitalCore (API)",
            posterUrl = "https://image.tmdb.org/t/p/w500/libuk.jpg",
            instanceName = "Sonarr-TV"
        )

    private fun mockMultiProgress(): TorrentProgress {
        val ep1 =
            TorrentProgress(
                hash = "hash_lib_01",
                name = "Love.Is.Blind.UK.S03E01.1080p.WEB.H264-DEFENESTRATE",
                progressPercent = 100.0,
                progressRatio = 1.0,
                downloadSpeedBytesPerSec = 0L,
                uploadSpeedBytesPerSec = 100000L,
                etaSeconds = 0L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 2834677760L,
                state = TorrentState.COMPLETED
            )
        val ep2 =
            TorrentProgress(
                hash = "hash_lib_02",
                name = "Love.Is.Blind.UK.S03E02.1080p.WEB.H264-DEFENESTRATE",
                progressPercent = 82.5,
                progressRatio = 0.825,
                downloadSpeedBytesPerSec = 12582912L,
                uploadSpeedBytesPerSec = 50000L,
                etaSeconds = 4L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 2338609152L,
                state = TorrentState.DOWNLOADING
            )
        val ep3 =
            TorrentProgress(
                hash = "hash_lib_03",
                name = "Love.Is.Blind.UK.S03E03.1080p.WEB.H264-DEFENESTRATE",
                progressPercent = 45.0,
                progressRatio = 0.45,
                downloadSpeedBytesPerSec = 8388608L,
                uploadSpeedBytesPerSec = 20000L,
                etaSeconds = 22L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 1275605000L,
                state = TorrentState.DOWNLOADING
            )
        val ep4 =
            TorrentProgress(
                hash = "hash_lib_04",
                name = "Love.Is.Blind.UK.S03E04.1080p.WEB.H264-DEFENESTRATE",
                progressPercent = 21.0,
                progressRatio = 0.21,
                downloadSpeedBytesPerSec = 4194304L,
                uploadSpeedBytesPerSec = 0L,
                etaSeconds = 75L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 595282330L,
                state = TorrentState.DOWNLOADING
            )
        val ep5 =
            TorrentProgress(
                hash = "hash_lib_05",
                name = "Love.Is.Blind.UK.S03E05.1080p.WEB.H264-DEFENESTRATE",
                progressPercent = 0.0,
                progressRatio = 0.0,
                downloadSpeedBytesPerSec = 0L,
                uploadSpeedBytesPerSec = 0L,
                etaSeconds = 0L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 0L,
                state = TorrentState.QUEUED
            )

        return TorrentProgress(
            hash = "hash_lib_01|hash_lib_02|hash_lib_03|hash_lib_04|hash_lib_05",
            name = "Love.Is.Blind.UK.S03.1080p.WEB.H264-DEFENESTRATE",
            progressPercent = 49.70,
            progressRatio = 0.4970,
            downloadSpeedBytesPerSec = 25165824L,
            uploadSpeedBytesPerSec = 170000L,
            etaSeconds = 48L,
            totalSizeBytes = 14173388800L,
            downloadedBytes = 7044174242L,
            seedsCount = 18,
            seedsTotal = 45,
            peersCount = 10,
            peersTotal = 25,
            state = TorrentState.DOWNLOADING,
            items = listOf(ep1, ep2, ep3, ep4, ep5)
        )
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
            releaseTitle = "Breaking.Bad.S01E01.Pilot.1080p.BluRay.x264-FLUX",
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
            title = MOCK_DUNE_TITLE,
            seriesOrMovieTitle = MOCK_DUNE_TITLE,
            year = 2024,
            quality = "Remux-2160p",
            videoCodec = "HEVC",
            audioCodec = "TrueHD Atmos",
            resolution = "2160p (4K)",
            sizeBytes = 45097156608L,
            posterUrl = MOCK_DUNE_POSTER,
            overview =
                "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                    "against the conspirators who destroyed his family.",
            instanceName = "Radarr-4K",
            webUrl = "http://radarr.lan:7878/movie/1"
        )

    private fun mockPlex() =
        MediaPayload.PlexLibraryNew(
            title = MOCK_DUNE_TITLE,
            year = 2024,
            summary =
                "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                    "against the conspirators who destroyed his family.",
            rating = 8.6,
            durationSeconds = 9960L,
            videoCodec = "HEVC",
            audioCodec = "TrueHD Atmos",
            resolution = "2160p (4K)",
            posterUrl = MOCK_DUNE_POSTER,
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
            title = "$MOCK_DUNE_TITLE (2024)",
            seriesOrMovieTitle = MOCK_DUNE_TITLE,
            releaseTitle = "Dune.Part.Two.2024.2160p.UHD.Remux.TrueHD.Atmos-FLUX.mkv",
            quality = "Remux-2160p",
            sizeBytes = 45097156608L,
            indexer = "TorrentLeech",
            downloadClient = "qBittorrent",
            reason = "Found unknown movie file",
            posterUrl = MOCK_DUNE_POSTER,
            webUrl = "http://radarr.lan:7878/activity/queue",
            instanceName = "Radarr-4K"
        )

    private fun mockSeerr() =
        MediaPayload.SeerrEvent(
            eventType = EventType.REQUEST_PENDING,
            notificationType = "MEDIA_PENDING",
            subject = "$MOCK_DUNE_TITLE (2024)",
            requestedByUsername = "alice",
            mediaType = "movie",
            is4k = true,
            image = MOCK_DUNE_POSTER,
            webUrl = "http://overseerr.lan:5055/movie/1",
            instanceName = "Overseerr"
        )

    private fun mockIssue() =
        MediaPayload.SeerrEvent(
            eventType = EventType.ISSUE_CREATED,
            notificationType = "ISSUE_CREATED",
            subject = "$MOCK_DUNE_TITLE (2024)",
            requestedByUsername = "bob",
            mediaType = "movie",
            is4k = false,
            issueType = "Video",
            issueStatus = "Open",
            commentMessage = "Video playback is choppy at 45m",
            image = MOCK_DUNE_POSTER,
            webUrl = "http://overseerr.lan:5055/issues/42",
            instanceName = "Overseerr"
        )
}
