package app.hononeko.notifier.domain.model

sealed interface MediaPayload {
    val source: AppSource
    val eventType: EventType
    val instanceName: String?

    data class ArrGrab(
        override val source: AppSource,
        override val eventType: EventType = EventType.GRAB,
        val downloadId: String,
        val downloadIds: List<String> = if (downloadId.isNotBlank()) listOf(downloadId) else emptyList(),
        val title: String,
        val seriesOrMovieTitle: String,
        val seasonNumber: Int? = null,
        val episodeNumbers: List<Int> = emptyList(),
        val releaseGroup: String? = null,
        val releaseTitle: String? = null,
        val quality: String? = null,
        val sizeBytes: Long? = null,
        val indexer: String? = null,
        val posterUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload

    data class ArrDownload(
        override val source: AppSource,
        override val eventType: EventType = EventType.DOWNLOAD,
        val downloadId: String? = null,
        val title: String,
        val seriesOrMovieTitle: String,
        val seasonNumber: Int? = null,
        val episodeNumbers: List<Int> = emptyList(),
        val episodeTitle: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val quality: String? = null,
        val isUpgrade: Boolean = false,
        val sizeBytes: Long? = null,
        val posterUrl: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        override val instanceName: String? = null,
        val webUrl: String? = null
    ) : MediaPayload

    data class PlexLibraryNew(
        override val source: AppSource = AppSource.PLEX,
        override val eventType: EventType = EventType.MEDIA_AVAILABLE,
        val title: String,
        val mediaType: String? = null,
        val grandParentTitle: String? = null,
        val parentTitle: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val episodeNumbers: List<Int> = if (episodeNumber != null) listOf(episodeNumber) else emptyList(),
        val year: Int? = null,
        val summary: String? = null,
        val rating: Double? = null,
        val durationSeconds: Long? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val posterUrl: String? = null,
        val parentPosterUrl: String? = null,
        val grandparentPosterUrl: String? = null,
        val artworkBytes: ByteArray? = null,
        val ratingKey: String? = null,
        val ratingKeys: List<String> = if (!ratingKey.isNullOrBlank()) listOf(ratingKey) else emptyList(),
        val addedAt: Long? = null,
        val serverMachineIdentifier: String? = null,
        val deepLinkUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PlexLibraryNew) return false
            if (source != other.source) return false
            if (eventType != other.eventType) return false
            if (title != other.title) return false
            if (mediaType != other.mediaType) return false
            if (grandParentTitle != other.grandParentTitle) return false
            if (parentTitle != other.parentTitle) return false
            if (seasonNumber != other.seasonNumber) return false
            if (episodeNumber != other.episodeNumber) return false
            if (episodeNumbers != other.episodeNumbers) return false
            if (year != other.year) return false
            if (summary != other.summary) return false
            if (rating != other.rating) return false
            if (durationSeconds != other.durationSeconds) return false
            if (videoCodec != other.videoCodec) return false
            if (audioCodec != other.audioCodec) return false
            if (resolution != other.resolution) return false
            if (posterUrl != other.posterUrl) return false
            if (parentPosterUrl != other.parentPosterUrl) return false
            if (grandparentPosterUrl != other.grandparentPosterUrl) return false
            if (artworkBytes != null) {
                if (other.artworkBytes == null) return false
                if (!artworkBytes.contentEquals(other.artworkBytes)) return false
            } else if (other.artworkBytes != null) {
                return false
            }
            if (ratingKey != other.ratingKey) return false
            if (ratingKeys != other.ratingKeys) return false
            if (addedAt != other.addedAt) return false
            if (serverMachineIdentifier != other.serverMachineIdentifier) return false
            if (deepLinkUrl != other.deepLinkUrl) return false
            if (instanceName != other.instanceName) return false
            return true
        }

        override fun hashCode(): Int {
            var result = source.hashCode()
            result = 31 * result + eventType.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + (mediaType?.hashCode() ?: 0)
            result = 31 * result + (grandParentTitle?.hashCode() ?: 0)
            result = 31 * result + (parentTitle?.hashCode() ?: 0)
            result = 31 * result + (seasonNumber?.hashCode() ?: 0)
            result = 31 * result + (episodeNumber?.hashCode() ?: 0)
            result = 31 * result + episodeNumbers.hashCode()
            result = 31 * result + (year?.hashCode() ?: 0)
            result = 31 * result + (summary?.hashCode() ?: 0)
            result = 31 * result + (rating?.hashCode() ?: 0)
            result = 31 * result + (durationSeconds?.hashCode() ?: 0)
            result = 31 * result + (videoCodec?.hashCode() ?: 0)
            result = 31 * result + (audioCodec?.hashCode() ?: 0)
            result = 31 * result + (resolution?.hashCode() ?: 0)
            result = 31 * result + (posterUrl?.hashCode() ?: 0)
            result = 31 * result + (parentPosterUrl?.hashCode() ?: 0)
            result = 31 * result + (grandparentPosterUrl?.hashCode() ?: 0)
            result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
            result = 31 * result + (ratingKey?.hashCode() ?: 0)
            result = 31 * result + ratingKeys.hashCode()
            result = 31 * result + (addedAt?.hashCode() ?: 0)
            result = 31 * result + (serverMachineIdentifier?.hashCode() ?: 0)
            result = 31 * result + (deepLinkUrl?.hashCode() ?: 0)
            result = 31 * result + (instanceName?.hashCode() ?: 0)
            return result
        }
    }

    data class JellyfinItemAdded(
        override val source: AppSource = AppSource.JELLYFIN,
        override val eventType: EventType = EventType.MEDIA_AVAILABLE,
        val itemId: String,
        val mediaType: String? = null,
        val serverId: String? = null,
        val title: String,
        val seriesName: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val episodeNumbers: List<Int> = if (episodeNumber != null) listOf(episodeNumber) else emptyList(),
        val year: Int? = null,
        val overview: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val posterUrl: String? = null,
        val deepLinkUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload

    data class ServarrHealth(
        override val source: AppSource,
        override val eventType: EventType = EventType.HEALTH_ISSUE,
        val level: String,
        val message: String,
        val type: String? = null,
        val wikiUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload

    data class ServarrManualInteraction(
        override val source: AppSource,
        override val eventType: EventType = EventType.MANUAL_INTERACTION,
        val title: String,
        val seriesOrMovieTitle: String,
        val seasonNumber: Int? = null,
        val episodeNumbers: List<Int> = emptyList(),
        val episodeTitle: String? = null,
        val releaseTitle: String? = null,
        val quality: String? = null,
        val sizeBytes: Long? = null,
        val indexer: String? = null,
        val downloadClient: String? = null,
        val downloadId: String? = null,
        val reason: String? = null,
        val posterUrl: String? = null,
        val webUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload

    data class SeerrEvent(
        override val source: AppSource = AppSource.SEERR,
        override val eventType: EventType,
        val notificationType: String,
        val subject: String,
        val message: String? = null,
        val image: String? = null,
        val mediaType: String? = null,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
        val jellyfinMediaId: String? = null,
        val requestedByUsername: String? = null,
        val requestedByEmail: String? = null,
        val requestedByAvatar: String? = null,
        val is4k: Boolean = false,
        val issueType: String? = null,
        val issueStatus: String? = null,
        val commentMessage: String? = null,
        val extra: Map<String, String> = emptyMap(),
        val webUrl: String? = null,
        override val instanceName: String? = null
    ) : MediaPayload
}
