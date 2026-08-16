package app.hononeko.notifier.domain.model

data class NotificationCard(
    val title: String,
    val subtitle: String? = null,
    val overview: String? = null,
    val level: NotificationLevel = NotificationLevel.INFO,
    val fields: List<CardField> = emptyList(),
    val mediaSpecs: MediaSpecs? = null,
    val customBody: String? = null,
    val artworkUrl: String? = null,
    val artworkBytes: ByteArray? = null,
    val actions: List<ActionLink> = emptyList(),
    val eventType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationCard) return false
        if (title != other.title) return false
        if (subtitle != other.subtitle) return false
        if (overview != other.overview) return false
        if (level != other.level) return false
        if (fields != other.fields) return false
        if (mediaSpecs != other.mediaSpecs) return false
        if (customBody != other.customBody) return false
        if (artworkUrl != other.artworkUrl) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) {
            return false
        }
        if (actions != other.actions) return false
        if (eventType != other.eventType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (subtitle?.hashCode() ?: 0)
        result = 31 * result + (overview?.hashCode() ?: 0)
        result = 31 * result + level.hashCode()
        result = 31 * result + fields.hashCode()
        result = 31 * result + (mediaSpecs?.hashCode() ?: 0)
        result = 31 * result + (customBody?.hashCode() ?: 0)
        result = 31 * result + (artworkUrl?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + actions.hashCode()
        result = 31 * result + (eventType?.hashCode() ?: 0)
        return result
    }
}

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
