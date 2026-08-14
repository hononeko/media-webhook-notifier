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
                progressPercent = 45,
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
        assertEquals(45, downloading.progressPercent)

        val completed =
            downloading.copy(
                progressPercent = 100,
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
}
