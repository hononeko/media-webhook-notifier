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
    val imageEmbedEnabled: Boolean = true,
    val silent: Boolean = false,
    val chatId: String? = null,
    val topicId: Long? = null
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
    val actions: List<ActionLink> = emptyList(),
    val silent: Boolean = false
)

class TemplateEngine(
    private val config: TemplateConfig = TemplateConfig()
) {
    companion object {
        private val TAG_PATTERN: Pattern = Pattern.compile("\\{([a-zA-Z0-9_-]+)}")
        private val EVENT_ALIAS_GROUPS: List<List<String>> =
            listOf(
                listOf(
                    "grab",
                    "servarr.grab",
                    "servarr_grab",
                    "arr.grab",
                    "arr_grab",
                    "download.grab",
                    "download_grab",
                    "torrent.grab",
                    "torrent_grab"
                ),
                listOf(
                    "download_progress",
                    "download.progress",
                    "progress",
                    "torrent.progress",
                    "torrent_progress"
                ),
                listOf(
                    "download_complete",
                    "download.complete",
                    "complete",
                    "torrent.complete",
                    "torrent_complete"
                ),
                listOf(
                    "download_stalled",
                    "download.stalled",
                    "stalled",
                    "torrent.stalled",
                    "torrent_stalled"
                ),
                listOf("import", "servarr.import", "servarr_import", "arr.import", "arr_import"),
                listOf(
                    "manual_interaction",
                    "servarr.manual_interaction",
                    "servarr_manual_interaction",
                    "arr.manual_interaction",
                    "arr_manual_interaction"
                ),
                listOf("health", "servarr.health", "servarr_health", "system.health", "system_health"),
                listOf(
                    "media_available",
                    "media_server.available",
                    "media_server_available",
                    "media.available",
                    "available"
                ),
                listOf("request", "seerr.request", "seerr_request"),
                listOf("issue", "seerr.issue", "seerr_issue"),
                listOf("media_server.status_online", "media_server_status_online", "status_online"),
                listOf("media_server.status_offline", "media_server_status_offline", "status_offline"),
                listOf(
                    "cross_seed.match",
                    "cross_seed_match",
                    "cross-seed.match",
                    "cross-seed_match",
                    "crossseed.match",
                    "crossseed_match",
                    "match"
                ),
                listOf(
                    "cross_seed.failure",
                    "cross_seed_failure",
                    "cross-seed.failure",
                    "cross-seed_failure",
                    "crossseed.failure",
                    "crossseed_failure",
                    "failure"
                )
            )

        private val EVENT_ALIASES_BY_KEY: Map<String, List<String>> =
            buildMap {
                for (group in EVENT_ALIAS_GROUPS) {
                    for (key in group) {
                        put(key, group)
                    }
                }
            }

        private val EVENT_FALLBACKS: Map<String, List<String>> =
            mapOf("issue" to listOf("request", "seerr.request", "seerr_request"))

        /** All equivalent template keys for [eventName], or `[eventName]` itself for custom event names. */
        fun eventAliasesFor(eventName: String): List<String> = EVENT_ALIASES_BY_KEY[eventName] ?: listOf(eventName)
    }

    val theme get() = config.theme

    fun getEventTemplate(eventName: String): EventTemplate? =
        config.events[eventName]
            ?: eventAliasesFor(eventName).firstNotNullOfOrNull { config.events[it] }
            ?: EVENT_FALLBACKS[eventName]?.firstNotNullOfOrNull { config.events[it] }

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
        defaults: DefaultCardSpec,
        context: Map<String, Any?>
    ): ResolvedTemplateCard {
        val customTemplate = getEventTemplate(eventName)
        val title =
            customTemplate?.title?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaults.title

        val subtitle =
            customTemplate?.subtitle?.takeIf { it.isNotBlank() }?.let {
                interpolate(it, context)
            } ?: defaults.subtitle

        val imageEmbedEnabled = customTemplate?.imageEmbed != false
        val artworkUrl =
            if (!imageEmbedEnabled) {
                null
            } else {
                customTemplate?.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                    interpolate(it, context).ifBlank { null }
                } ?: defaults.artworkUrl
            }

        val actions =
            if (customTemplate != null && customTemplate.actions.isNotEmpty()) {
                renderActions(customTemplate.actions, context)
            } else {
                defaults.actions
            }

        val customBody =
            customTemplate?.body?.takeIf { it.isNotBlank() }?.let {
                interpolateBody(it, context)
            }

        val silent = customTemplate?.silent ?: defaults.silent

        return ResolvedTemplateCard(
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            actions = actions,
            customBody = customBody,
            imageEmbedEnabled = imageEmbedEnabled,
            silent = silent,
            chatId = customTemplate?.chatId?.trim()?.takeIf { it.isNotEmpty() },
            topicId = customTemplate?.topicId
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
                defaults = defaults,
                context = context
            )

        return NotificationCard(
            title = resolved.title,
            subtitle = resolved.subtitle,
            overview = resolved.customBody ?: defaults.body,
            level = defaults.level,
            artworkUrl = resolved.artworkUrl,
            actions = resolved.actions,
            eventType = eventName,
            silent = resolved.silent,
            chatId = resolved.chatId,
            topicId = resolved.topicId
        )
    }

    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US).trim()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}

/** Whether [eventName] is announced at all: only `enabled: false` (or `disabled: true`) turns it off. */
fun TemplateEngine.isEnabled(eventName: String): Boolean = getEventTemplate(eventName)?.enabled != false
