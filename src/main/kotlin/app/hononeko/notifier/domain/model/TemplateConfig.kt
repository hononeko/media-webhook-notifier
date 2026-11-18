package app.hononeko.notifier.domain.model

data class TemplateConfig(
    val theme: ThemeConfig = ThemeConfig(),
    val events: Map<String, EventTemplate> = emptyMap()
)

data class ThemeConfig(
    val maxOverviewLength: Int = 220,
    val progressBarLength: Int = 10,
    val progressBarStyle: String = "default",
    val dateFormat: String = "yyyy-MM-dd HH:mm"
)

/**
 * One event's template overrides. Null fields fall back to the built-in defaults.
 *
 * [enabled] `false` suppresses the event entirely. [preferPoster] `true` makes media-server cards use the poster URL
 * instead of the image the media server uploaded with the webhook.
 */
data class EventTemplate(
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val artworkUrl: String? = null,
    val imageEmbed: Boolean? = null,
    val stateText: String? = null,
    val silent: Boolean? = null,
    val actions: List<TemplateActionConfig> = emptyList(),
    val enabled: Boolean? = null,
    val preferPoster: Boolean? = null,
    val chatId: String? = null,
    val topicId: Long? = null
) {
    /** Returns a copy where null or empty fields fall back to [base]. */
    fun withDefaults(base: EventTemplate): EventTemplate =
        EventTemplate(
            title = title ?: base.title,
            subtitle = subtitle ?: base.subtitle,
            body = body ?: base.body,
            artworkUrl = artworkUrl ?: base.artworkUrl,
            imageEmbed = imageEmbed ?: base.imageEmbed,
            stateText = stateText ?: base.stateText,
            silent = silent ?: base.silent,
            actions = actions.ifEmpty { base.actions },
            enabled = enabled ?: base.enabled,
            preferPoster = preferPoster ?: base.preferPoster,
            chatId = chatId ?: base.chatId,
            topicId = topicId ?: base.topicId
        )
}

data class TemplateActionConfig(
    val label: String,
    val url: String? = null,
    val callback: String? = null,
    val style: String = "DEFAULT"
)
