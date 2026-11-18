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
)

data class TemplateActionConfig(
    val label: String,
    val url: String? = null,
    val callback: String? = null,
    val style: String = "DEFAULT"
)
