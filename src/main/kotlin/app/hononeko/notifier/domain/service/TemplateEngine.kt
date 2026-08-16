package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.TemplateActionConfig
import app.hononeko.notifier.domain.model.TemplateConfig
import java.util.Locale
import java.util.regex.Pattern

data class ResolvedTemplateCard(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val actions: List<ActionLink>,
    val customBody: String?,
    val imageEmbedEnabled: Boolean = true
)

data class ResolvedTemplateProgress(
    val title: String,
    val subtitle: String?,
    val actions: List<ActionLink>,
    val customBody: String?
)

data class DefaultCardSpec(
    val title: String,
    val subtitle: String? = null,
    val level: NotificationLevel = NotificationLevel.INFO,
    val body: String? = null,
    val artworkUrl: String? = null,
    val actions: List<ActionLink> = emptyList()
)

class TemplateEngine(
    private val config: TemplateConfig = TemplateConfig()
) {
    companion object {
        private val TAG_PATTERN: Pattern = Pattern.compile("\\{([a-zA-Z0-9_-]+)}")
    }

    val theme get() = config.theme

    fun getEventTemplate(eventName: String): EventTemplate? =
        config.events[eventName]
            ?: when (eventName) {
                "grab" ->
                    config.events["servarr.grab"] ?: config.events["servarr_grab"] ?: config.events["arr.grab"]
                        ?: config.events["download.grab"] ?: config.events["download_grab"]
                        ?: config.events["torrent.grab"]
                "download_progress", "progress" ->
                    config.events["download_progress"] ?: config.events["download.progress"]
                        ?: config.events["progress"]
                "download_complete", "complete" ->
                    config.events["download_complete"] ?: config.events["download.complete"]
                        ?: config.events["complete"]
                "download_stalled", "stalled" ->
                    config.events["download_stalled"] ?: config.events["download.stalled"] ?: config.events["stalled"]
                "import" -> config.events["import"] ?: config.events["servarr.import"] ?: config.events["arr.import"]
                "manual_interaction" ->
                    config.events["manual_interaction"] ?: config.events["servarr.manual_interaction"]
                        ?: config.events["arr.manual_interaction"]
                "health" -> config.events["health"] ?: config.events["servarr.health"] ?: config.events["system.health"]
                "media_available", "available" ->
                    config.events["media_available"] ?: config.events["media_server.available"]
                        ?: config.events["media.available"]
                        ?: config.events["available"]
                "request" ->
                    config.events["request"] ?: config.events["seerr.request"]
                        ?: config.events["seerr_request"]
                "issue" ->
                    config.events["issue"] ?: config.events["seerr.issue"] ?: config.events["seerr_issue"]
                        ?: getEventTemplate("request")
                else -> null
            }

    fun interpolate(
        template: String?,
        context: Map<String, Any?>
    ): String {
        if (template.isNullOrEmpty()) return ""
        val matcher = TAG_PATTERN.matcher(template)
        val sb = StringBuilder()
        while (matcher.find()) {
            val tag = matcher.group(1)
            val value = context[tag]?.toString() ?: ""
            matcher.appendReplacement(
                sb,
                java.util.regex.Matcher
                    .quoteReplacement(value)
            )
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    fun interpolateBody(
        bodyTemplate: String?,
        context: Map<String, Any?>
    ): String {
        if (bodyTemplate.isNullOrBlank()) return ""
        val renderedLines = bodyTemplate.lines().mapNotNull { renderBodyLine(it, context) }
        return collapseContiguousBlankLines(renderedLines)
    }

    private fun renderBodyLine(
        line: String,
        context: Map<String, Any?>
    ): String? {
        if (line.isBlank()) return ""
        val matcher = TAG_PATTERN.matcher(line)
        var hasTags = false
        while (matcher.find()) {
            hasTags = true
            val tag = matcher.group(1)
            if (context[tag]?.toString().isNullOrBlank()) {
                return null
            }
        }
        return if (hasTags) interpolate(line, context).ifBlank { null } else line
    }

    private fun collapseContiguousBlankLines(lines: List<String>): String {
        val result = mutableListOf<String>()
        var prevBlank = false
        for (l in lines) {
            val isBlank = l.isBlank()
            if (isBlank) {
                if (!prevBlank && result.isNotEmpty()) {
                    result.add("")
                }
                prevBlank = true
            } else {
                result.add(l)
                prevBlank = false
            }
        }
        return result.joinToString("\n").trim()
    }

    fun renderActions(
        actionConfigs: List<TemplateActionConfig>,
        context: Map<String, Any?>
    ): List<ActionLink> {
        val result = mutableListOf<ActionLink>()
        for (action in actionConfigs) {
            val label = interpolate(action.label, context)
            val rawUrl = action.url?.let { interpolate(it, context) }
            if (label.isNotBlank() && !rawUrl.isNullOrBlank() && isValidUrl(rawUrl)) {
                val style =
                    try {
                        ActionStyle.valueOf(action.style.uppercase(Locale.US))
                    } catch (_: Exception) {
                        ActionStyle.DEFAULT
                    }
                result.add(ActionLink(label = label, url = rawUrl, style = style))
            }
        }
        return result
    }

    fun resolveCard(
        eventName: String,
        defaultTitle: String,
        defaultSubtitle: String?,
        defaultArtworkUrl: String?,
        defaultActions: List<ActionLink>,
        context: Map<String, Any?>
    ): ResolvedTemplateCard {
        val customTemplate = getEventTemplate(eventName)
        val title =
            customTemplate?.title?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultTitle

        val subtitle =
            customTemplate?.subtitle?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultSubtitle

        val imageEmbedEnabled = customTemplate?.imageEmbed != false
        val artworkUrl =
            if (!imageEmbedEnabled) {
                null
            } else {
                customTemplate?.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                    interpolate(it, context).ifBlank { null }
                } ?: defaultArtworkUrl
            }

        val actions =
            if (customTemplate != null && customTemplate.actions.isNotEmpty()) {
                renderActions(customTemplate.actions, context)
            } else {
                defaultActions
            }

        val customBody =
            customTemplate?.body?.takeIf { it.isNotBlank() }?.let {
                interpolateBody(it, context)
            }

        return ResolvedTemplateCard(
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            actions = actions,
            customBody = customBody,
            imageEmbedEnabled = imageEmbedEnabled
        )
    }

    fun resolveProgress(
        eventName: String = "download_progress",
        defaultTitle: String,
        defaultSubtitle: String?,
        defaultActions: List<ActionLink>,
        context: Map<String, Any?>
    ): ResolvedTemplateProgress {
        val customTemplate = getEventTemplate(eventName)
        val title =
            customTemplate?.title?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultTitle

        val subtitle =
            customTemplate?.subtitle?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultSubtitle

        val actions =
            if (customTemplate != null && customTemplate.actions.isNotEmpty()) {
                renderActions(customTemplate.actions, context)
            } else {
                defaultActions
            }

        val customBody =
            customTemplate?.body?.takeIf { it.isNotBlank() }?.let {
                interpolateBody(it, context)
            }

        return ResolvedTemplateProgress(
            title = title,
            subtitle = subtitle,
            actions = actions,
            customBody = customBody
        )
    }

    fun renderCard(
        eventName: String,
        defaults: DefaultCardSpec,
        context: Map<String, Any?>
    ): NotificationCard {
        val resolved =
            resolveCard(
                eventName = eventName,
                defaultTitle = defaults.title,
                defaultSubtitle = defaults.subtitle,
                defaultArtworkUrl = defaults.artworkUrl,
                defaultActions = defaults.actions,
                context = context
            )

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = resolved.customBody ?: defaults.body,
            level = defaults.level,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = eventName
        )
    }

    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US).trim()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
