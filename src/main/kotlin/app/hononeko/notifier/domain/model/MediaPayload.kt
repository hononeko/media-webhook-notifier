package app.hononeko.notifier.domain.model

sealed interface MediaPayload {
    val source: AppSource
    val eventType: EventType

    data class ArrGrab(
        override val source: AppSource,
        override val eventType: EventType = EventType.GRAB,
        val downloadId: String,
        val title: String,
        val seriesOrMovieTitle: String,
        val seasonNumber: Int? = null,
        val episodeNumbers: List<Int> = emptyList(),
        val releaseGroup: String? = null,
        val quality: String? = null,
        val sizeBytes: Long? = null,
        val indexer: String? = null,
        val posterUrl: String? = null,
        val instanceName: String? = null
    ) : MediaPayload

    data class ArrDownload(
        override val source: AppSource,
        override val eventType: EventType = EventType.DOWNLOAD,
        val downloadId: String? = null,
        val title: String,
        val seriesOrMovieTitle: String,
        val seasonNumber: Int? = null,
        val episodeNumbers: List<Int> = emptyList(),
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val quality: String? = null,
        val isUpgrade: Boolean = false,
        val sizeBytes: Long? = null,
        val posterUrl: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val instanceName: String? = null,
        val webUrl: String? = null
    ) : MediaPayload

    data class PlexLibraryNew(
        override val source: AppSource = AppSource.PLEX,
        override val eventType: EventType = EventType.MEDIA_AVAILABLE,
        val title: String,
        val grandParentTitle: String? = null,
        val parentTitle: String? = null,
        val year: Int? = null,
        val summary: String? = null,
        val rating: Double? = null,
        val durationSeconds: Long? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val posterUrl: String? = null,
        val ratingKey: String? = null,
        val serverMachineIdentifier: String? = null,
        val deepLinkUrl: String? = null
    ) : MediaPayload

    data class JellyfinItemAdded(
        override val source: AppSource = AppSource.JELLYFIN,
        override val eventType: EventType = EventType.MEDIA_AVAILABLE,
        val itemId: String,
        val serverId: String? = null,
        val title: String,
        val seriesName: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val year: Int? = null,
        val overview: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val resolution: String? = null,
        val posterUrl: String? = null,
        val deepLinkUrl: String? = null
    ) : MediaPayload
}
