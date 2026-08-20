package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.ProgressUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramHtmlFormatterTest {
    @Test
    fun `escapeHtml escapes special characters`() {
        assertEquals("&amp;&lt;&gt;", TelegramHtmlFormatter.escapeHtml("&<>"))
        assertEquals("Safe text", TelegramHtmlFormatter.escapeHtml("Safe text"))
    }

    @Test
    fun `formatSpecs formats complete and partial media specs`() {
        val fullSpecs =
            MediaSpecs(
                resolution = "2160p",
                video = "HEVC",
                audio = "TrueHD Atmos",
                score = "8.5/10",
                duration = "2h 15m",
                sizeFormatted = "45.00 GB"
            )
        val formatted = TelegramHtmlFormatter.formatSpecs(fullSpecs)
        assertEquals("2160p • HEVC • TrueHD Atmos • ⭐ 8.5/10 • 2h 15m", formatted)

        val emptySpecs = MediaSpecs()
        assertEquals("", TelegramHtmlFormatter.formatSpecs(emptySpecs))
    }

    @Test
    fun `buildCardHtml formats standard card with fields, specs, and overview`() {
        val card =
            NotificationCard(
                title = "Dune: Part Two & More <Test>",
                subtitle = "Radarr-4K",
                level = NotificationLevel.SUCCESS,
                fields = listOf(CardField("Quality", "2160p"), CardField("Size", "45 GB")),
                mediaSpecs = MediaSpecs(resolution = "4K", score = "9.0"),
                overview = "Great movie overview.",
                actions = listOf(ActionLink("Open", "https://example.com", ActionStyle.PRIMARY))
            )

        val html = TelegramHtmlFormatter.buildCardHtml(card)
        assertTrue(html.contains("<b>Dune: Part Two &amp; More &lt;Test&gt;</b>"))
        assertTrue(html.contains("<i>Radarr-4K</i>"))
        assertTrue(html.contains("▪ <b>Quality:</b> 2160p"))
        assertTrue(html.contains("▪ <b>Specs:</b> 4K • ⭐ 9.0"))
        assertTrue(html.contains("<i>Great movie overview.</i>"))
    }

    @Test
    fun `buildCardHtml formats custom body card when present`() {
        val card =
            NotificationCard(
                title = "Custom Card",
                subtitle = null,
                level = NotificationLevel.INFO,
                customBody = "▪ <b>Custom Line:</b> Value"
            )

        val html = TelegramHtmlFormatter.buildCardHtml(card)
        assertEquals("<b>Custom Card</b>\n\n▪ <b>Custom Line:</b> Value", html)
    }

    @Test
    fun `buildProgressHtml formats progress with ETA and without ETA`() {
        val withEta =
            ProgressUpdate(
                trackingKey = "key1",
                title = "⏳ Downloading: Severance S02E01",
                subtitle = "Sonarr-Main",
                percent = 45.5,
                progressBar = "[████░░░░░░]",
                speedFormatted = "12 MB/s",
                etaFormatted = "2m 30s",
                sizeFormatted = "1.5 GB / 3.0 GB",
                peersInfo = "20 seeds • 5 peers",
                stateText = "Downloading"
            )

        val htmlWithEta = TelegramHtmlFormatter.buildProgressHtml(withEta)
        assertTrue(htmlWithEta.contains("<b>⏳ Downloading: Severance S02E01</b>"))
        assertTrue(htmlWithEta.contains("<i>Sonarr-Main</i>"))
        assertTrue(htmlWithEta.contains("<code>[████░░░░░░]</code> <b>45.50%</b>"))
        assertTrue(htmlWithEta.contains("(ETA: 2m 30s)"))

        val withoutEta = withEta.copy(subtitle = null, etaFormatted = "")
        val htmlWithoutEta = TelegramHtmlFormatter.buildProgressHtml(withoutEta)
        assertTrue(!htmlWithoutEta.contains("(ETA:"))
    }

    @Test
    fun `buildProgressHtml formats custom body when present`() {
        val customProgress =
            ProgressUpdate(
                trackingKey = "key2",
                title = "Movie",
                subtitle = "Radarr",
                percent = 99.0,
                progressBar = "[██████████]",
                speedFormatted = "50 MB/s",
                etaFormatted = "1s",
                sizeFormatted = "10 GB / 10 GB",
                peersInfo = "50 seeds",
                stateText = "Downloading",
                customBody = "🚀 <b>99.0%</b> (50 MB/s)"
            )

        val html = TelegramHtmlFormatter.buildProgressHtml(customProgress)
        assertTrue(html.contains("<b>Movie</b>"))
        assertTrue(html.contains("<i>Radarr</i>"))
        assertTrue(html.contains("🚀 <b>99.0%</b> (50 MB/s)"))
    }

    @Test
    fun `buildProgressHtml formats multi-track progress with episode tracks and total progress line`() {
        val tracks =
            """
            <code>[██████████]</code> <b>100%</b> • <b>E01:</b> 2.64 GB
            <code>[████████░░]</code> <b>82.5%</b> • <b>E02:</b> 12.1 MB/s (ETA: 4s)
            <code>[████░░░░░░]</code> <b>45.0%</b> • <b>E03:</b> 8.5 MB/s (ETA: 22s)
            """.trimIndent()

        val multiTrackUpdate =
            ProgressUpdate(
                trackingKey = "multi_key",
                title = "⏳ Downloading: Love is Blind: UK (S03E01-E03)",
                subtitle = "Sonarr-TV",
                percent = 75.83,
                progressBar = "[███████░░░]",
                speedFormatted = "20.6 MB/s",
                etaFormatted = "15s",
                sizeFormatted = "6.0 GB / 7.92 GB",
                peersInfo = "15 seeds • 20 peers",
                stateText = "Downloading",
                episodeTracks = tracks
            )

        val html = TelegramHtmlFormatter.buildProgressHtml(multiTrackUpdate)
        assertTrue(html.contains("<b>⏳ Downloading: Love is Blind: UK (S03E01-E03)</b>"))
        assertTrue(html.contains("<i>Sonarr-TV</i>"))
        assertTrue(html.contains("<code>[██████████]</code> <b>100%</b> • <b>E01:</b> 2.64 GB"))
        assertTrue(html.contains("<code>[████████░░]</code> <b>82.5%</b> • <b>E02:</b> 12.1 MB/s (ETA: 4s)"))
        assertTrue(html.contains("<code>[████░░░░░░]</code> <b>45.0%</b> • <b>E03:</b> 8.5 MB/s (ETA: 22s)"))
        assertTrue(html.contains("▪ <b>Total Progress:</b> <code>[███████░░░]</code> <b>75.83%</b>"))
        assertTrue(html.contains("▪ <b>Speed:</b> 20.6 MB/s (ETA: 15s)"))
        assertTrue(html.contains("▪ <b>Transferred:</b> 6.0 GB / 7.92 GB"))
    }

    @Test
    fun `buildCardHtml skips specs and empty italic lines when blank`() {
        val cardWithEmptySpecs =
            NotificationCard(
                title = "Show",
                subtitle = "   ",
                mediaSpecs = MediaSpecs(),
                overview = ""
            )
        val html = TelegramHtmlFormatter.buildCardHtml(cardWithEmptySpecs)
        assertEquals("<b>Show</b>", html)

        val sb = StringBuilder()
        TelegramHtmlFormatter.appendItalicLine(sb, "   ")
        assertEquals("", sb.toString())
    }
}
