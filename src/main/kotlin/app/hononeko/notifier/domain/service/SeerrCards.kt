package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine

/** Seerr (Overseerr / Jellyseerr) request and issue cards. */
object SeerrCards {
    fun buildSeerrCard(
        payload: MediaPayload.SeerrEvent,
        engine: TemplateEngine = templateEngine
    ): NotificationCard =
        renderSeerrCard(payload, engine).withAcknowledgeIf(payload.eventType == EventType.REQUEST_FAILED)

    private fun renderSeerrCard(
        payload: MediaPayload.SeerrEvent,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val appName = payload.instanceName ?: payload.source.displayName
        val meta = resolveSeerrMetadata(payload, appName)
        val mediaLabel = resolveSeerrMediaLabel(payload.mediaType)
        val context = buildSeerrContext(payload, meta, appName, mediaLabel)

        val isIssue = payload.eventType in SEERR_ISSUE_EVENTS
        val eventName = if (isIssue) "issue" else "request"
        val defaultActions = buildSeerrActions(payload.webUrl, appName, isIssue)

        val resolved =
            engine.resolveCard(
                eventName = eventName,
                defaults =
                    DefaultCardSpec(
                        title = meta.defaultTitle,
                        subtitle = meta.defaultSubtitle,
                        artworkUrl = payload.image,
                        actions = defaultActions
                    ),
                context = context
            )

        return resolved.toCard(meta.level, eventName) { copy(fields = buildSeerrCardFields(payload, mediaLabel)) }
    }
}

private data class SeerrMetadata(
    val defaultTitle: String,
    val defaultSubtitle: String,
    val level: NotificationLevel,
    val requestIcon: String,
    val requestAction: String
)

/** How a Seerr event is announced: its icon, the action in the title, the subtitle suffix and the card level. */
private data class SeerrStyle(
    val icon: String,
    val action: String,
    val subtitleSuffix: String,
    val level: NotificationLevel
)

private val SEERR_STYLES =
    mapOf(
        EventType.REQUEST_PENDING to SeerrStyle("🛎️", "New Request", "Request Pending", NotificationLevel.WARNING),
        EventType.REQUEST_APPROVED to
            SeerrStyle(
                "✅",
                "Request Approved",
                "Request Approved",
                NotificationLevel.SUCCESS
            ),
        EventType.REQUEST_AUTO_APPROVED to
            SeerrStyle("✅", "Request Auto-Approved", "Request Auto-Approved", NotificationLevel.SUCCESS),
        EventType.REQUEST_AVAILABLE to
            SeerrStyle("🍿", "Request Available", "Media Available", NotificationLevel.SUCCESS),
        EventType.REQUEST_DECLINED to SeerrStyle("❌", "Request Declined", "Request Declined", NotificationLevel.ERROR),
        EventType.REQUEST_FAILED to
            SeerrStyle("🚨", "Request Failed", "Request Processing Failed", NotificationLevel.ERROR),
        EventType.ISSUE_CREATED to SeerrStyle("⚠️", "Issue Reported", "Issue Report", NotificationLevel.WARNING),
        EventType.ISSUE_COMMENT to SeerrStyle("💬", "Issue Comment", "Issue Update", NotificationLevel.INFO),
        EventType.ISSUE_RESOLVED to SeerrStyle("✅", "Issue Resolved", "Issue Resolved", NotificationLevel.SUCCESS),
        EventType.ISSUE_REOPENED to SeerrStyle("⚠️", "Issue Reopened", "Issue Reopened", NotificationLevel.WARNING)
    )

/** Any other Seerr notification: titled with just the subject. */
private val SEERR_FALLBACK_STYLE = SeerrStyle("🔔", "Notification", "Notification", NotificationLevel.INFO)

private fun resolveSeerrMetadata(
    payload: MediaPayload.SeerrEvent,
    appName: String
): SeerrMetadata {
    val style = SEERR_STYLES[payload.eventType]
    val shown = style ?: SEERR_FALLBACK_STYLE
    val title =
        if (style !=
            null
        ) {
            "${shown.icon} ${shown.action}: ${payload.subject}"
        } else {
            "${shown.icon} ${payload.subject}"
        }
    return SeerrMetadata(title, "$appName • ${shown.subtitleSuffix}", shown.level, shown.icon, shown.action)
}

private val SEERR_ISSUE_EVENTS =
    setOf(
        EventType.ISSUE_CREATED,
        EventType.ISSUE_COMMENT,
        EventType.ISSUE_RESOLVED,
        EventType.ISSUE_REOPENED
    )

private fun resolveSeerrMediaLabel(mediaType: String?): String? =
    mediaType?.takeIf { it.isNotBlank() }?.let {
        when (it.lowercase()) {
            "movie" -> "🎬 Movie"
            "tv" -> "📺 TV Series"
            else -> it.replaceFirstChar { c -> c.uppercase() }
        }
    }

private fun buildSeerrContext(
    payload: MediaPayload.SeerrEvent,
    meta: SeerrMetadata,
    appName: String,
    mediaLabel: String?
): Map<String, Any?> =
    mapOf(
        "title" to meta.defaultTitle,
        "subject" to payload.subject,
        "request_icon" to meta.requestIcon,
        "request_action" to meta.requestAction,
        "request_status" to meta.defaultSubtitle.substringAfter(" • "),
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

private fun buildSeerrActions(
    webUrl: String?,
    appName: String,
    isIssue: Boolean
): List<ActionLink> =
    if (!webUrl.isNullOrBlank()) {
        val actionLabel = if (isIssue) "⚠️ View Issue in $appName" else "🌐 Open in $appName"
        listOf(ActionLink(label = actionLabel, url = webUrl, style = ActionStyle.PRIMARY))
    } else {
        emptyList()
    }

private fun buildSeerrCardFields(
    payload: MediaPayload.SeerrEvent,
    mediaLabel: String?
): List<CardField> {
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
    return fields
}
