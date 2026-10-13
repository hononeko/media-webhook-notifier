package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.MediaPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Feeds payloads shaped exactly like Sonarr's and Radarr's webhook classes
 * (the `Webhook*.cs` classes under `src/NzbDrone.Core/Notifications/Webhook`) through the providers.
 */
class ServarrRealPayloadTest {
    @Test
    fun `Sonarr Download reads specs from the top-level episodeFile`() {
        val result = processServarr(SonarrWebhookProvider(), servarrFixture("sonarr-download.json"))

        val payload = assertIs<WebhookProcessResult.Queued>(result).payload
        assertIs<MediaPayload.ArrDownload>(payload)
        assertEquals("Silo", payload.seriesOrMovieTitle)
        assertEquals("The Engineer", payload.episodeTitle)
        assertEquals("WEBDL-2160p", payload.quality)
        assertEquals("x265", payload.videoCodec)
        assertEquals("EAC3", payload.audioCodec)
        assertEquals("2160p", payload.resolution)
        assertEquals(6442450944, payload.sizeBytes)
        assertEquals("https://artworks.thetvdb.com/banners/silo-poster.jpg", payload.posterUrl)
    }

    @Test
    fun `Radarr Download reads specs from the top-level movieFile`() {
        val result = processServarr(RadarrWebhookProvider(), servarrFixture("radarr-download.json"))

        val payload = assertIs<WebhookProcessResult.Queued>(result).payload
        assertIs<MediaPayload.ArrDownload>(payload)
        assertEquals("Oppenheimer", payload.title)
        assertEquals(true, payload.isUpgrade)
        assertEquals("Bluray-2160p", payload.quality)
        assertEquals("HEVC", payload.videoCodec)
        assertEquals("TrueHD Atmos", payload.audioCodec)
        assertEquals("2160p", payload.resolution)
        assertEquals(75161927680, payload.sizeBytes)
    }

    @Test
    fun `ManualInteractionRequired takes the reason from status messages and quality from downloadInfo`() {
        val result = processServarr(SonarrWebhookProvider(), servarrFixture("sonarr-manual-interaction.json"))

        val payload = assertIs<WebhookProcessResult.Queued>(result).payload
        assertIs<MediaPayload.ServarrManualInteraction>(payload)
        assertEquals(
            "Found matching series via grab history, but release was matched to series by ID. " +
                "Automatic import is not possible.",
            payload.reason
        )
        assertEquals("WEBDL-1080p", payload.quality)
        assertEquals(2147483648, payload.sizeBytes)
        assertEquals("TorrentLeech", payload.indexer)
    }
}
