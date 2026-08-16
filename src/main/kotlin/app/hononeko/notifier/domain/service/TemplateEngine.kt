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
    val customBody: String?
)

data class ResolvedTemplateProgress(
    val title: String,
    val subtitle: String?,
    val actions: List<ActionLink>,
    val customBody: String?
)

class TemplateEngine(
    private val config: TemplateConfig = TemplateConfig()
) {
    companion object {
        private val TAG_PATTERN: Pattern = Pattern.compile("\\{([a-zA-Z0-9_-]+)}")
    }

    val theme get() = config.theme

    fun getEventTemplate(eventName: String): EventTemplate? = config.events[eventName]

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
        val lines = bodyTemplate.lines()
        val renderedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                renderedLines.add("")
                continue
            }

            val matcher = TAG_PATTERN.matcher(line)
            var hasMissingRequiredTag = false
            val tagsInLine = mutableListOf<String>()

            while (matcher.find()) {
                val tag = matcher.group(1)
                tagsInLine.add(tag)
                val value = context[tag]?.toString()
                if (value.isNullOrBlank()) {
                    hasMissingRequiredTag = true
                    break
                }
            }

            if (hasMissingRequiredTag) {
                // Suppress line when one of its placeholders has no value
                continue
            }

            if (tagsInLine.isNotEmpty()) {
                val interpolated = interpolate(line, context)
                if (interpolated.isNotBlank()) {
                    renderedLines.add(interpolated)
                }
            } else {
                renderedLines.add(line)
            }
        }

        // Clean up excessive blank lines (max 1 contiguous blank line)
        val cleanResult = mutableListOf<String>()
        var prevBlank = false
        for (l in renderedLines) {
            val isBlank = l.isBlank()
            if (isBlank) {
                if (!prevBlank && cleanResult.isNotEmpty()) {
                    cleanResult.add("")
                }
                prevBlank = true
            } else {
                cleanResult.add(l)
                prevBlank = false
            }
        }

        return cleanResult.joinToString("\n").trim()
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
        val customTemplate = config.events[eventName]
        val title =
            customTemplate?.title?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultTitle

        val subtitle =
            customTemplate?.subtitle?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaultSubtitle

        val artworkUrl =
            customTemplate?.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context).ifBlank { null }
            } ?: defaultArtworkUrl

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
            customBody = customBody
        )
    }

    fun resolveProgress(
        eventName: String = "download_progress",
        defaultTitle: String,
        defaultSubtitle: String?,
        defaultActions: List<ActionLink>,
        context: Map<String, Any?>
    ): ResolvedTemplateProgress {
        val customTemplate = config.events[eventName]
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
        defaultTitle: String,
        defaultSubtitle: String?,
        defaultLevel: NotificationLevel,
        defaultBody: String?,
        defaultArtworkUrl: String?,
        defaultActions: List<ActionLink>,
        context: Map<String, Any?>
    ): NotificationCard {
        val resolved =
            resolveCard(
                eventName = eventName,
                defaultTitle = defaultTitle,
                defaultSubtitle = defaultSubtitle,
                defaultArtworkUrl = defaultArtworkUrl,
                defaultActions = defaultActions,
                context = context
            )

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = resolved.customBody ?: defaultBody,
            level = defaultLevel,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions
        )
    }

    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US).trim()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
