package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardFormatterServiceTest {
    @Test
    fun `should draw progress bar accurately with sub-blocks and brackets`() {
        assertEquals("[░░░░░░░░░░]", CardFormatterService.drawProgressBar(0, 10))
        assertEquals("[███▌░░░░░░]", CardFormatterService.drawProgressBar(35, 10))
        assertEquals("[█████░░░░░]", CardFormatterService.drawProgressBar(50, 10))
        assertEquals("[██████▌░░░]", CardFormatterService.drawProgressBar(65, 10))
        assertEquals("[██████████]", CardFormatterService.drawProgressBar(100, 10))
        assertEquals("[██████████]", CardFormatterService.drawProgressBar(120, 10))
        assertEquals("[░░░░░░░░░░]", CardFormatterService.drawProgressBar(-10, 10))
    }

    @Test
    fun `should format durations correctly`() {
        assertEquals("0s", CardFormatterService.formatDuration(0))
        assertEquals("45s", CardFormatterService.formatDuration(45))
        assertEquals("2m 30s", CardFormatterService.formatDuration(150))
        assertEquals("1h 15m", CardFormatterService.formatDuration(4500))
        assertEquals("∞", CardFormatterService.formatDuration(-1))
        assertEquals("∞", CardFormatterService.formatDuration(9999999))
    }

    @Test
    fun `should format byte sizes and speed correctly`() {
        assertEquals("500 B", CardFormatterService.formatBytes(500))
        assertEquals("1.5 MB", CardFormatterService.formatBytes(1572864))
        assertEquals("4.00 GB", CardFormatterService.formatBytes(4294967296L))
        assertEquals("1.50 TB", CardFormatterService.formatBytes(1649267441664L))
        assertEquals("14.2 MB/s", CardFormatterService.formatSpeed(14889779))
    }

    @Test
    fun `should format peers and seeders correctly`() {
        assertEquals(
            "12 (45) seeds • 4 (18) peers",
            CardFormatterService.formatPeers(12, 45, 4, 18)
        )
        assertEquals(
            "12 seeds • 4 peers",
            CardFormatterService.formatPeers(12, 0, 4, 0)
        )
    }

    @Test
    fun `should format episode ranges correctly`() {
        assertNull(CardFormatterService.formatEpisodeRange(null, emptyList()))
        assertNull(CardFormatterService.formatEpisodeRange(1, emptyList()))
        assertEquals("S02E01", CardFormatterService.formatEpisodeRange(2, listOf(1)))
        assertEquals("S02E01-E08", CardFormatterService.formatEpisodeRange(2, (1..8).toList()))
        assertEquals("S01E01,E03,E05", CardFormatterService.formatEpisodeRange(1, listOf(1, 3, 5)))
    }

    @Test
    fun `should truncate long overviews gracefully on word boundary`() {
        assertNull(CardFormatterService.truncateOverview(null))
        assertNull(CardFormatterService.truncateOverview("   "))

        val shortText = "A short overview."
        assertEquals(shortText, CardFormatterService.truncateOverview(shortText))

        val longText =
            "Lumon Industries employees undergo a surgical procedure called severance " +
                "to separate their work memories from non-work memories, leading to eerie consequences."
        val truncated = CardFormatterService.truncateOverview(longText, 50)
        assertNotNull(truncated)
        assertTrue(truncated.endsWith("..."))
        assertTrue(truncated.length <= 53)
    }

    @Test
    fun `should build initial grab card correctly`() {
        val payload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash123",
                title = "Severance - S02E01 - Hello",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                quality = "2160p",
                releaseGroup = "NTb",
                sizeBytes = 5368709120L,
                posterUrl = "https://cdn.example.com/poster.jpg"
            )

        val card = CardFormatterService.buildGrabInitialCard(payload, "https://qbit.example.com")
        assertEquals("⏳ Queueing Download: Severance (S02E01)", card.title)
        assertEquals(NotificationLevel.PROGRESS, card.level)
        assertEquals(3, card.fields.size)
        assertEquals("https://cdn.example.com/poster.jpg", card.artworkUrl)
        assertEquals(1, card.actions.size)
        assertEquals(ActionStyle.PRIMARY, card.actions.first().style)
    }

    @Test
    fun `should build progress update correctly`() {
        val payload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash123",
                title = "Severance - S02E01 - Hello",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                instanceName = "Sonarr-Main"
            )

        val progress =
            TorrentProgress(
                hash = "hash123",
                name = "Severance.S02E01",
                progressPercent = 65.0,
                progressRatio = 0.65,
                downloadSpeedBytesPerSec = 10485760,
                uploadSpeedBytesPerSec = 524288,
                etaSeconds = 120,
                totalSizeBytes = 5368709120L,
                downloadedBytes = 3489660928L,
                seedsCount = 25,
                seedsTotal = 50,
                peersCount = 8,
                peersTotal = 20,
                state = TorrentState.DOWNLOADING
            )

        val update = CardFormatterService.buildProgressUpdate(payload, progress, "https://qbit.example.com")
        assertEquals("Severance (S02E01)", update.title)
        assertEquals("Sonarr-Main", update.subtitle)
        assertEquals(65.0, update.percent)
        assertEquals("[██████▌░░░]", update.progressBar)
        assertEquals("10.0 MB/s", update.speedFormatted)
        assertEquals("2m 0s", update.etaFormatted)
        assertEquals("Downloading", update.stateText)
    }

    @Test
    fun `should build completion and stalled cards correctly`() {
        val payload =
            MediaPayload.ArrGrab(
                source = AppSource.RADARR,
                downloadId = "hash456",
                title = "Dune: Part Two (2024)",
                seriesOrMovieTitle = "Dune: Part Two",
                quality = "2160p Remux"
            )

        val progress =
            TorrentProgress(
                hash = "hash456",
                name = "Dune.Part.Two.2024",
                progressPercent = 100.0,
                progressRatio = 1.0,
                downloadSpeedBytesPerSec = 0,
                uploadSpeedBytesPerSec = 1048576,
                etaSeconds = 0,
                totalSizeBytes = 26843545600L,
                downloadedBytes = 26843545600L,
                state = TorrentState.COMPLETED
            )

        val completionCard = CardFormatterService.buildCompletionCard(payload, progress, "https://qbit.example.com")
        assertEquals("✅ Download Complete: Dune: Part Two (2024)", completionCard.title)
        assertEquals(NotificationLevel.SUCCESS, completionCard.level)

        val stalledCard =
            CardFormatterService.buildStalledCard(
                payload,
                progress.copy(state = TorrentState.STALLED),
                "https://qbit.example.com"
            )
        assertEquals("⚠️ Download Stalled: Dune: Part Two (2024)", stalledCard.title)
        assertEquals(NotificationLevel.WARNING, stalledCard.level)
    }

    @Test
    fun `should build Plex and Jellyfin available cards correctly`() {
        val plex =
            MediaPayload.PlexLibraryNew(
                title = "Dune: Part Two",
                year = 2024,
                summary = "Epic sci-fi journey...",
                rating = 8.6,
                durationSeconds = 9960,
                videoCodec = "4K HEVC",
                audioCodec = "TrueHD Atmos",
                posterUrl = "https://plex.example.com/poster.jpg",
                deepLinkUrl = "https://app.plex.tv/desktop#!/server/123/details"
            )

        val plexCard = CardFormatterService.buildAvailableCard(plex)
        assertEquals("🍿 Now Available: Dune: Part Two (2024)", plexCard.title)
        assertEquals("Plex Media Server", plexCard.subtitle)
        assertEquals("8.6/10", plexCard.mediaSpecs?.score)
        assertEquals("2h 46m", plexCard.mediaSpecs?.duration)
        assertEquals(1, plexCard.actions.size)
        assertEquals("🎬 Watch on Plex", plexCard.actions.first().label)

        val jellyfin =
            MediaPayload.JellyfinItemAdded(
                itemId = "item123",
                title = "Hello World",
                seriesName = "Severance",
                overview = "Episode description...",
                videoCodec = "HEVC",
                audioCodec = "EAC3",
                deepLinkUrl = "https://jellyfin.example.com/item123"
            )

        val jellyfinCard = CardFormatterService.buildAvailableCard(jellyfin)
        assertEquals("🍿 Now Available: Severance - Hello World", jellyfinCard.title)
        assertEquals("Jellyfin Media Server", jellyfinCard.subtitle)
        assertEquals("🍿 Watch on Jellyfin", jellyfinCard.actions.first().label)
    }

    @Test
    fun `should build import and upgrade cards correctly`() {
        val importPayload =
            MediaPayload.ArrDownload(
                source = AppSource.SONARR,
                title = "Severance - S02E01 - Hello World",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                videoCodec = "HEVC",
                audioCodec = "EAC3",
                resolution = "2160p",
                isUpgrade = false,
                instanceName = "Sonarr 4K"
            )

        val importCard = CardFormatterService.buildImportCard(importPayload)
        assertEquals("📁 File Imported: Severance (S02E01)", importCard.title)
        assertEquals("Sonarr 4K • Library Import", importCard.subtitle)
        assertEquals(NotificationLevel.SUCCESS, importCard.level)

        val upgradePayload =
            importPayload.copy(
                isUpgrade = true
            )
        val upgradeCard = CardFormatterService.buildImportCard(upgradePayload)
        assertEquals("⬆️ File Upgraded: Severance (S02E01)", upgradeCard.title)
        assertEquals("Sonarr 4K • Quality Upgrade", upgradeCard.subtitle)
    }

    @Test
    fun `should build health cards for warning, error, and restored statuses`() {
        val warningPayload =
            MediaPayload.ServarrHealth(
                source = AppSource.SONARR,
                eventType = app.hononeko.notifier.domain.model.EventType.HEALTH_ISSUE,
                level = "warning",
                message = "Indexer connection unstable",
                type = "IndexersUnavailable",
                wikiUrl = "https://wiki.servarr.com/indexers",
                instanceName = "Sonarr-Anime"
            )
        val warningCard = CardFormatterService.buildHealthCard(warningPayload)
        assertEquals("⚠️ Health Warning: Sonarr-Anime", warningCard.title)
        assertEquals("Sonarr-Anime • Health Warning", warningCard.subtitle)
        assertEquals(NotificationLevel.WARNING, warningCard.level)
        assertEquals("Indexer connection unstable", warningCard.fields.first { it.name == "Message" }.value)
        assertEquals("IndexersUnavailable", warningCard.fields.first { it.name == "Issue Type" }.value)
        assertEquals("📖 Open Wiki", warningCard.actions.first().label)

        val errorPayload =
            warningPayload.copy(
                level = "error",
                message = "All download clients are offline"
            )
        val errorCard = CardFormatterService.buildHealthCard(errorPayload)
        assertEquals("🚨 Health Error: Sonarr-Anime", errorCard.title)
        assertEquals(NotificationLevel.ERROR, errorCard.level)

        val restoredPayload =
            warningPayload.copy(
                eventType = app.hononeko.notifier.domain.model.EventType.HEALTH_RESTORED,
                level = "ok",
                message = "Indexer connection restored"
            )
        val restoredCard = CardFormatterService.buildHealthCard(restoredPayload)
        assertEquals("✅ Health Restored: Sonarr-Anime", restoredCard.title)
        assertEquals(NotificationLevel.SUCCESS, restoredCard.level)
    }

    @Test
    fun `should build manual interaction required card correctly`() {
        val manualPayload =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.RADARR,
                title = "Dune: Part Two (2024)",
                seriesOrMovieTitle = "Dune: Part Two",
                releaseTitle = "Dune.Part.Two.2024.2160p.WEB-DL",
                quality = "2160p",
                sizeBytes = 15728640000L,
                indexer = "Prowlarr",
                downloadClient = "qBittorrent",
                downloadId = "hash123",
                reason = "Sample file detected or unrecognized audio stream",
                posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
                webUrl = "http://radarr:7878/activity/queue",
                instanceName = "Radarr-4K"
            )

        val card = CardFormatterService.buildManualInteractionCard(manualPayload)
        assertEquals("✋ Manual Import Required: Dune: Part Two (2024)", card.title)
        assertEquals("Radarr-4K • Manual Intervention", card.subtitle)
        assertEquals(NotificationLevel.WARNING, card.level)
        assertEquals(
            "Sample file detected or unrecognized audio stream",
            card.fields.first { it.name == "Reason" }.value
        )
        assertEquals("Dune.Part.Two.2024.2160p.WEB-DL", card.fields.first { it.name == "Release" }.value)
        assertEquals("2160p", card.fields.first { it.name == "Quality" }.value)
        assertEquals("qBittorrent", card.fields.first { it.name == "Client" }.value)
        assertEquals("📁 Open in Radarr", card.actions.first().label)
    }
}
