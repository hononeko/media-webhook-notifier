package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.MediaPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Fallbacks for payloads that leave out the file, download info or status messages. */
class ServarrPayloadFallbackTest {
    private fun download(body: String): MediaPayload.ArrDownload {
        val payload = assertIs<WebhookProcessResult.Queued>(processServarr(SonarrWebhookProvider(), body)).payload
        return assertIs<MediaPayload.ArrDownload>(payload)
    }

    private fun manualInteraction(body: String): MediaPayload.ServarrManualInteraction {
        val payload = assertIs<WebhookProcessResult.Queued>(processServarr(SonarrWebhookProvider(), body)).payload
        return assertIs<MediaPayload.ServarrManualInteraction>(payload)
    }

    @Test
    fun `Download without a file object falls back to the release quality and size`() {
        val payload =
            download(
                """
                {
                    "eventType": "Download",
                    "series": { "title": "Silo" },
                    "release": { "quality": "WEBDL-1080p", "size": 1500000000 }
                }
                """.trimIndent()
            )

        assertEquals("WEBDL-1080p", payload.quality)
        assertEquals("WEBDL-1080p", payload.resolution)
        assertEquals(1500000000, payload.sizeBytes)
        assertNull(payload.videoCodec)
        assertNull(payload.audioCodec)
    }

    @Test
    fun `Download with neither file nor release leaves the specs empty`() {
        val payload = download("""{ "eventType": "Download", "series": { "title": "Silo" } }""")

        assertNull(payload.quality)
        assertNull(payload.resolution)
        assertNull(payload.sizeBytes)
    }

    @Test
    fun `Download with an unknown height shows the file quality as resolution`() {
        val payload =
            download(
                """
                {
                    "eventType": "Download",
                    "series": { "title": "Silo" },
                    "episodeFile": {
                        "quality": "HDTV-720p",
                        "size": 900000000,
                        "mediaInfo": { "videoCodec": "x264", "height": 0, "width": 0 }
                    }
                }
                """.trimIndent()
            )

        assertEquals("HDTV-720p", payload.resolution)
        assertEquals("x264", payload.videoCodec)
    }

    @Test
    fun `Download with a file but no mediaInfo keeps the file quality and size`() {
        val payload =
            download(
                """
                {
                    "eventType": "Download",
                    "series": { "title": "Silo" },
                    "episodeFile": { "quality": "WEBDL-720p", "size": 800000000 },
                    "release": { "quality": "WEBDL-1080p", "size": 1500000000 }
                }
                """.trimIndent()
            )

        assertEquals("WEBDL-720p", payload.quality)
        assertEquals("WEBDL-720p", payload.resolution)
        assertEquals(800000000, payload.sizeBytes)
        assertNull(payload.videoCodec)
    }

    @Test
    fun `ManualInteractionRequired without downloadInfo uses the release details`() {
        val payload =
            manualInteraction(
                """
                {
                    "eventType": "ManualInteractionRequired",
                    "series": { "title": "Severance" },
                    "release": { "quality": "WEBDL-1080p", "size": 2000000000, "indexer": "TL" },
                    "downloadStatusMessages": [{ "title": "Severance.S02E01", "messages": ["No files found"] }]
                }
                """.trimIndent()
            )

        assertEquals("WEBDL-1080p", payload.quality)
        assertEquals(2000000000, payload.sizeBytes)
        assertEquals("TL", payload.indexer)
    }

    @Test
    fun `ManualInteractionRequired prefers downloadInfo fields and falls back per field`() {
        val payload =
            manualInteraction(
                """
                {
                    "eventType": "ManualInteractionRequired",
                    "series": { "title": "Severance" },
                    "downloadInfo": { "title": "Severance.S02E01" },
                    "release": { "quality": "WEBDL-1080p", "size": 2000000000, "indexer": "TL" }
                }
                """.trimIndent()
            )

        assertEquals("WEBDL-1080p", payload.quality)
        assertEquals(2000000000, payload.sizeBytes)
        assertEquals("TL", payload.indexer)
    }

    @Test
    fun `ManualInteractionRequired without download details leaves them empty`() {
        val payload =
            manualInteraction("""{ "eventType": "ManualInteractionRequired", "series": { "title": "Severance" } }""")

        assertNull(payload.quality)
        assertNull(payload.sizeBytes)
        assertNull(payload.indexer)
        assertNull(payload.reason)
    }

    @Test
    fun `status messages without text use their title, skip blanks and drop duplicates`() {
        val payload =
            manualInteraction(
                """
                {
                    "eventType": "ManualInteractionRequired",
                    "series": { "title": "Severance" },
                    "downloadStatusMessages": [
                        { "title": "Severance.S02E01.mkv", "messages": [] },
                        { "title": "sample.mkv", "messages": ["Sample", " ", "Sample"] },
                        { "messages": [] }
                    ]
                }
                """.trimIndent()
            )

        assertEquals("Severance.S02E01.mkv\nSample", payload.reason)
    }

    @Test
    fun `blank status messages fall back to the payload message`() {
        val payload =
            manualInteraction(
                """
                {
                    "eventType": "ManualInteractionRequired",
                    "series": { "title": "Severance" },
                    "downloadStatusMessages": [{ "title": " ", "messages": [] }],
                    "message": "Manual import required"
                }
                """.trimIndent()
            )

        assertEquals("Manual import required", payload.reason)
    }
}
