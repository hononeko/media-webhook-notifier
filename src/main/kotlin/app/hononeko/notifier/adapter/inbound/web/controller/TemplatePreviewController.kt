package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.outbound.telegram.TelegramHtmlFormatter
import app.hononeko.notifier.config.YamlParser
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.service.CardTemplates
import app.hononeko.notifier.domain.service.DownloadCards
import app.hononeko.notifier.domain.service.PayloadCards
import app.hononeko.notifier.domain.service.SeerrCards
import app.hononeko.notifier.domain.service.ServarrCards
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
    val actions: List<ActionLinkDto> = emptyList(),
    val silent: Boolean = false
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

private const val MOCK_QBIT_URL = "https://qbittorrent.lan:8080"

private val BASE_TAGS =
    listOf("title", "series_title", "season", "episode_range", "poster_url", "instance_name", "source_name")
private val TORRENT_TAGS =
    BASE_TAGS +
        listOf(
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
            "season_number",
            "episode",
            "episode_number",
            "episode_title",
            "episode_name",
            "reason",
            "release_title",
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

class TemplatePreviewController {
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

        val eventType = request.eventType.lowercase(Locale.US).trim()
        val (card, progressUpdate, availableTags) = renderMockEvent(eventType, request.multiTrack, engineFor(request))
        // Exactly one of the two is set: progress events render a live-progress update, all others a card.
        val rendered = card?.toDto() ?: progressUpdate!!.toDto()
        val html =
            card?.let { TelegramHtmlFormatter.buildCardHtml(it) }
                ?: TelegramHtmlFormatter.buildProgressHtml(progressUpdate!!)

        call.respond(
            HttpStatusCode.OK,
            TemplatePreviewResponseDto(
                status = "success",
                eventType = eventType,
                renderedCard = rendered,
                telegramHtml = html,
                tagsAvailable = availableTags
            )
        )
    }

    /** The request's own templates on top of the active theme, or the active engine when it brings none. */
    private fun engineFor(request: TemplatePreviewRequestDto): TemplateEngine =
        if (!request.templateYaml.isNullOrBlank()) {
            TemplateEngine(
                YamlParser.parseTemplateConfig(request.templateYaml, defaultTheme = CardTemplates.templateEngine.theme)
            )
        } else {
            CardTemplates.templateEngine
        }

    private fun NotificationCard.toDto() =
        RenderedCardDto(
            title = title,
            subtitle = subtitle,
            level = level.name,
            customBody = customBody,
            overview = overview,
            fields = fields.map { CardFieldDto(it.name, it.value, it.inline) },
            artworkUrl = artworkUrl,
            actions = actions.map { ActionLinkDto(it.label, it.url, it.style.name) },
            silent = silent
        )

    private fun ProgressUpdate.toDto() =
        RenderedCardDto(
            title = title,
            subtitle = subtitle,
            level = "PROGRESS",
            customBody = customBody,
            overview = null,
            fields = emptyList(),
            artworkUrl = null,
            actions = actions.map { ActionLinkDto(it.label, it.url, it.style.name) },
            silent = true
        )

    private fun renderMockEvent(
        eventType: String,
        multiTrack: Boolean,
        engine: TemplateEngine
    ): Triple<NotificationCard?, ProgressUpdate?, List<String>> =
        when (eventType) {
            "download_progress", "download_progress_multi" ->
                renderMockProgressEvent(eventType, multiTrack, engine)
            "download_complete" ->
                renderMockCompleteEvent(multiTrack, engine)
            "download_stalled" -> {
                val grab = mockGrab()
                val progress = mockProgress(42.0, DownloadState.STALLED)
                val card = DownloadCards.buildStalledCard(grab, progress, MOCK_QBIT_URL, engine)
                Triple(card, null, STALLED_TAGS)
            }
            "import" -> {
                val download = mockDownload()
                val card = ServarrCards.buildImportCard(download, engine)
                Triple(card, null, IMPORT_TAGS)
            }
            "media_available" -> {
                val plex = mockPlex()
                val card = PayloadCards.buildAvailableCard(plex, engine = engine)
                Triple(card, null, AVAILABLE_TAGS)
            }
            "health" -> {
                val health = mockHealth()
                val card = ServarrCards.buildHealthCard(health, engine)
                Triple(card, null, HEALTH_TAGS)
            }
            "manual_interaction" -> {
                val manual = mockManual()
                val card = ServarrCards.buildManualInteractionCard(manual, engine)
                Triple(card, null, MANUAL_TAGS)
            }
            "issue" -> {
                val issue = mockIssue()
                val card = SeerrCards.buildSeerrCard(issue, engine)
                Triple(card, null, ISSUE_TAGS)
            }
            "request" -> {
                val seerr = mockSeerr()
                val card = SeerrCards.buildSeerrCard(seerr, engine)
                Triple(card, null, REQUEST_TAGS)
            }
            else -> {
                val grab = mockGrab()
                val card = DownloadCards.buildGrabInitialCard(grab, MOCK_QBIT_URL, engine)
                Triple(card, null, GRAB_TAGS)
            }
        }
}

private fun renderMockProgressEvent(
    eventType: String,
    multiTrack: Boolean,
    engine: TemplateEngine
): Triple<NotificationCard?, ProgressUpdate?, List<String>> {
    val isMulti = multiTrack || eventType == "download_progress_multi"
    val grab = if (isMulti) mockMultiGrab() else mockGrab()
    val progress = if (isMulti) mockMultiProgress() else mockProgress(68.5, DownloadState.DOWNLOADING)
    val update =
        DownloadCards.buildProgressUpdate(
            grab,
            progress,
            MOCK_QBIT_URL,
            engine
        )
    return Triple(null, update, PROGRESS_TAGS)
}

private fun renderMockCompleteEvent(
    multiTrack: Boolean,
    engine: TemplateEngine
): Triple<NotificationCard?, ProgressUpdate?, List<String>> {
    val grab = if (multiTrack) mockMultiGrab() else mockGrab()
    val progress =
        if (multiTrack) {
            mockMultiProgress().copy(state = DownloadState.COMPLETED)
        } else {
            mockProgress(100.0, DownloadState.COMPLETED)
        }
    val card =
        DownloadCards.buildCompletionCard(
            grab,
            progress,
            MOCK_QBIT_URL,
            engine
        )
    return Triple(card, null, GRAB_TAGS)
}
