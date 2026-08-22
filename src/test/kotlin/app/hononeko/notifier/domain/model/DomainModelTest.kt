package app.hononeko.notifier.domain.model

import app.hononeko.notifier.domain.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun `should construct and inspect MediaPayload ArrGrab correctly`() {
        val payload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "4a5b6c7d8e9f0123456789abcdef0123456789ab",
                title = "Severance - S02E01 - Hello World",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                releaseGroup = "NTb",
                quality = "WEBDL-2160p",
                sizeBytes = 5368709120L
            )

        assertEquals(AppSource.SONARR, payload.source)
        assertEquals(EventType.GRAB, payload.eventType)
        assertEquals("4a5b6c7d8e9f0123456789abcdef0123456789ab", payload.downloadId)
        assertEquals(2, payload.seasonNumber)
        assertEquals(listOf(1), payload.episodeNumbers)
    }

    @Test
    fun `should construct and inspect MediaPayload PlexLibraryNew correctly`() {
        val payload =
            MediaPayload.PlexLibraryNew(
                title = "Dune: Part Two",
                year = 2024,
                summary = "Paul Atreides unites with Chani...",
                rating = 8.5,
                videoCodec = "HEVC",
                audioCodec = "TrueHD Atmos",
                resolution = "4K"
            )

        assertEquals(AppSource.PLEX, payload.source)
        assertEquals(EventType.MEDIA_AVAILABLE, payload.eventType)
        assertEquals(2024, payload.year)
        assertEquals(8.5, payload.rating)
        assertNull(payload.grandParentTitle)
    }

    @Test
    fun `should construct TorrentProgress and assert states correctly`() {
        val downloading =
            TorrentProgress(
                hash = "aabbcc",
                name = "Test.Torrent",
                progressPercent = 45.0,
                progressRatio = 0.45,
                downloadSpeedBytesPerSec = 15728640,
                uploadSpeedBytesPerSec = 1048576,
                etaSeconds = 300,
                totalSizeBytes = 10737418240L,
                downloadedBytes = 4831838208L,
                seedsCount = 20,
                seedsTotal = 50,
                peersCount = 5,
                peersTotal = 10,
                state = TorrentState.DOWNLOADING
            )

        assertFalse(downloading.state.isComplete)
        assertFalse(downloading.state.isStalled)
        assertEquals(45.0, downloading.progressPercent)

        val completed =
            downloading.copy(
                progressPercent = 100.0,
                progressRatio = 1.0,
                state = TorrentState.COMPLETED
            )
        assertTrue(completed.state.isComplete)
        assertFalse(completed.state.isStalled)

        val stalled =
            downloading.copy(
                state = TorrentState.STALLED
            )
        assertTrue(stalled.state.isStalled)
    }

    @Test
    fun `should construct NotificationCard and ActionLinks correctly`() {
        val action =
            ActionLink(
                label = "Open WebUI",
                url = "https://downloads.example.com",
                style = ActionStyle.PRIMARY
            )

        val card =
            NotificationCard(
                title = "Now Available: Severance",
                subtitle = "S02E01 • Hello World",
                overview = "Mark Scout leads a team at Lumon Industries...",
                level = NotificationLevel.SUCCESS,
                fields =
                    listOf(
                        CardField(name = "Resolution", value = "2160p UHD"),
                        CardField(name = "Audio", value = "Dolby Atmos")
                    ),
                mediaSpecs =
                    MediaSpecs(
                        video = "HEVC",
                        audio = "Atmos 7.1",
                        resolution = "2160p",
                        sizeFormatted = "5.0 GB"
                    ),
                actions = listOf(action)
            )

        assertEquals("Now Available: Severance", card.title)
        assertEquals(NotificationLevel.SUCCESS, card.level)
        assertEquals(2, card.fields.size)
        assertEquals("Open WebUI", card.actions.first().label)
    }

    @Test
    fun `should verify DomainError hierarchy types`() {
        val rateLimitError =
            DomainError.NotificationError.RateLimited(
                provider = "telegram",
                retryAfterSeconds = 30
            )
        assertEquals("telegram", rateLimitError.provider)
        assertEquals(30, rateLimitError.retryAfterSeconds)

        val webhookError: DomainError = DomainError.WebhookError.MissingTorrentHash
        assertEquals(DomainError.WebhookError.MissingTorrentHash, webhookError)
    }

    @Test
    fun `should construct and inspect MediaPayload ServarrHealth and ServarrManualInteraction correctly`() {
        val health =
            MediaPayload.ServarrHealth(
                source = AppSource.SONARR,
                eventType = EventType.HEALTH_ISSUE,
                level = "warning",
                message = "Indexer connection unstable",
                type = "IndexersUnavailable",
                wikiUrl = "https://wiki.servarr.com",
                instanceName = "Sonarr-Anime"
            )

        assertEquals(AppSource.SONARR, health.source)
        assertEquals(EventType.HEALTH_ISSUE, health.eventType)
        assertEquals("warning", health.level)
        assertEquals("Indexer connection unstable", health.message)
        assertEquals("IndexersUnavailable", health.type)
        assertEquals("https://wiki.servarr.com", health.wikiUrl)
        assertEquals("Sonarr-Anime", health.instanceName)

        val manual =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.RADARR,
                eventType = EventType.MANUAL_INTERACTION,
                title = "Dune 2",
                seriesOrMovieTitle = "Dune: Part Two",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                episodeTitle = "Episode One",
                releaseTitle = "Dune.2.2024.UHD",
                reason = "Sample file detected",
                instanceName = "Radarr-4K"
            )

        assertEquals(AppSource.RADARR, manual.source)
        assertEquals(EventType.MANUAL_INTERACTION, manual.eventType)
        assertEquals("Dune 2", manual.title)
        assertEquals("Dune: Part Two", manual.seriesOrMovieTitle)
        assertEquals(2, manual.seasonNumber)
        assertEquals(listOf(1), manual.episodeNumbers)
        assertEquals("Episode One", manual.episodeTitle)
        assertEquals("Sample file detected", manual.reason)
        assertEquals("Radarr-4K", manual.instanceName)
    }

    @Test
    fun `should construct and inspect MediaPayload SeerrEvent correctly`() {
        val seerr =
            MediaPayload.SeerrEvent(
                source = AppSource.SEERR,
                eventType = EventType.REQUEST_PENDING,
                notificationType = "MEDIA_PENDING",
                subject = "Severance (2022)",
                message = "New request submitted by Bob",
                mediaType = "tv",
                tmdbId = "12345",
                requestedByUsername = "Bob",
                is4k = true,
                webUrl = "https://seerr.example.com",
                instanceName = "Overseerr"
            )

        assertEquals(AppSource.SEERR, seerr.source)
        assertEquals(EventType.REQUEST_PENDING, seerr.eventType)
        assertEquals("MEDIA_PENDING", seerr.notificationType)
        assertEquals("Severance (2022)", seerr.subject)
        assertEquals("tv", seerr.mediaType)
        assertEquals("12345", seerr.tmdbId)
        assertEquals("Bob", seerr.requestedByUsername)
        assertTrue(seerr.is4k)
        assertEquals("https://seerr.example.com", seerr.webUrl)
        assertEquals("Overseerr", seerr.instanceName)
    }

    @Test
    fun `should test NotificationCard equality and hashCode thoroughly`() {
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(1, 2, 3)
        val bytes3 = byteArrayOf(4, 5, 6)

        val card1 =
            NotificationCard(
                title = "Title",
                subtitle = "Sub",
                overview = "Overview",
                level = NotificationLevel.INFO,
                fields = listOf(CardField("K", "V")),
                mediaSpecs = MediaSpecs(video = "H264"),
                customBody = "Body",
                artworkUrl = "https://example.com/art.jpg",
                artworkBytes = bytes1,
                actions = listOf(ActionLink("A", "https://example.com")),
                eventType = "event"
            )

        val card2 = card1.copy(artworkBytes = bytes2)
        val cardWithBytes3 = card1.copy(artworkBytes = bytes3)
        val cardWithNullBytes = card1.copy(artworkBytes = null)

        assertEquals(card1, card1)
        assertEquals(card1, card2)
        assertEquals(card1.hashCode(), card2.hashCode())
        assertFalse(card1.equals("not a card"))
        assertFalse(card1.equals(null))
        assertFalse(card1 == cardWithBytes3)
        assertFalse(card1 == cardWithNullBytes)
        assertFalse(cardWithNullBytes == card1)

        val nullBytesOther = cardWithNullBytes.copy()
        assertEquals(cardWithNullBytes, nullBytesOther)

        assertFalse(card1 == card1.copy(title = "Other"))
        assertFalse(card1 == card1.copy(subtitle = "Other"))
        assertFalse(card1 == card1.copy(overview = "Other"))
        assertFalse(card1 == card1.copy(level = NotificationLevel.ERROR))
        assertFalse(card1 == card1.copy(fields = emptyList()))
        assertFalse(card1 == card1.copy(mediaSpecs = null))
        assertFalse(card1 == card1.copy(customBody = null))
        assertFalse(card1 == card1.copy(artworkUrl = null))
        assertFalse(card1 == card1.copy(actions = emptyList()))
        assertFalse(card1 == card1.copy(eventType = "other"))
    }

    @Test
    fun `should test PlexLibraryNew equality and hashCode thoroughly`() {
        val bytes1 = byteArrayOf(10, 20)
        val bytes2 = byteArrayOf(10, 20)
        val bytes3 = byteArrayOf(30, 40)

        val p1 =
            MediaPayload.PlexLibraryNew(
                source = AppSource.PLEX,
                eventType = EventType.MEDIA_AVAILABLE,
                title = "Title",
                mediaType = "movie",
                grandParentTitle = "Grand",
                parentTitle = "Parent",
                seasonNumber = 1,
                episodeNumber = 2,
                year = 2024,
                summary = "Sum",
                rating = 9.0,
                durationSeconds = 1200L,
                videoCodec = "hevc",
                audioCodec = "aac",
                resolution = "1080p",
                posterUrl = "https://example.com/p.jpg",
                parentPosterUrl = "https://example.com/parent.jpg",
                grandparentPosterUrl = "https://example.com/grand.jpg",
                artworkBytes = bytes1,
                ratingKey = "123",
                serverMachineIdentifier = "srv",
                deepLinkUrl = "https://app.plex.tv",
                instanceName = "Plex-1"
            )

        val p2 = p1.copy(artworkBytes = bytes2)
        val pWithBytes3 = p1.copy(artworkBytes = bytes3)
        val pWithNullBytes = p1.copy(artworkBytes = null)

        assertEquals(p1, p1)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
        assertFalse(p1.equals("not a payload"))
        assertFalse(p1.equals(null))
        assertFalse(p1 == pWithBytes3)
        assertFalse(p1 == pWithNullBytes)
        assertFalse(pWithNullBytes == p1)

        val nullBytesOther = pWithNullBytes.copy()
        assertEquals(pWithNullBytes, nullBytesOther)

        assertFalse(p1 == p1.copy(source = AppSource.JELLYFIN))
        assertFalse(p1 == p1.copy(eventType = EventType.GRAB))
        assertFalse(p1 == p1.copy(title = "Other"))
        assertFalse(p1 == p1.copy(mediaType = "season"))
        assertFalse(p1 == p1.copy(grandParentTitle = "Other"))
        assertFalse(p1 == p1.copy(parentTitle = "Other"))
        assertFalse(p1 == p1.copy(seasonNumber = 9))
        assertFalse(p1 == p1.copy(episodeNumber = 9))
        assertFalse(p1 == p1.copy(year = 2025))
        assertFalse(p1 == p1.copy(summary = "Other"))
        assertFalse(p1 == p1.copy(rating = 5.0))
        assertFalse(p1 == p1.copy(durationSeconds = 100L))
        assertFalse(p1 == p1.copy(videoCodec = "h264"))
        assertFalse(p1 == p1.copy(audioCodec = "mp3"))
        assertFalse(p1 == p1.copy(resolution = "720p"))
        assertFalse(p1 == p1.copy(posterUrl = null))
        assertFalse(p1 == p1.copy(parentPosterUrl = null))
        assertFalse(p1 == p1.copy(grandparentPosterUrl = null))
        assertFalse(p1 == p1.copy(ratingKey = "999"))
        assertFalse(p1 == p1.copy(serverMachineIdentifier = "srv2"))
        assertFalse(p1 == p1.copy(deepLinkUrl = null))
        assertFalse(p1 == p1.copy(instanceName = "Other"))
    }

    @Test
    fun `should construct and inspect Servarr DTOs with mediaInfo`() {
        val mediaInfo =
            app.hononeko.notifier.adapter.inbound.web.dto.ServarrMediaInfoDto(
                videoCodec = "x265",
                audioCodec = "TrueHD",
                resolution = "3840x2160",
                audioChannels = 8.0,
                videoDynamicRange = "HDR"
            )
        assertEquals("x265", mediaInfo.videoCodec)
        assertEquals("TrueHD", mediaInfo.audioCodec)
        assertEquals("3840x2160", mediaInfo.resolution)
        assertEquals(8.0, mediaInfo.audioChannels)
        assertEquals("HDR", mediaInfo.videoDynamicRange)

        val movieFile =
            app.hononeko.notifier.adapter.inbound.web.dto.ServarrMovieFileDto(
                id = 1,
                relativePath = "Movie.mkv",
                path = "/movies/Movie.mkv",
                size = 1000L,
                quality = "2160p",
                videoCodec = "x265",
                audioCodec = "TrueHD",
                mediaInfo = mediaInfo
            )
        assertEquals(1, movieFile.id)
        assertEquals("x265", movieFile.mediaInfo?.videoCodec)

        val episodeFile =
            app.hononeko.notifier.adapter.inbound.web.dto.ServarrEpisodeFileDto(
                id = 2,
                relativePath = "S01E01.mkv",
                path = "/tv/S01E01.mkv",
                size = 2000L,
                quality = "1080p",
                videoCodec = "x264",
                audioCodec = "AAC",
                mediaInfo = mediaInfo
            )
        assertEquals(2, episodeFile.id)
        assertEquals("TrueHD", episodeFile.mediaInfo?.audioCodec)
    }

    @Test
    fun `should construct and inspect Plex DTOs`() {
        val stream =
            app.hononeko.notifier.adapter.inbound.web.dto.PlexMediaStreamDto(
                videoCodec = "hevc",
                audioCodec = "truehd",
                videoResolution = "4k",
                container = "mkv",
                duration = 5000L,
                bitrate = 20000L,
                width = 3840,
                height = 1600
            )
        assertEquals("hevc", stream.videoCodec)
        assertEquals("4k", stream.videoResolution)
        assertEquals(3840, stream.width)

        val metadata =
            app.hononeko.notifier.adapter.inbound.web.dto.PlexMetadataDto(
                librarySectionType = "movie",
                ratingKey = "123",
                key = "/library/metadata/123",
                title = "Movie",
                year = 2024,
                duration = 5000000L,
                media = listOf(stream)
            )
        assertEquals("123", metadata.ratingKey)
        assertEquals(1, metadata.media.size)

        val server =
            app.hononeko.notifier.adapter.inbound.web.dto.PlexServerDto(
                title = "Server",
                uuid = "uuid-123"
            )
        assertEquals("Server", server.title)

        val dto =
            app.hononeko.notifier.adapter.inbound.web.dto.PlexWebhookDto(
                event = "library.new",
                user = true,
                owner = true,
                server = server,
                metadata = metadata
            )
        assertEquals("library.new", dto.event)
        assertEquals(true, dto.user)
        assertEquals(true, dto.owner)
    }

    @Test
    fun `should construct and inspect TemplateConfig`() {
        val eventTemplate =
            EventTemplate(
                title = "Title",
                subtitle = "Sub",
                body = "Body",
                imageEmbed = false
            )
        assertEquals("Title", eventTemplate.title)
        assertEquals("Sub", eventTemplate.subtitle)
        assertEquals("Body", eventTemplate.body)
        assertEquals(false, eventTemplate.imageEmbed)

        val theme = ThemeConfig(maxOverviewLength = 300, progressBarLength = 15, progressBarStyle = "blocks")
        assertEquals(300, theme.maxOverviewLength)
        assertEquals(15, theme.progressBarLength)
        assertEquals("blocks", theme.progressBarStyle)

        val config = TemplateConfig(theme = theme, events = mapOf("event" to eventTemplate))
        assertEquals(theme, config.theme)
        assertEquals(eventTemplate, config.events["event"])
    }
}
