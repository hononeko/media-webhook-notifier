package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload

// The keys SeasonDebouncer buffers under: one buffer per title and season (or movie), per source and instance.

private val seriesMediaTypes = setOf("episode", "season", "show", "series")

internal fun debounceKey(grab: MediaPayload.ArrGrab): String {
    val title = grab.seriesOrMovieTitle.trim().lowercase()
    val source = grab.source.name.lowercase()
    val instance = (grab.instanceName ?: "").trim().lowercase()
    return if (title.isNotBlank()) {
        "$source:$instance:$title:s${grab.seasonNumber ?: 0}"
    } else {
        val downloadId = grab.downloadId.trim().lowercase()
        if (downloadId.isNotBlank()) downloadId else "$source:$instance:unknown:s${grab.seasonNumber ?: 0}"
    }
}

internal fun debounceKey(download: MediaPayload.ArrDownload): String {
    val title = download.seriesOrMovieTitle.trim().lowercase()
    val source = download.source.name.lowercase()
    val instance = (download.instanceName ?: "").trim().lowercase()
    return if (title.isNotBlank()) {
        "title:$source:$instance:$title:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
    } else {
        val downloadId = download.downloadId?.trim()?.lowercase()
        if (!downloadId.isNullOrBlank()) {
            "dl:$downloadId:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
        } else {
            "title:$source:$instance:unknown:s${download.seasonNumber ?: 0}:${download.isUpgrade}"
        }
    }
}

internal fun debounceKey(plex: MediaPayload.PlexLibraryNew): String {
    val instance = (plex.instanceName ?: "").trim().lowercase()
    val isSeries =
        plex.seasonNumber != null ||
            !plex.parentTitle.isNullOrBlank() ||
            !plex.grandParentTitle.isNullOrBlank() ||
            plex.mediaType?.lowercase() in seriesMediaTypes
    return if (isSeries) {
        val series = (plex.grandParentTitle ?: plex.parentTitle ?: plex.title).trim().lowercase()
        "plex:$instance:show:$series:s${plex.seasonNumber ?: 0}"
    } else {
        val title = plex.title.trim().lowercase()
        val year = plex.year ?: 0
        "plex:$instance:movie:$title:$year"
    }
}

internal fun debounceKey(jellyfin: MediaPayload.JellyfinItemAdded): String {
    val instance = (jellyfin.instanceName ?: "").trim().lowercase()
    val isSeries =
        jellyfin.seasonNumber != null ||
            !jellyfin.seriesName.isNullOrBlank() ||
            jellyfin.mediaType?.lowercase() in seriesMediaTypes
    return if (isSeries) {
        val series = (jellyfin.seriesName ?: jellyfin.title).trim().lowercase()
        "jellyfin:$instance:show:$series:s${jellyfin.seasonNumber ?: 0}"
    } else {
        val title = jellyfin.title.trim().lowercase()
        val year = jellyfin.year ?: 0
        "jellyfin:$instance:movie:$title:$year"
    }
}
