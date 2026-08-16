package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.TemplateConfig
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
        assertEquals(0, card.actions.size)
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
        assertEquals("⏳ Downloading: Severance (S02E01)", update.title)
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
        assertEquals("🍿 Dune: Part Two (2024) now available on Plex", plexCard.title)
        assertEquals(null, plexCard.subtitle)
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
        assertEquals("🍿 Severance - Hello World now available on Jellyfin", jellyfinCard.title)
        assertEquals(null, jellyfinCard.subtitle)
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
        assertEquals("2160p", importCard.mediaSpecs?.resolution)
        assertEquals("HEVC", importCard.mediaSpecs?.video)
        assertEquals("EAC3", importCard.mediaSpecs?.audio)
        assertEquals(null, importCard.mediaSpecs?.sizeFormatted)

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
        assertEquals(0, warningCard.actions.size)

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

    @Test
    fun `should build Seerr notification cards for requests and issues correctly`() {
        val pendingPayload =
            MediaPayload.SeerrEvent(
                source = AppSource.SEERR,
                eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_PENDING,
                notificationType = "MEDIA_PENDING",
                subject = "Severance (2022)",
                message = "New request submitted by John.",
                mediaType = "tv",
                requestedByUsername = "John",
                is4k = true,
                webUrl = "https://overseerr.example.com/tv/123",
                instanceName = "Overseerr"
            )

        val pendingCard = CardFormatterService.buildSeerrCard(pendingPayload)
        assertEquals("🛎️ New Request: Severance (2022)", pendingCard.title)
        assertEquals("Overseerr • Request Pending", pendingCard.subtitle)
        assertEquals(NotificationLevel.WARNING, pendingCard.level)
        assertEquals("John", pendingCard.fields.first { it.name == "Requested By" }.value)
        assertEquals("📺 TV Series", pendingCard.fields.first { it.name == "Media Type" }.value)
        assertEquals("4K UHD", pendingCard.fields.first { it.name == "Quality" }.value)
        assertEquals("🌐 Open in Overseerr", pendingCard.actions.first().label)

        val approvedPayload =
            pendingPayload.copy(
                eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_APPROVED
            )
        val approvedCard = CardFormatterService.buildSeerrCard(approvedPayload)
        assertEquals("✅ Request Approved: Severance (2022)", approvedCard.title)
        assertEquals(NotificationLevel.SUCCESS, approvedCard.level)

        val issuePayload =
            MediaPayload.SeerrEvent(
                source = AppSource.SEERR,
                eventType = app.hononeko.notifier.domain.model.EventType.ISSUE_CREATED,
                notificationType = "ISSUE_CREATED",
                subject = "Dune: Part Two (2024)",
                issueType = "Audio",
                issueStatus = "OPEN",
                commentMessage = "Audio is out of sync in second half",
                webUrl = "https://overseerr.example.com/issues/42",
                instanceName = "Jellyseerr"
            )
        val issueCard = CardFormatterService.buildSeerrCard(issuePayload)
        assertEquals("⚠️ Issue Reported: Dune: Part Two (2024)", issueCard.title)
        assertEquals("Jellyseerr • Issue Report", issueCard.subtitle)
        assertEquals(NotificationLevel.WARNING, issueCard.level)
        assertEquals("Audio", issueCard.fields.first { it.name == "Issue Type" }.value)
        assertEquals("Audio is out of sync in second half", issueCard.fields.first { it.name == "Comment" }.value)
        assertEquals(1, issueCard.actions.size)
        assertEquals("⚠️ View Issue in Jellyseerr", issueCard.actions.first().label)
        assertEquals("https://overseerr.example.com/issues/42", issueCard.actions.first().url)

        val autoApprovedCard =
            CardFormatterService.buildSeerrCard(
                pendingPayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_AUTO_APPROVED)
            )
        assertEquals("✅ Request Auto-Approved: Severance (2022)", autoApprovedCard.title)
        assertEquals(NotificationLevel.SUCCESS, autoApprovedCard.level)

        val availableCard =
            CardFormatterService.buildSeerrCard(
                pendingPayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_AVAILABLE)
            )
        assertEquals("🍿 Request Available: Severance (2022)", availableCard.title)
        assertEquals(NotificationLevel.SUCCESS, availableCard.level)

        val declinedCard =
            CardFormatterService.buildSeerrCard(
                pendingPayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_DECLINED)
            )
        assertEquals("❌ Request Declined: Severance (2022)", declinedCard.title)
        assertEquals(NotificationLevel.ERROR, declinedCard.level)

        val failedCard =
            CardFormatterService.buildSeerrCard(
                pendingPayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_FAILED)
            )
        assertEquals("🚨 Request Failed: Severance (2022)", failedCard.title)
        assertEquals(NotificationLevel.ERROR, failedCard.level)

        val issueCommentCard =
            CardFormatterService.buildSeerrCard(
                issuePayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.ISSUE_COMMENT)
            )
        assertEquals("💬 Issue Comment: Dune: Part Two (2024)", issueCommentCard.title)
        assertEquals(NotificationLevel.INFO, issueCommentCard.level)

        val issueResolvedCard =
            CardFormatterService.buildSeerrCard(
                issuePayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.ISSUE_RESOLVED)
            )
        assertEquals("✅ Issue Resolved: Dune: Part Two (2024)", issueResolvedCard.title)
        assertEquals(NotificationLevel.SUCCESS, issueResolvedCard.level)

        val issueReopenedCard =
            CardFormatterService.buildSeerrCard(
                issuePayload.copy(eventType = app.hononeko.notifier.domain.model.EventType.ISSUE_REOPENED)
            )
        assertEquals("⚠️ Issue Reopened: Dune: Part Two (2024)", issueReopenedCard.title)
        assertEquals(NotificationLevel.WARNING, issueReopenedCard.level)

        val fallbackCard =
            CardFormatterService.buildSeerrCard(
                pendingPayload.copy(
                    eventType = app.hononeko.notifier.domain.model.EventType.UNKNOWN,
                    webUrl = null
                )
            )
        assertEquals("🔔 Severance (2022)", fallbackCard.title)
        assertEquals(NotificationLevel.INFO, fallbackCard.level)
        assertTrue(fallbackCard.actions.isEmpty())
    }

    @Test
    fun `should render custom templates with customBody and artworkBytes across all card builders`() {
        val customConfig =
            TemplateConfig(
                events =
                    mapOf(
                        "grab" to EventTemplate(body = "Custom Grab Body", imageEmbed = true),
                        "download_complete" to EventTemplate(body = "Custom Complete Body"),
                        "download_stalled" to EventTemplate(body = "Custom Stalled Body"),
                        "import" to EventTemplate(body = "Custom Import Body", imageEmbed = true),
                        "media_available" to EventTemplate(body = "Custom Available Body", imageEmbed = true),
                        "request" to EventTemplate(body = "Custom Request Body", imageEmbed = true),
                        "issue" to EventTemplate(body = "Custom Issue Body", imageEmbed = false),
                        "manual_interaction" to EventTemplate(body = "Custom Manual Body"),
                        "health" to EventTemplate(body = "Custom Health Body")
                    )
            )
        val customEngine = TemplateEngine(customConfig)
        val bytes = byteArrayOf(1, 2, 3)

        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash123",
                title = "Show S01E01",
                seriesOrMovieTitle = "Show",
                posterUrl = "https://example.com/poster.jpg"
            )
        val grabCard =
            CardFormatterService.buildGrabInitialCard(
                grab,
                "https://qbit.example.com",
                engine = customEngine
            )
        assertEquals("Custom Grab Body", grabCard.customBody)

        val progress =
            TorrentProgress(
                hash = "hash123",
                name = "Show.S01E01",
                progressPercent = 100.0,
                progressRatio = 1.0,
                downloadSpeedBytesPerSec = 0,
                uploadSpeedBytesPerSec = 0,
                etaSeconds = 0,
                totalSizeBytes = 1000L,
                downloadedBytes = 1000L,
                seedsCount = 0,
                seedsTotal = 0,
                peersCount = 0,
                peersTotal = 0,
                state = TorrentState.COMPLETED
            )
        val completeCard =
            CardFormatterService.buildCompletionCard(
                grab,
                progress,
                "https://qbit.example.com",
                engine = customEngine
            )
        assertEquals("Custom Complete Body", completeCard.customBody)

        val importPayload =
            MediaPayload.ArrDownload(
                source = AppSource.SONARR,
                title = "Show - S01E01",
                seriesOrMovieTitle = "Show"
            )
        val importCard = CardFormatterService.buildImportCard(importPayload, engine = customEngine)
        assertEquals("Custom Import Body", importCard.customBody)

        val plex =
            MediaPayload.PlexLibraryNew(
                title = "Movie",
                artworkBytes = bytes
            )
        val availableCard = CardFormatterService.buildAvailableCard(plex, engine = customEngine)
        assertEquals("Custom Available Body", availableCard.customBody)
        assertNotNull(availableCard.artworkBytes)

        val req =
            MediaPayload.SeerrEvent(
                source = AppSource.SEERR,
                eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_PENDING,
                notificationType = "MEDIA_PENDING",
                subject = "Movie",
                image = "https://example.com/poster.jpg"
            )
        val reqCard = CardFormatterService.buildSeerrCard(req, engine = customEngine)
        assertEquals("Custom Request Body", reqCard.customBody)

        val issue =
            MediaPayload.SeerrEvent(
                source = AppSource.SEERR,
                eventType = app.hononeko.notifier.domain.model.EventType.ISSUE_CREATED,
                notificationType = "ISSUE_CREATED",
                subject = "Movie Issue",
                image = "https://example.com/poster.jpg"
            )
        val issueCard = CardFormatterService.buildSeerrCard(issue, engine = customEngine)
        assertEquals("Custom Issue Body", issueCard.customBody)
        assertNull(issueCard.artworkUrl)

        val manual =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.RADARR,
                eventType = app.hononeko.notifier.domain.model.EventType.MANUAL_INTERACTION,
                title = "Movie",
                seriesOrMovieTitle = "Movie",
                releaseTitle = "Movie.2024",
                reason = "Sample"
            )
        val manualCard = CardFormatterService.buildManualInteractionCard(manual, engine = customEngine)
        assertEquals("Custom Manual Body", manualCard.customBody)

        val stalledCard =
            CardFormatterService.buildStalledCard(
                grab,
                progress.copy(state = TorrentState.STALLED),
                "https://qbit.example.com",
                engine = customEngine
            )
        assertEquals("Custom Stalled Body", stalledCard.customBody)

        val health =
            MediaPayload.ServarrHealth(
                source = AppSource.SONARR,
                eventType = app.hononeko.notifier.domain.model.EventType.HEALTH_ISSUE,
                level = "warning",
                message = "Disk space low",
                type = "DiskSpace",
                instanceName = "Sonarr"
            )
        val healthCard = CardFormatterService.buildHealthCard(health, engine = customEngine)
        assertEquals("Custom Health Body", healthCard.customBody)
    }
}
