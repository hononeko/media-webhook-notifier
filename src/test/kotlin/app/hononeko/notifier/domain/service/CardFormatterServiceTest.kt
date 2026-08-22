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
        assertEquals("⏳ Downloading: Severance (S02E01)", card.title)
        assertEquals(NotificationLevel.PROGRESS, card.level)
        assertEquals(4, card.fields.size)
        assertEquals("https://cdn.example.com/poster.jpg", card.artworkUrl)
        assertEquals(0, card.actions.size)

        // Single episode with episodeTitle
        val payloadWithEpTitle = payload.copy(episodeTitle = "Hello World")
        val cardWithEpTitle = CardFormatterService.buildGrabInitialCard(payloadWithEpTitle, "https://qbit.example.com")
        assertEquals("⏳ Downloading: Severance - S02E01 - Hello World", cardWithEpTitle.title)

        val payloadWithIndexer = payload.copy(indexer = "TorrentLeech", releaseTitle = "Severance.S02E01.2160p-NTb")
        val engine =
            TemplateEngine(
                TemplateConfig(
                    events =
                        mapOf(
                            "grab" to EventTemplate(title = "⏳ Downloading {title} from {indexer}")
                        )
                )
            )
        val cardWithIndexer =
            CardFormatterService.buildGrabInitialCard(
                payloadWithIndexer,
                "https://qbit.example.com",
                engine
            )
        assertEquals("⏳ Downloading Severance (S02E01) from TorrentLeech", cardWithIndexer.title)
        assertEquals("Severance.S02E01.2160p-NTb", cardWithIndexer.fields.first { it.name == "Release" }.value)
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
                episodeTitle = "Hello World",
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
        assertEquals("⏳ Downloading: Severance - S02E01 - Hello World", update.title)
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

        // TV Episode with episodeTitle
        val tvPayload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash789",
                title = "Severance - S02E01",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                episodeTitle = "Hello World",
                quality = "2160p"
            )
        val tvCompletionCard = CardFormatterService.buildCompletionCard(tvPayload, progress, "https://qbit.example.com")
        assertEquals("✅ Download Complete: Severance - S02E01 - Hello World", tvCompletionCard.title)

        val tvStalledCard =
            CardFormatterService.buildStalledCard(
                tvPayload,
                progress.copy(state = TorrentState.STALLED),
                "https://qbit.example.com"
            )
        assertEquals("⚠️ Download Stalled: Severance - S02E01 - Hello World", tvStalledCard.title)
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
    fun `should format Plex and Jellyfin TV season added cards correctly`() {
        val plexSeason =
            MediaPayload.PlexLibraryNew(
                title = "Season 3",
                parentTitle = "Futurama",
                mediaType = "season",
                seasonNumber = 3,
                year = 1999,
                summary = "After a young male is transported to the future...",
                rating = 8.8,
                durationSeconds = 1320L,
                videoCodec = "HEVC",
                audioCodec = "EAC3",
                resolution = "1080p",
                posterUrl = "https://plex.example.com/season3_poster.jpg",
                parentPosterUrl = "https://plex.example.com/series_poster.jpg",
                deepLinkUrl = "https://app.plex.tv/desktop#!/server/abc/details?key=123",
                instanceName = "Kerrlab Plex"
            )

        val plexCard = CardFormatterService.buildAvailableCard(plexSeason)
        assertEquals("🍿 Futurama - Season 3 now available on Kerrlab Plex", plexCard.title)
        assertEquals(null, plexCard.subtitle)
        assertEquals("8.8/10", plexCard.mediaSpecs?.score)
        assertNull(plexCard.mediaSpecs?.duration, "Season duration should be omitted for season-level notifications")
        assertEquals("https://plex.example.com/season3_poster.jpg", plexCard.artworkUrl)
        assertEquals(1, plexCard.actions.size)
        assertEquals("🎬 Watch on Plex", plexCard.actions.first().label)

        val jellyfinSeason =
            MediaPayload.JellyfinItemAdded(
                itemId = "season-item-99",
                mediaType = "Season",
                title = "Season 3",
                seriesName = "Futurama",
                seasonNumber = 3,
                year = 1999,
                overview = "Fry and the Planet Express crew embark on season 3 adventures.",
                videoCodec = "HEVC",
                audioCodec = "AAC",
                resolution = "1080p",
                posterUrl = "https://jellyfin.example.com/season3.jpg",
                instanceName = "Home Jellyfin"
            )

        val jellyfinCard = CardFormatterService.buildAvailableCard(jellyfinSeason)
        assertEquals("🍿 Futurama - Season 3 now available on Home Jellyfin", jellyfinCard.title)
        assertEquals(null, jellyfinCard.subtitle)
        assertNull(jellyfinCard.mediaSpecs?.duration)
        assertEquals("https://jellyfin.example.com/season3.jpg", jellyfinCard.artworkUrl)
    }

    @Test
    fun `should fallback to series poster when season poster is null for Plex season card`() {
        val plexSeasonNoThumb =
            MediaPayload.PlexLibraryNew(
                title = "Season 3",
                parentTitle = "Futurama",
                mediaType = "season",
                seasonNumber = 3,
                posterUrl = null,
                parentPosterUrl = "https://plex.example.com/series_poster.jpg",
                instanceName = "Plex"
            )

        val card = CardFormatterService.buildAvailableCard(plexSeasonNoThumb)
        assertEquals("🍿 Futurama - Season 3 now available on Plex", card.title)
        assertEquals("https://plex.example.com/series_poster.jpg", card.artworkUrl)
    }

    @Test
    fun `should format Plex and Jellyfin TV episode added cards with SxxExx and episode duration`() {
        val plexEpisode =
            MediaPayload.PlexLibraryNew(
                title = "Roswell That Ends Well",
                parentTitle = "Season 3",
                grandParentTitle = "Futurama",
                mediaType = "episode",
                seasonNumber = 3,
                episodeNumber = 1,
                durationSeconds = 1320L,
                posterUrl = "https://plex.example.com/ep_thumb.jpg",
                parentPosterUrl = "https://plex.example.com/season_poster.jpg",
                instanceName = "Kerrlab Plex"
            )

        val plexCard = CardFormatterService.buildAvailableCard(plexEpisode)
        assertEquals("🍿 Futurama - S03E01 - Roswell That Ends Well now available on Kerrlab Plex", plexCard.title)
        assertEquals("22m 0s", plexCard.mediaSpecs?.duration)
        assertEquals("https://plex.example.com/ep_thumb.jpg", plexCard.artworkUrl)

        val jellyfinEpisode =
            MediaPayload.JellyfinItemAdded(
                itemId = "ep-123",
                mediaType = "Episode",
                title = "Roswell That Ends Well",
                seriesName = "Futurama",
                seasonNumber = 3,
                episodeNumber = 1,
                posterUrl = "https://jellyfin.example.com/ep.jpg",
                instanceName = "Jellyfin"
            )

        val jellyfinCard = CardFormatterService.buildAvailableCard(jellyfinEpisode)
        assertEquals("🍿 Futurama - S03E01 - Roswell That Ends Well now available on Jellyfin", jellyfinCard.title)
    }

    @Test
    fun `should format Plex and Jellyfin media server cards across all title and type branches`() {
        // Episode with S03E01 code as title (no duplicate code in output)
        val plexEpWithCodeTitle =
            MediaPayload.PlexLibraryNew(
                title = "S03E01",
                parentTitle = "Season 3",
                grandParentTitle = "Futurama",
                mediaType = "episode",
                seasonNumber = 3,
                episodeNumber = 1,
                instanceName = "Plex"
            )
        val card1 = CardFormatterService.buildAvailableCard(plexEpWithCodeTitle)
        assertEquals("🍿 Futurama - S03E01 now available on Plex", card1.title)

        // Episode with 'Episode 1' as title (no duplicate code in output)
        val plexEpWithGenericTitle =
            MediaPayload.PlexLibraryNew(
                title = "Episode 1",
                parentTitle = "Season 3",
                grandParentTitle = "Futurama",
                mediaType = "episode",
                seasonNumber = 3,
                episodeNumber = 1,
                instanceName = "Plex"
            )
        val card2 = CardFormatterService.buildAvailableCard(plexEpWithGenericTitle)
        assertEquals("🍿 Futurama - S03E01 now available on Plex", card2.title)

        // Episode with null episodeNumber fallback
        val plexEpNoEpNumber =
            MediaPayload.PlexLibraryNew(
                title = "Special",
                grandParentTitle = "Futurama",
                mediaType = "episode",
                seasonNumber = 3,
                episodeNumber = null,
                instanceName = "Plex"
            )
        val card3 = CardFormatterService.buildAvailableCard(plexEpNoEpNumber)
        assertEquals("🍿 Futurama - Special now available on Plex", card3.title)

        // Season with non-Season title and null seasonNumber fallback
        val plexSeasonCustomTitle =
            MediaPayload.PlexLibraryNew(
                title = "Final Arc",
                parentTitle = "Attack on Titan",
                mediaType = "season",
                seasonNumber = null,
                instanceName = "Plex"
            )
        val card4 = CardFormatterService.buildAvailableCard(plexSeasonCustomTitle)
        assertEquals("🍿 Attack on Titan - Final Arc now available on Plex", card4.title)

        // Grandparent poster fallback
        val plexGrandparentPoster =
            MediaPayload.PlexLibraryNew(
                title = "S01E01",
                grandParentTitle = "Futurama",
                mediaType = "episode",
                posterUrl = null,
                parentPosterUrl = null,
                grandparentPosterUrl = "https://plex.example.com/grandparent.jpg",
                instanceName = "Plex"
            )
        val card5 = CardFormatterService.buildAvailableCard(plexGrandparentPoster)
        assertEquals("https://plex.example.com/grandparent.jpg", card5.artworkUrl)

        // Jellyfin Series mapping to show
        val jellyfinSeries =
            MediaPayload.JellyfinItemAdded(
                itemId = "show-1",
                mediaType = "Series",
                title = "Severance",
                year = 2022,
                instanceName = "Jellyfin"
            )
        val card6 = CardFormatterService.buildAvailableCard(jellyfinSeries)
        assertEquals("🍿 Severance (2022) now available on Jellyfin", card6.title)

        // Movie with no year
        val plexMovieNoYear =
            MediaPayload.PlexLibraryNew(
                title = "Standalone Movie",
                mediaType = "movie",
                year = null,
                instanceName = "Plex"
            )
        val card7 = CardFormatterService.buildAvailableCard(plexMovieNoYear)
        assertEquals("🍿 Standalone Movie now available on Plex", card7.title)
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
                episodeTitle = "Hello World",
                videoCodec = "HEVC",
                audioCodec = "EAC3",
                resolution = "2160p",
                isUpgrade = false,
                instanceName = "Sonarr 4K"
            )

        val importCard = CardFormatterService.buildImportCard(importPayload)
        assertEquals("📁 File Imported: Severance - S02E01 - Hello World", importCard.title)
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
        assertEquals("⬆️ File Upgraded: Severance - S02E01 - Hello World", upgradeCard.title)
        assertEquals("Sonarr 4K • Quality Upgrade", upgradeCard.subtitle)

        // Fallback without episodeTitle
        val noEpTitlePayload = importPayload.copy(episodeTitle = null)
        val noEpTitleCard = CardFormatterService.buildImportCard(noEpTitlePayload)
        assertEquals("📁 File Imported: Severance (S02E01)", noEpTitleCard.title)
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

        // TV series single episode with season number and episode name
        val sonarrManualSingleEp =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.SONARR,
                title = "Severance - S02E01",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                episodeTitle = "Hello World",
                releaseTitle = "Severance.S02E01.1080p",
                reason = "Sample file detected",
                instanceName = "Sonarr-TV"
            )
        val sonarrCard = CardFormatterService.buildManualInteractionCard(sonarrManualSingleEp)
        assertEquals("✋ Manual Import Required: Severance - S02E01 - Hello World", sonarrCard.title)
        assertEquals("Sonarr-TV • Manual Intervention", sonarrCard.subtitle)

        // TV series single episode with code title (no duplicate code in output)
        val sonarrManualCodeTitle =
            sonarrManualSingleEp.copy(
                episodeTitle = "S02E01"
            )
        val sonarrCodeCard = CardFormatterService.buildManualInteractionCard(sonarrManualCodeTitle)
        assertEquals("✋ Manual Import Required: Severance - S02E01", sonarrCodeCard.title)

        // TV series single episode with generic 'Episode 1' title
        val sonarrManualGenericTitle =
            sonarrManualSingleEp.copy(
                episodeTitle = "Episode 1"
            )
        val sonarrGenericCard = CardFormatterService.buildManualInteractionCard(sonarrManualGenericTitle)
        assertEquals("✋ Manual Import Required: Severance - S02E01", sonarrGenericCard.title)

        // TV series multi-episode manual interaction
        val sonarrManualMultiEp =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.SONARR,
                title = "Severance - S02E01-E03",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1, 2, 3),
                releaseTitle = "Severance.S02E01-E03.1080p",
                reason = "Multiple episodes in release",
                instanceName = "Sonarr-TV"
            )
        val sonarrMultiCard = CardFormatterService.buildManualInteractionCard(sonarrManualMultiEp)
        assertEquals("✋ Manual Import Required: Severance (S02E01-E03)", sonarrMultiCard.title)

        // TV series template resolution with season and episode tags
        val manualTemplateEngine =
            TemplateEngine(
                TemplateConfig(
                    events =
                        mapOf(
                            "manual_interaction" to
                                EventTemplate(
                                    title = "✋ Fix: {series_title} S{season}E{episode} - {episode_title}",
                                    body =
                                        "▪ <b>Reason:</b> {reason}\n" +
                                            "▪ <b>Season:</b> {season_number}\n" +
                                            "▪ <b>Ep:</b> {episode_number}"
                                )
                        )
                )
            )
        val templateCard =
            CardFormatterService.buildManualInteractionCard(
                sonarrManualSingleEp,
                engine = manualTemplateEngine
            )
        assertEquals("✋ Fix: Severance S02E01 - Hello World", templateCard.title)
        assertEquals(
            "▪ <b>Reason:</b> Sample file detected\n▪ <b>Season:</b> 2\n▪ <b>Ep:</b> 1",
            templateCard.customBody
        )
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

    @Test
    fun `should dispatch buildAvailableCard across all payload types correctly`() {
        val jellyfin =
            MediaPayload.JellyfinItemAdded(
                itemId = "j1",
                title = "Episode 1",
                seriesName = "Show"
            )
        val download =
            MediaPayload.ArrDownload(
                source = AppSource.RADARR,
                title = "Movie",
                seriesOrMovieTitle = "Movie"
            )
        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.RADARR,
                downloadId = "hash",
                title = "Movie",
                seriesOrMovieTitle = "Movie"
            )
        val health =
            MediaPayload.ServarrHealth(
                source = AppSource.SONARR,
                level = "warning",
                message = "Indexer offline"
            )
        val manual =
            MediaPayload.ServarrManualInteraction(
                source = AppSource.SONARR,
                title = "Show S01E01",
                seriesOrMovieTitle = "Show"
            )
        val seerr =
            MediaPayload.SeerrEvent(
                eventType = app.hononeko.notifier.domain.model.EventType.REQUEST_PENDING,
                notificationType = "PENDING",
                subject = "Show"
            )

        assertNotNull(CardFormatterService.buildAvailableCard(jellyfin))
        assertNotNull(CardFormatterService.buildAvailableCard(download))
        assertNotNull(CardFormatterService.buildAvailableCard(grab))
        assertNotNull(CardFormatterService.buildAvailableCard(health))
        assertNotNull(CardFormatterService.buildAvailableCard(manual))
        assertNotNull(CardFormatterService.buildAvailableCard(seerr))
    }

    @Test
    fun `should handle edge cases in formatting helpers`() {
        // Truncate overview with no spaces
        val longWord = "a".repeat(100)
        val truncated = CardFormatterService.truncateOverview(longWord, 20)
        assertTrue(truncated?.endsWith("...") == true)
        assertTrue((truncated?.length ?: 0) <= 23)

        // Duration formatting
        assertEquals("30s", CardFormatterService.formatDuration(30))
        assertEquals("5m 0s", CardFormatterService.formatDuration(300))
        assertEquals("2h 0m", CardFormatterService.formatDuration(7200))

        // Progress updates across states
        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash",
                title = "Show S01E01",
                seriesOrMovieTitle = "Show"
            )

        val states =
            listOf(
                TorrentState.CHECKING,
                TorrentState.PAUSED,
                TorrentState.QUEUED,
                TorrentState.STALLED,
                TorrentState.ALLOCATING_METADATA,
                TorrentState.UNKNOWN
            )

        for (state in states) {
            val progress =
                TorrentProgress(
                    hash = "hash",
                    name = "Show",
                    progressPercent = 50.0,
                    progressRatio = 0.5,
                    downloadSpeedBytesPerSec = 1000000,
                    uploadSpeedBytesPerSec = 50000,
                    etaSeconds = 300,
                    totalSizeBytes = 2000000000L,
                    downloadedBytes = 1000000000L,
                    seedsCount = 10,
                    seedsTotal = 20,
                    peersCount = 5,
                    peersTotal = 10,
                    state = state
                )
            val update = CardFormatterService.buildProgressUpdate(grab, progress, null)
            assertNotNull(update.subtitle)
        }
    }

    @Test
    fun `should extract episode labels accurately from various torrent release names`() {
        assertEquals(
            "E01",
            CardFormatterService.extractEpisodeLabel("Love.Is.Blind.UK.S03E01.1080p.WEB.H264-DEFENESTRATE")
        )
        assertEquals("E05", CardFormatterService.extractEpisodeLabel("Futurama.S01E05.1080p.WEBDL"))
        assertEquals("E02", CardFormatterService.extractEpisodeLabel("Severance.1x02.720p.HDTV"))
        assertEquals("E08", CardFormatterService.extractEpisodeLabel("Show.Name - Episode 8"))
        assertEquals("E03", CardFormatterService.extractEpisodeLabel("UnknownReleaseName", fallbackIndex = 3))
    }

    @Test
    fun `should format single-track download progress card correctly without episodeTracks`() {
        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "single_hash",
                title = "Severance - S02E01",
                seriesOrMovieTitle = "Severance",
                seasonNumber = 2,
                episodeNumbers = listOf(1),
                instanceName = "Sonarr-Main"
            )

        val progress =
            TorrentProgress(
                hash = "single_hash",
                name = "Severance.S02E01.1080p.WEB",
                progressPercent = 65.5,
                progressRatio = 0.655,
                downloadSpeedBytesPerSec = 15728640L,
                uploadSpeedBytesPerSec = 500000L,
                etaSeconds = 45L,
                totalSizeBytes = 3221225472L,
                downloadedBytes = 2109865984L,
                seedsCount = 25,
                seedsTotal = 50,
                peersCount = 10,
                peersTotal = 15,
                state = TorrentState.DOWNLOADING,
                items = emptyList() // Single-track
            )

        val update = CardFormatterService.buildProgressUpdate(grab, progress, "https://qbit.example.com")
        assertEquals("⏳ Downloading: Severance (S02E01)", update.title)
        assertEquals("Sonarr-Main", update.subtitle)
        assertEquals(65.5, update.percent, 0.001)
        assertNull(update.episodeTracks, "Single-track download must not have episodeTracks")
        assertEquals("Downloading", update.stateText)
    }

    @Test
    fun `should format multi-track download progress card correctly with individual episode tracks`() {
        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash1|hash2|hash3",
                title = "Love.Is.Blind.UK.S03.1080p",
                seriesOrMovieTitle = "Love is Blind: UK",
                seasonNumber = 3,
                episodeNumbers = listOf(1, 2, 3),
                instanceName = "Sonarr-TV"
            )

        val ep1 =
            TorrentProgress(
                hash = "hash1",
                name = "Love.Is.Blind.UK.S03E01.1080p.WEB",
                progressPercent = 100.0,
                progressRatio = 1.0,
                downloadSpeedBytesPerSec = 0L,
                uploadSpeedBytesPerSec = 100000L,
                etaSeconds = 0L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 2834677760L,
                state = TorrentState.COMPLETED
            )
        val ep2 =
            TorrentProgress(
                hash = "hash2",
                name = "Love.Is.Blind.UK.S03E02.1080p.WEB",
                progressPercent = 82.5,
                progressRatio = 0.825,
                downloadSpeedBytesPerSec = 12582912L,
                uploadSpeedBytesPerSec = 50000L,
                etaSeconds = 4L,
                totalSizeBytes = 2834677760L,
                downloadedBytes = 2338609152L,
                state = TorrentState.DOWNLOADING
            )
        val ep3 =
            TorrentProgress(
                hash = "hash3",
                name = "Love.Is.Blind.UK.S03E03.1080p.WEB",
                progressPercent = 45.0,
                progressRatio = 0.45,
                downloadSpeedBytesPerSec = 8388608L,
                uploadSpeedBytesPerSec = 20000L,
                etaSeconds = 22L,
                totalSizeBytes = 2866937856L,
                downloadedBytes = 1290122035L,
                state = TorrentState.DOWNLOADING
            )

        val multiProgress =
            TorrentProgress(
                hash = "hash1|hash2|hash3",
                name = "Love.Is.Blind.UK.S03.1080p",
                progressPercent = 75.83,
                progressRatio = 0.7583,
                downloadSpeedBytesPerSec = 20971520L,
                uploadSpeedBytesPerSec = 170000L,
                etaSeconds = 22L,
                totalSizeBytes = 8536293376L,
                downloadedBytes = 6463408947L,
                state = TorrentState.DOWNLOADING,
                items = listOf(ep1, ep2, ep3)
            )

        val update = CardFormatterService.buildProgressUpdate(grab, multiProgress, "https://qbit.example.com")
        assertEquals("⏳ Downloading: Love is Blind: UK (S03E01-E03)", update.title)
        assertNotNull(update.episodeTracks)
        assertTrue(update.episodeTracks!!.contains("<b>100%</b> • <b>E01:</b> 2.64 GB"))
        assertTrue(update.episodeTracks!!.contains("<b>82.5%</b> • <b>E02:</b> 12.0 MB/s (ETA: 4s)"))
        assertTrue(update.episodeTracks!!.contains("<b>45.0%</b> • <b>E03:</b> 8.0 MB/s (ETA: 22s)"))

        // Verify completion card includes Episodes field
        val completionCard =
            CardFormatterService.buildCompletionCard(
                grab,
                multiProgress.copy(state = TorrentState.COMPLETED),
                null
            )
        val episodesField = completionCard.fields.firstOrNull { it.name == "Episodes" }
        assertNotNull(episodesField)
        assertEquals("E01, E02, E03 (3 episodes)", episodesField.value)
    }

    @Test
    fun `should collapse excess episode rows when more than 8 episodes in multi-track download`() {
        val childItems =
            (1..12).map { epNum ->
                TorrentProgress(
                    hash = "hash_$epNum",
                    name = "Futurama.S01E%02d.1080p".format(epNum),
                    progressPercent = if (epNum <= 6) 100.0 else 20.0,
                    progressRatio = if (epNum <= 6) 1.0 else 0.2,
                    downloadSpeedBytesPerSec = 5000000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 60L,
                    totalSizeBytes = 1000000000L,
                    downloadedBytes = if (epNum <= 6) 1000000000L else 200000000L,
                    state = if (epNum <= 6) TorrentState.COMPLETED else TorrentState.DOWNLOADING
                )
            }

        val tracks = CardFormatterService.formatEpisodeTracks(childItems, maxItems = 8)
        assertNotNull(tracks)
        assertTrue(tracks.contains("E01"))
        assertTrue(tracks.contains("E08"))
        assertTrue(!tracks.contains("E09"))
        assertTrue(tracks.contains("<i>...and 4 more episodes</i>"))
    }

    @Test
    fun `should format episode tracks with various TorrentState statuses`() {
        val items =
            listOf(
                TorrentProgress(
                    hash = "h1",
                    name = "Show.S01E01",
                    progressPercent = 0.0,
                    progressRatio = 0.0,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 0L,
                    state = TorrentState.STALLED
                ),
                TorrentProgress(
                    hash = "h2",
                    name = "Show.S01E02",
                    progressPercent = 0.0,
                    progressRatio = 0.0,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 0L,
                    state = TorrentState.QUEUED
                ),
                TorrentProgress(
                    hash = "h3",
                    name = "Show.S01E03",
                    progressPercent = 10.0,
                    progressRatio = 0.1,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 100000L,
                    state = TorrentState.PAUSED
                ),
                TorrentProgress(
                    hash = "h4",
                    name = "Show.S01E04",
                    progressPercent = 5.0,
                    progressRatio = 0.05,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 50000L,
                    state = TorrentState.ALLOCATING_METADATA
                ),
                TorrentProgress(
                    hash = "h5",
                    name = "Show.S01E05",
                    progressPercent = 50.0,
                    progressRatio = 0.5,
                    downloadSpeedBytesPerSec = 0L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 500000L,
                    state = TorrentState.CHECKING
                ),
                TorrentProgress(
                    hash = "h6",
                    name = "Show.S01E06",
                    progressPercent = 30.0,
                    progressRatio = 0.3,
                    downloadSpeedBytesPerSec = 2000000L,
                    uploadSpeedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    totalSizeBytes = 1000000L,
                    downloadedBytes = 300000L,
                    state = TorrentState.DOWNLOADING
                )
            )

        val tracks = CardFormatterService.formatEpisodeTracks(items)
        assertNotNull(tracks)
        assertTrue(tracks.contains("Stalled"))
        assertTrue(tracks.contains("Queued"))
        assertTrue(tracks.contains("Paused"))
        assertTrue(tracks.contains("Allocating"))
        assertTrue(tracks.contains("488.3 KB / 976.6 KB"))
        assertTrue(tracks.contains("1.9 MB/s"))
    }
}
