package app.hononeko.notifier.config

import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.TemplateActionConfig
import app.hononeko.notifier.domain.model.TemplateConfig
import app.hononeko.notifier.domain.model.ThemeConfig

/** Turns a parsed templates YAML tree into [TemplateConfig]: the theme, the events and their aliases. */
internal object TemplateConfigMapper {
    private val TEMPLATE_KEYS =
        setOf(
            "title",
            "subtitle",
            "body",
            "artwork_url",
            "poster_url",
            "image_embed",
            "image_embed_enabled",
            "embed_image",
            "send_photos",
            "send_photo",
            "photo",
            "photos",
            "state_text",
            "silent",
            "silent_send",
            "disable_notification",
            "actions"
        )

    @Suppress("UNCHECKED_CAST")
    fun mapToTemplateConfig(
        root: Map<String, Any?>,
        defaultTheme: ThemeConfig = ThemeConfig()
    ): TemplateConfig {
        val themeMap = root["theme"] as? Map<String, Any?>
        val theme =
            if (themeMap != null) {
                ThemeConfig(
                    maxOverviewLength =
                        (themeMap["max_overview_length"] as? Number)?.toInt() ?: defaultTheme.maxOverviewLength,
                    progressBarLength =
                        (themeMap["progress_bar_length"] as? Number)?.toInt() ?: defaultTheme.progressBarLength,
                    progressBarStyle = themeMap["progress_bar_style"]?.toString() ?: defaultTheme.progressBarStyle,
                    dateFormat = themeMap["date_format"]?.toString() ?: defaultTheme.dateFormat
                )
            } else {
                defaultTheme
            }

        val eventsMap = root["events"] as? Map<String, Any?> ?: emptyMap()
        val events = parseEventsMap(eventsMap)

        return TemplateConfig(
            theme = theme,
            events = events
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventsMap(eventsMap: Map<String, Any?>): Map<String, EventTemplate> {
        val events = mutableMapOf<String, EventTemplate>()
        for ((key, obj) in eventsMap) {
            val map = obj as? Map<String, Any?> ?: continue
            if (isEventTemplateMap(map)) {
                events[key] = parseEventTemplate(map)
            } else {
                parseNestedEvents(events, key, map)
            }
        }
        return events
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNestedEvents(
        events: MutableMap<String, EventTemplate>,
        category: String,
        nestedMap: Map<String, Any?>
    ) {
        for ((subKey, subObj) in nestedMap) {
            val subMap = subObj as? Map<String, Any?> ?: continue
            val template = parseEventTemplate(subMap)
            registerEventAliases(events, category, subKey, template)
        }
    }

    private fun registerEventAliases(
        events: MutableMap<String, EventTemplate>,
        category: String,
        subKey: String,
        template: EventTemplate
    ) {
        events["$category.$subKey"] = template
        events["${category}_$subKey"] = template
        events[subKey] = template

        if (category == "media_server" || category == "media") {
            events["media_available"] = template
            events["media.available"] = template
            events["media_server.available"] = template
            events["media_server_available"] = template
            events["available"] = template
        }
        if (category == "download" || category == "torrent") {
            events["download_$subKey"] = template
            events["torrent_$subKey"] = template
        }
    }

    private fun isEventTemplateMap(map: Map<String, Any?>): Boolean = map.keys.any { it in TEMPLATE_KEYS }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventTemplate(eventProps: Map<String, Any?>): EventTemplate {
        val rawActions = eventProps["actions"] as? List<*> ?: emptyList<Any?>()
        val actionsList = rawActions.mapNotNull { parseAction(it) }

        val imageEmbed = parseImageEmbedFlag(eventProps)
        val silent = parseSilentFlag(eventProps)

        return EventTemplate(
            title = eventProps["title"]?.toString(),
            subtitle = eventProps["subtitle"]?.toString(),
            body = eventProps["body"]?.toString(),
            artworkUrl = eventProps["artwork_url"]?.toString() ?: eventProps["poster_url"]?.toString(),
            imageEmbed = imageEmbed,
            stateText = eventProps["state_text"]?.toString(),
            silent = silent,
            actions = actionsList
        )
    }

    private fun parseBooleanValue(v: Any?): Boolean? {
        if (v == null) return null
        if (v is Boolean) return v
        val str = v.toString().trim()
        if (str.equals("true", ignoreCase = true) || str.equals("yes", ignoreCase = true) || str == "1") {
            return true
        }
        if (str.equals("false", ignoreCase = true) || str.equals("no", ignoreCase = true) || str == "0") {
            return false
        }
        return null
    }

    private fun parseSilentFlag(eventProps: Map<String, Any?>): Boolean? {
        val keys = listOf("silent", "silent_send", "disable_notification")
        for (k in keys) {
            val parsed = parseBooleanValue(eventProps[k])
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseImageEmbedFlag(eventProps: Map<String, Any?>): Boolean? {
        val keys =
            listOf(
                "image_embed",
                "image_embed_enabled",
                "embed_image",
                "send_photos",
                "send_photo",
                "photo",
                "photos"
            )
        for (k in keys) {
            val parsed = parseBooleanValue(eventProps[k])
            if (parsed != null) return parsed
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAction(actionItem: Any?): TemplateActionConfig? {
        if (actionItem !is Map<*, *>) return null
        val actionMap = actionItem as Map<String, Any?>
        val label = actionMap["label"]?.toString() ?: return null
        val url = actionMap["url"]?.toString()
        val callback = actionMap["callback"]?.toString()
        val style = actionMap["style"]?.toString() ?: "DEFAULT"
        return TemplateActionConfig(
            label = label,
            url = url,
            callback = callback,
            style = style
        )
    }
}
