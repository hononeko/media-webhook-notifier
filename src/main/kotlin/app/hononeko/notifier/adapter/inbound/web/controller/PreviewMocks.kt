package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.SwarmStats

// Sample payloads the template preview renders cards for.

private const val MOCK_DUNE_TITLE = "Dune: Part Two"
private const val MOCK_DUNE_POSTER = "https://image.tmdb.org/t/p/w500/dune2.jpg"
private const val MOCK_MULTI_EPISODE_RELEASE_NAME = "Love.Is.Blind.UK.S03.1080p.WEB.H264-DEFENESTRATE"
private val MOCK_MULTI_EPISODE_NUMBERS = listOf(1, 2, 3, 4, 5)

internal fun mockMultiGrab() =
    MediaPayload.ArrGrab(
        source = AppSource.SONARR,
        downloadId = "hash_lib_01|hash_lib_02|hash_lib_03|hash_lib_04|hash_lib_05",
        title = MOCK_MULTI_EPISODE_RELEASE_NAME,
        seriesOrMovieTitle = "Love is Blind: UK",
        seasonNumber = 3,
        episodeNumbers = MOCK_MULTI_EPISODE_NUMBERS,
        releaseGroup = "DEFENESTRATE",
        releaseTitle = MOCK_MULTI_EPISODE_RELEASE_NAME,
        quality = "WEB-DL-1080p",
        sizeBytes = 14173388800L,
        indexer = "DigitalCore (API)",
        posterUrl = "https://image.tmdb.org/t/p/w500/libuk.jpg",
        instanceName = "Sonarr-TV"
    )

/** One episode of the multi-track sample: how far along it is and how fast it's moving. */
private data class MockEpisode(
    val number: Int,
    val percent: Double,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val etaSeconds: Long,
    val downloadedBytes: Long,
    val state: DownloadState
)

private const val MOCK_EPISODE_SIZE = 2834677760L

private val MOCK_EPISODES =
    listOf(
        MockEpisode(1, 100.0, 0L, 100000L, 0L, 2834677760L, DownloadState.COMPLETED),
        MockEpisode(2, 82.5, 12582912L, 50000L, 4L, 2338609152L, DownloadState.DOWNLOADING),
        MockEpisode(3, 45.0, 8388608L, 20000L, 22L, 1275605000L, DownloadState.DOWNLOADING),
        MockEpisode(4, 21.0, 4194304L, 0L, 75L, 595282330L, DownloadState.DOWNLOADING),
        MockEpisode(5, 0.0, 0L, 0L, 0L, 0L, DownloadState.QUEUED)
    )

internal fun mockMultiProgress(): DownloadItemProgress =
    DownloadItemProgress(
        id = "hash_lib_01|hash_lib_02|hash_lib_03|hash_lib_04|hash_lib_05",
        name = MOCK_MULTI_EPISODE_RELEASE_NAME,
        progressPercent = 49.70,
        progressRatio = 0.4970,
        downloadSpeedBytesPerSec = 25165824L,
        uploadSpeedBytesPerSec = 170000L,
        etaSeconds = 48L,
        totalSizeBytes = 14173388800L,
        downloadedBytes = 7044174242L,
        swarm = SwarmStats(seedsCount = 18, seedsTotal = 45, peersCount = 10, peersTotal = 25),
        state = DownloadState.DOWNLOADING,
        items =
            MOCK_EPISODES.map { ep ->
                DownloadItemProgress(
                    id = "hash_lib_0${ep.number}",
                    name = "Love.Is.Blind.UK.S03E0${ep.number}.1080p.WEB.H264-DEFENESTRATE",
                    progressPercent = ep.percent,
                    progressRatio = ep.percent / 100,
                    downloadSpeedBytesPerSec = ep.downloadSpeed,
                    uploadSpeedBytesPerSec = ep.uploadSpeed,
                    etaSeconds = ep.etaSeconds,
                    totalSizeBytes = MOCK_EPISODE_SIZE,
                    downloadedBytes = ep.downloadedBytes,
                    state = ep.state
                )
            }
    )

internal fun mockGrab() =
    MediaPayload.ArrGrab(
        source = AppSource.SONARR,
        downloadId = "mock-download-123",
        title = "Breaking Bad - S01E01 - Pilot",
        seriesOrMovieTitle = "Breaking Bad",
        seasonNumber = 1,
        episodeNumbers = listOf(1),
        releaseGroup = "FLUX",
        releaseTitle = "Breaking.Bad.S01E01.Pilot.1080p.BluRay.x264-FLUX",
        quality = "WEBDL-1080p",
        sizeBytes = 2147483648L,
        indexer = "TorrentLeech",
        posterUrl = "https://image.tmdb.org/t/p/w500/sample.jpg",
        instanceName = "Sonarr-4K"
    )

internal fun mockProgress(
    percent: Double,
    state: DownloadState
) = DownloadItemProgress(
    id = "mock-hash-123456",
    name = "Breaking Bad - S01E01 - Pilot",
    progressPercent = percent,
    progressRatio = percent / 100.0,
    downloadSpeedBytesPerSec = 15728640L,
    uploadSpeedBytesPerSec = 1048576L,
    downloadedBytes = (2147483648L * (percent / 100.0)).toLong(),
    totalSizeBytes = 2147483648L,
    etaSeconds = 45L,
    swarm = SwarmStats(seedsCount = 42, seedsTotal = 120, peersCount = 8, peersTotal = 25),
    state = state
)

internal fun mockDownload() =
    MediaPayload.ArrDownload(
        source = AppSource.RADARR,
        title = MOCK_DUNE_TITLE,
        seriesOrMovieTitle = MOCK_DUNE_TITLE,
        year = 2024,
        quality = "Remux-2160p",
        videoCodec = "HEVC",
        audioCodec = "TrueHD Atmos",
        resolution = "2160p (4K)",
        sizeBytes = 45097156608L,
        posterUrl = MOCK_DUNE_POSTER,
        overview =
            "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                "against the conspirators who destroyed his family.",
        instanceName = "Radarr-4K",
        webUrl = "https://radarr.lan:7878/movie/1"
    )

internal fun mockPlex() =
    MediaPayload.PlexLibraryNew(
        title = MOCK_DUNE_TITLE,
        year = 2024,
        summary =
            "Paul Atreides unites with Chani and the Fremen while seeking revenge " +
                "against the conspirators who destroyed his family.",
        rating = 8.6,
        durationSeconds = 9960L,
        videoCodec = "HEVC",
        audioCodec = "TrueHD Atmos",
        resolution = "2160p (4K)",
        posterUrl = MOCK_DUNE_POSTER,
        deepLinkUrl = "https://app.plex.tv/desktop#!/server/abc/details?key=/library/metadata/12345",
        instanceName = "Plex Media Server"
    )

internal fun mockHealth() =
    MediaPayload.ServarrHealth(
        source = AppSource.SONARR,
        level = "error",
        message = "Indexers are unavailable: TorrentLeech",
        type = "Indexers",
        wikiUrl = "https://wiki.servarr.com/sonarr/system#indexers-unavailable",
        instanceName = "Sonarr-4K"
    )

internal fun mockManual() =
    MediaPayload.ServarrManualInteraction(
        source = AppSource.RADARR,
        title = "$MOCK_DUNE_TITLE (2024)",
        seriesOrMovieTitle = MOCK_DUNE_TITLE,
        releaseTitle = "Dune.Part.Two.2024.2160p.UHD.Remux.TrueHD.Atmos-FLUX.mkv",
        quality = "Remux-2160p",
        sizeBytes = 45097156608L,
        indexer = "TorrentLeech",
        downloadClient = "qBittorrent",
        reason = "Found unknown movie file",
        posterUrl = MOCK_DUNE_POSTER,
        webUrl = "https://radarr.lan:7878/activity/queue",
        instanceName = "Radarr-4K"
    )

internal fun mockSeerr() =
    MediaPayload.SeerrEvent(
        eventType = EventType.REQUEST_PENDING,
        notificationType = "MEDIA_PENDING",
        subject = "$MOCK_DUNE_TITLE (2024)",
        requestedByUsername = "alice",
        mediaType = "movie",
        is4k = true,
        image = MOCK_DUNE_POSTER,
        webUrl = "https://overseerr.lan:5055/movie/1",
        instanceName = "Overseerr"
    )

internal fun mockIssue() =
    MediaPayload.SeerrEvent(
        eventType = EventType.ISSUE_CREATED,
        notificationType = "ISSUE_CREATED",
        subject = "$MOCK_DUNE_TITLE (2024)",
        requestedByUsername = "bob",
        mediaType = "movie",
        is4k = false,
        issueType = "Video",
        issueStatus = "Open",
        commentMessage = "Video playback is choppy at 45m",
        image = MOCK_DUNE_POSTER,
        webUrl = "https://overseerr.lan:5055/issues/42",
        instanceName = "Overseerr"
    )
