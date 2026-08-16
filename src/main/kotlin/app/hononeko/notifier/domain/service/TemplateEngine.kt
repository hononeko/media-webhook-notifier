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
        val customTemplate = config.events[eventName]

        val title =
            if (!customTemplate?.title.isNullOrBlank()) {
                interpolate(customTemplate!!.title, context)
            } else {
                defaultTitle
            }

        val subtitle =
            if (!customTemplate?.subtitle.isNullOrBlank()) {
                interpolate(customTemplate!!.subtitle, context)
            } else {
                defaultSubtitle
            }

        val artworkUrl =
            if (!customTemplate?.artworkUrl.isNullOrBlank()) {
                val url = interpolate(customTemplate!!.artworkUrl, context)
                url.ifBlank { defaultArtworkUrl }
            } else {
                defaultArtworkUrl
            }

        val actions =
            if (customTemplate != null && customTemplate.actions.isNotEmpty()) {
                renderActions(customTemplate.actions, context)
            } else {
                defaultActions
            }

        val bodyText =
            if (!customTemplate?.body.isNullOrBlank()) {
                interpolateBody(customTemplate!!.body, context)
            } else {
                defaultBody
            }

        return NotificationCard(
            title = title,
            subtitle = subtitle,
            overview = bodyText,
            level = defaultLevel,
            artworkUrl = artworkUrl,
            actions = actions
        )
    }

    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US).trim()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
