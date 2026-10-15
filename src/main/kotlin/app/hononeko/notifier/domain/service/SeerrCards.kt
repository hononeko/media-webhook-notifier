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

        if (resolved.customBody != null) {
            return NotificationCard(
                title = resolved.title,
                subtitle = resolved.subtitle,
                level = meta.level,
                customBody = resolved.customBody,
                artworkUrl = resolved.artworkUrl,
                actions = resolved.actions,
                eventType = eventName,
                silent = resolved.silent
            )
        }

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            level = meta.level,
            fields = buildSeerrCardFields(payload, mediaLabel),
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = eventName,
            silent = resolved.silent
        )
    }
}

private data class SeerrMetadata(
    val defaultTitle: String,
    val defaultSubtitle: String,
    val level: NotificationLevel,
    val requestIcon: String,
    val requestAction: String
)

private fun resolveSeerrMetadata(
    payload: MediaPayload.SeerrEvent,
    appName: String
): SeerrMetadata =
    when (payload.eventType) {
        EventType.REQUEST_PENDING ->
            SeerrMetadata(
                "🛎️ New Request: ${payload.subject}",
                "$appName • Request Pending",
                NotificationLevel.WARNING,
                "🛎️",
                "New Request"
            )
        EventType.REQUEST_APPROVED, EventType.REQUEST_AUTO_APPROVED -> {
            val approvedType =
                if (payload.eventType == EventType.REQUEST_AUTO_APPROVED) "Auto-Approved" else "Approved"
            SeerrMetadata(
                "✅ Request $approvedType: ${payload.subject}",
                "$appName • Request $approvedType",
                NotificationLevel.SUCCESS,
                "✅",
                "Request $approvedType"
            )
        }
        EventType.REQUEST_AVAILABLE ->
            SeerrMetadata(
                "🍿 Request Available: ${payload.subject}",
                "$appName • Media Available",
                NotificationLevel.SUCCESS,
                "🍿",
                "Request Available"
            )
        EventType.REQUEST_DECLINED ->
            SeerrMetadata(
                "❌ Request Declined: ${payload.subject}",
                "$appName • Request Declined",
                NotificationLevel.ERROR,
                "❌",
                "Request Declined"
            )
        EventType.REQUEST_FAILED ->
            SeerrMetadata(
                "🚨 Request Failed: ${payload.subject}",
                "$appName • Request Processing Failed",
                NotificationLevel.ERROR,
                "🚨",
                "Request Failed"
            )
        EventType.ISSUE_CREATED ->
            SeerrMetadata(
                "⚠️ Issue Reported: ${payload.subject}",
                "$appName • Issue Report",
                NotificationLevel.WARNING,
                "⚠️",
                "Issue Reported"
            )
        EventType.ISSUE_COMMENT ->
            SeerrMetadata(
                "💬 Issue Comment: ${payload.subject}",
                "$appName • Issue Update",
                NotificationLevel.INFO,
                "💬",
                "Issue Comment"
            )
        EventType.ISSUE_RESOLVED ->
            SeerrMetadata(
                "✅ Issue Resolved: ${payload.subject}",
                "$appName • Issue Resolved",
                NotificationLevel.SUCCESS,
                "✅",
                "Issue Resolved"
            )
        EventType.ISSUE_REOPENED ->
            SeerrMetadata(
                "⚠️ Issue Reopened: ${payload.subject}",
                "$appName • Issue Reopened",
                NotificationLevel.WARNING,
                "⚠️",
                "Issue Reopened"
            )
        else ->
            SeerrMetadata(
                "🔔 ${payload.subject}",
                "$appName • Notification",
                NotificationLevel.INFO,
                "🔔",
                "Notification"
            )
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
