package app.hononeko.notifier.domain.model

data class NotificationCard(
    val title: String,
    val subtitle: String? = null,
    val overview: String? = null,
    val level: NotificationLevel = NotificationLevel.INFO,
    val fields: List<CardField> = emptyList(),
    val mediaSpecs: MediaSpecs? = null,
    val artworkUrl: String? = null,
    val actions: List<ActionLink> = emptyList()
)

enum class NotificationLevel {
    INFO,
    PROGRESS,
    SUCCESS,
    WARNING,
    ERROR
}

data class CardField(
    val name: String,
    val value: String,
    val inline: Boolean = true
)

data class MediaSpecs(
    val video: String? = null,
    val audio: String? = null,
    val resolution: String? = null,
    val sizeFormatted: String? = null,
    val score: String? = null,
    val duration: String? = null,
    val releaseGroup: String? = null
)
