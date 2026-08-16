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

data class EventTemplate(
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val artworkUrl: String? = null,
    val stateText: String? = null,
    val actions: List<TemplateActionConfig> = emptyList()
)

data class TemplateActionConfig(
    val label: String,
    val url: String? = null,
    val callback: String? = null,
    val style: String = "DEFAULT"
)
