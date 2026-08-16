package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookDtoTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Test
    fun `test ServarrWebhookDto full serialization and deserialization`() {
        val dto =
            ServarrWebhookDto(
                eventType = "Download",
                series =
                    ServarrSeriesDto(
                        id = 1,
                        title = "Futurama",
                        path = "/tv/Futurama",
                        tvdbId = 73801,
                        tmdbId = 615,
                        imdbId = "tt0149460",
                        type = "standard",
                        year = 1999,
                        overview = "Fry is frozen...",
                        images =
                            listOf(
                                ServarrImageDto(
                                    coverType = "poster",
                                    url = "http://poster.jpg",
                                    remoteUrl = "https://remote.jpg"
                                )
                            )
                    ),
                movie =
                    ServarrMovieDto(
                        id = 2,
                        title = "Dune: Part Two",
                        year = 2024,
                        tmdbId = 693134,
                        imdbId = "tt15239678",
                        overview = "Paul Atreides unites...",
                        images =
                            listOf(
                                ServarrImageDto(coverType = "poster", url = "http://dune.jpg")
                            ),
                        movieFile =
                            ServarrMovieFileDto(
                                id = 10,
                                relativePath = "Dune.mkv",
                                path = "/movies/Dune.mkv",
                                quality = "2160p",
                                qualityVersion = 1,
                                releaseGroup = "FLUX",
                                size = 25000000000L,
                                videoCodec = "hevc",
                                audioCodec = "truehd",
                                mediaInfo =
                                    ServarrMediaInfoDto(
                                        audioChannels = 7.1,
                                        audioCodec = "truehd",
                                        videoCodec = "hevc",
                                        videoDynamicRange = "HDR10",
                                        videoDynamicRangeType = "HDR",
                                        resolution = "3840x2160"
                                    )
                            )
                    ),
                episodes =
                    listOf(
                        ServarrEpisodeDto(
                            id = 100,
                            episodeNumber = 1,
                            seasonNumber = 1,
                            title = "Space Pilot 3000",
                            overview = "Philip J. Fry...",
                            episodeFile =
                                ServarrEpisodeFileDto(
                                    id = 200,
                                    relativePath = "S01E01.mkv",
                                    path = "/tv/Futurama/S01E01.mkv",
                                    quality = "1080p",
                                    qualityVersion = 1,
                                    releaseGroup = "NTb",
                                    size = 2000000000L,
                                    videoCodec = "x265",
                                    audioCodec = "aac",
                                    mediaInfo =
                                        ServarrMediaInfoDto(
                                            audioChannels = 2.0,
                                            audioCodec = "aac",
                                            videoCodec = "x265",
                                            videoDynamicRange = null,
                                            videoDynamicRangeType = null,
                                            resolution = "1920x1080"
                                        )
                                )
                        )
                    ),
                release =
                    ServarrReleaseDto(
                        quality = "1080p",
                        qualityVersion = 1,
                        releaseGroup = "NTb",
                        releaseTitle = "Futurama.S01E01.1080p.WEB-DL",
                        indexer = "TorrentLeech",
                        size = 2000000000L,
                        customFormatScore = 1500
                    ),
                downloadId = "hashABC",
                downloadClient = "qBittorrent",
                isUpgrade = true,
                upgrade = ServarrUpgradeDto(isUpgrade = true),
                level = "info",
                message = "Download completed",
                type = "DiskSpace",
                wikiUrl = "https://wiki.servarr.com",
                reason = "Upgrade",
                instanceName = "Sonarr-TV",
                applicationUrl = "http://sonarr.lan:8989"
            )

        val serialized = json.encodeToString(ServarrWebhookDto.serializer(), dto)
        val deserialized = json.decodeFromString(ServarrWebhookDto.serializer(), serialized)

        assertEquals(dto.eventType, deserialized.eventType)
        assertEquals(dto.series?.title, deserialized.series?.title)
        assertEquals(dto.movie?.title, deserialized.movie?.title)
        assertEquals(dto.episodes.first().title, deserialized.episodes.first().title)
        assertEquals(
            dto.episodes
                .first()
                .episodeFile
                ?.mediaInfo
                ?.audioChannels,
            2.0
        )
        assertEquals(
            dto.movie
                ?.movieFile
                ?.mediaInfo
                ?.videoDynamicRange,
            "HDR10"
        )
        assertEquals(dto.release?.customFormatScore, 1500)
        assertEquals(dto.upgrade?.isUpgrade, true)
    }

    @Test
    fun `test JellyfinWebhookDto full serialization and deserialization`() {
        val dto =
            JellyfinWebhookDto(
                notificationType = "ItemAdded",
                itemType = "Episode",
                seriesName = "Severance",
                seasonNumber = 2,
                episodeNumber = 1,
                itemId = "jelly123",
                name = "Hello Mark",
                overview = "The workplace...",
                year = 2025,
                serverId = "server1",
                serverName = "HomeMedia",
                serverUrl = "http://jellyfin.lan:8096",
                videoCodec = "hevc",
                audioCodec = "aac",
                resolution = "4k",
                posterUrl = "http://jellyfin.lan/poster.jpg"
            )

        val serialized = json.encodeToString(JellyfinWebhookDto.serializer(), dto)
        val deserialized = json.decodeFromString(JellyfinWebhookDto.serializer(), serialized)

        assertEquals("ItemAdded", deserialized.notificationType)
        assertEquals("Severance", deserialized.seriesName)
        assertEquals(2, deserialized.seasonNumber)
        assertEquals("HomeMedia", deserialized.serverName)
        assertEquals("hevc", deserialized.videoCodec)
        assertEquals("4k", deserialized.resolution)
    }

    @Test
    fun `test PlexWebhookDto full serialization and deserialization`() {
        val dto =
            PlexWebhookDto(
                event = "library.new",
                user = true,
                owner = true,
                server = PlexServerDto(title = "PlexServer", uuid = "uuid-1234"),
                metadata =
                    PlexMetadataDto(
                        librarySectionType = "movie",
                        ratingKey = "12345",
                        key = "/library/metadata/12345",
                        title = "Inception",
                        year = 2010,
                        summary = "A thief enters dreams...",
                        duration = 8880000L,
                        rating = 9.0,
                        thumb = "/library/metadata/12345/thumb",
                        art = "/library/metadata/12345/art",
                        media =
                            listOf(
                                PlexMediaStreamDto(
                                    videoCodec = "hevc",
                                    audioCodec = "dts",
                                    videoResolution = "4k",
                                    container = "mkv",
                                    duration = 8880000L,
                                    bitrate = 25000000L,
                                    width = 3840,
                                    height = 2160
                                )
                            )
                    )
            )

        val serialized = json.encodeToString(PlexWebhookDto.serializer(), dto)
        val deserialized = json.decodeFromString(PlexWebhookDto.serializer(), serialized)

        assertEquals("library.new", deserialized.event)
        assertEquals("Inception", deserialized.metadata?.title)
        assertEquals("PlexServer", deserialized.server?.title)
        assertEquals(1, deserialized.metadata?.media?.size)
        assertEquals(
            "hevc",
            deserialized.metadata
                ?.media
                ?.first()
                ?.videoCodec
        )
        assertEquals(
            3840,
            deserialized.metadata
                ?.media
                ?.first()
                ?.width
        )
    }

    @Test
    fun `test WebhookReceiptDto serialization`() {
        val dto =
            WebhookReceiptDto(
                status = "accepted",
                message = "Webhook accepted for processing",
                eventType = "Grab",
                timestamp = "2026-08-16T22:00:00Z"
            )

        val serialized = json.encodeToString(WebhookReceiptDto.serializer(), dto)
        val deserialized = json.decodeFromString(WebhookReceiptDto.serializer(), serialized)

        assertEquals("accepted", deserialized.status)
        assertEquals("Grab", deserialized.eventType)
        assertEquals("2026-08-16T22:00:00Z", deserialized.timestamp)
    }
}
