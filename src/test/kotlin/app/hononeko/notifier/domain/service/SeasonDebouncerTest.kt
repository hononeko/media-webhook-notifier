package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonDebouncerTest {
    @Test
    fun `should batch rapid multi-episode grabs sharing downloadId`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedGrab = { received.add(it) }
                )

            val grab1 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashABC",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    sizeBytes = 2000000000L
                )

            val grab2 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashABC",
                    title = "Severance - S02E02",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(2),
                    sizeBytes = 4000000000L
                )

            debouncer.submit(grab1)
            testScope.advanceTimeBy(500L)
            debouncer.submit(grab2)

            assertEquals(0, received.size)
            assertEquals(1, debouncer.activeBufferCount())

            testScope.advanceTimeBy(2100L)

            assertEquals(1, received.size)
            val consolidated = received.first()
            assertEquals(listOf(1, 2), consolidated.episodeNumbers)
            assertEquals(4000000000L, consolidated.sizeBytes)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should batch rapid multi-episode grabs with different downloadIds for same season`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedGrab = { received.add(it) }
                )

            // Simulate Love is Blind UK S03E01 to S03E05 individual grabs arriving rapidly with separate torrent hashes
            for (i in 1..5) {
                val grab =
                    MediaPayload.ArrGrab(
                        source = AppSource.SONARR,
                        downloadId = "hash_ep_$i",
                        title = "Love.Is.Blind.UK.S03E%02d.1080p.WEB.H264-DEFENESTRATE".format(i),
                        seriesOrMovieTitle = "Love is Blind: UK",
                        seasonNumber = 3,
                        episodeNumbers = listOf(i),
                        sizeBytes = 2_640_000_000L,
                        instanceName = "Sonarr-TV"
                    )
                debouncer.submit(grab)
                testScope.advanceTimeBy(200L)
            }

            assertEquals(0, received.size)
            assertEquals(1, debouncer.activeBufferCount())
            assertEquals(1, debouncer.activeGrabBufferCount())

            testScope.advanceTimeBy(2100L)

            assertEquals(1, received.size)
            val consolidated = received.first()
            assertEquals("Love is Blind: UK", consolidated.seriesOrMovieTitle)
            assertEquals(3, consolidated.seasonNumber)
            assertEquals(listOf(1, 2, 3, 4, 5), consolidated.episodeNumbers)
            assertEquals(
                listOf("hash_ep_1", "hash_ep_2", "hash_ep_3", "hash_ep_4", "hash_ep_5"),
                consolidated.downloadIds
            )
            assertEquals("hash_ep_1|hash_ep_2|hash_ep_3|hash_ep_4|hash_ep_5", consolidated.downloadId)
            assertEquals("Sonarr-TV", consolidated.instanceName)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should flush immediately when requested`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 10000L,
                    scope = testScope,
                    onDebouncedGrab = { received.add(it) }
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashXYZ",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1)
                )

            debouncer.submit(grab)
            debouncer.flush("hashXYZ")

            assertEquals(1, received.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should execute suspending onDebouncedGrab successfully after timer expires without cancellation`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            var suspendingExecutionCompleted = false

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 1000L,
                    scope = testScope,
                    onDebouncedGrab = {
                        kotlinx.coroutines.delay(50L)
                        suspendingExecutionCompleted = true
                    }
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.RADARR,
                    downloadId = "hashRAD",
                    title = "Dune: Part Two",
                    seriesOrMovieTitle = "Dune: Part Two"
                )

            debouncer.submit(grab)
            testScope.advanceTimeBy(1100L)

            kotlin.test.assertTrue(suspendingExecutionCompleted)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should batch rapid multi-episode downloads and upgrades for season pack`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedDownload = { received.add(it) }
                )

            // Simulate Futurama S01E01 to S01E09 quality upgrade webhooks arriving rapidly
            for (i in 1..9) {
                val upgrade =
                    MediaPayload.ArrDownload(
                        source = AppSource.SONARR,
                        title = "Futurama - S01E%02d".format(i),
                        seriesOrMovieTitle = "Futurama",
                        seasonNumber = 1,
                        episodeNumbers = listOf(i),
                        isUpgrade = true,
                        sizeBytes = 2_168_455_168L, // ~2.02 GB
                        quality = "WEBDL-1080p",
                        videoCodec = "x265",
                        instanceName = "Sonarr-TV"
                    )
                debouncer.submit(upgrade)
                testScope.advanceTimeBy(200L) // arrive every 200ms
            }

            // Before debounce window expires, no notifications emitted and 1 buffer active
            assertEquals(0, received.size)
            assertEquals(1, debouncer.activeBufferCount())
            assertEquals(1, debouncer.activeDownloadBufferCount())

            // Advance time past debounce window
            testScope.advanceTimeBy(2100L)

            assertEquals(1, received.size)
            val consolidated = received.first()
            assertEquals("Futurama", consolidated.seriesOrMovieTitle)
            assertEquals(1, consolidated.seasonNumber)
            assertEquals((1..9).toList(), consolidated.episodeNumbers)
            assertEquals(true, consolidated.isUpgrade)
            assertEquals(2_168_455_168L, consolidated.sizeBytes)
            assertEquals("WEBDL-1080p", consolidated.quality)
            assertEquals("x265", consolidated.videoCodec)
            assertEquals("Sonarr-TV", consolidated.instanceName)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should maintain separate buffers for different shows and seasons during upgrade`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 1500L,
                    scope = testScope,
                    onDebouncedDownload = { received.add(it) }
                )

            val show1S1 =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1),
                    isUpgrade = true,
                    sizeBytes = 2_000_000_000L
                )

            val show1S2 =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Futurama - S02E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    isUpgrade = true,
                    sizeBytes = 2_000_000_000L
                )

            val show2S1 =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Severance - S01E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1),
                    isUpgrade = true,
                    sizeBytes = 3_000_000_000L
                )

            debouncer.submit(show1S1)
            debouncer.submit(show1S2)
            debouncer.submit(show2S1)

            assertEquals(3, debouncer.activeBufferCount())

            testScope.advanceTimeBy(1600L)

            assertEquals(3, received.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should flushAll both grab and download buffers cleanly`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val grabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val downloads = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 60000L,
                    scope = testScope,
                    onDebouncedGrab = { grabs.add(it) },
                    onDebouncedDownload = { downloads.add(it) }
                )

            val grab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hashGrab",
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1)
                )

            val download =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1),
                    isUpgrade = true
                )

            debouncer.submit(grab)
            debouncer.submit(download)

            assertEquals(2, debouncer.activeBufferCount())
            assertEquals(1, debouncer.activeGrabBufferCount())
            assertEquals(1, debouncer.activeDownloadBufferCount())

            debouncer.flushAll()

            assertEquals(1, grabs.size)
            assertEquals(1, downloads.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should support generic submit for MediaPayload instances`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val grabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val downloads = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 1000L,
                    scope = testScope,
                    onDebouncedGrab = { grabs.add(it) },
                    onDebouncedDownload = { downloads.add(it) }
                )

            val grab: MediaPayload =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "",
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1),
                    instanceName = "Sonarr-Main"
                )

            val download: MediaPayload =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    downloadId = null,
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1),
                    instanceName = "Sonarr-Main"
                )

            val plex: MediaPayload = MediaPayload.PlexLibraryNew(title = "Futurama")

            debouncer.submit(grab)
            debouncer.submit(download)
            debouncer.submit(plex) // ignored

            assertEquals(2, debouncer.activeBufferCount())

            debouncer.flush("sonarr:sonarr-main:futurama:s1")
            assertEquals(1, grabs.size)

            debouncer.flush("title:sonarr:sonarr-main:futurama:s1:false")
            assertEquals(1, downloads.size)

            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should handle key computation fallbacks and individual hash flushes`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val grabs = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())
            val downloads = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 60000L,
                    scope = testScope,
                    onDebouncedGrab = { grabs.add(it) },
                    onDebouncedDownload = { downloads.add(it) }
                )

            // Grab with empty series title but valid downloadId
            val grab1 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_only_grab",
                    title = "Some Release",
                    seriesOrMovieTitle = "",
                    seasonNumber = 1
                )
            // Grab with empty series title and empty downloadId
            val grab2 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "   ",
                    title = "Some Release 2",
                    seriesOrMovieTitle = "  ",
                    seasonNumber = null
                )

            // Download with empty series title but valid downloadId
            val dl1 =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    downloadId = "hash_only_dl",
                    title = "Some Release",
                    seriesOrMovieTitle = "",
                    seasonNumber = 1,
                    isUpgrade = false
                )
            // Download with empty series title and null downloadId
            val dl2 =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    downloadId = null,
                    title = "Some Release 2",
                    seriesOrMovieTitle = "   ",
                    seasonNumber = null,
                    isUpgrade = false
                )

            debouncer.submit(grab1)
            debouncer.submit(grab2)
            debouncer.submit(dl1)
            debouncer.submit(dl2)

            assertEquals(4, debouncer.activeBufferCount())

            // Flush grab by downloadId matching
            debouncer.flushGrab("hash_only_grab")
            assertEquals(1, grabs.size)

            // Flush download by downloadId matching
            debouncer.flushDownload("hash_only_dl")
            assertEquals(1, downloads.size)

            debouncer.flushAll()
            assertEquals(2, grabs.size)
            assertEquals(2, downloads.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should batch rapid multi-episode Plex webhooks for same season`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedAvailable = { available.add(it) }
                )

            // Simulate Ghost in the Shell S01E01 to S01E05 arriving rapidly from Plex
            for (i in 1..5) {
                val plexEp =
                    MediaPayload.PlexLibraryNew(
                        title = "Episode $i",
                        grandParentTitle = "Ghost in the Shell",
                        seasonNumber = 1,
                        episodeNumber = i,
                        ratingKey = "plex_ep_$i",
                        instanceName = "Kerrlab Plex"
                    )
                debouncer.submit(plexEp)
                testScope.advanceTimeBy(200L)
            }

            assertEquals(0, available.size)
            assertEquals(1, debouncer.activeBufferCount())
            assertEquals(1, debouncer.activeAvailableBufferCount())

            testScope.advanceTimeBy(2100L)

            assertEquals(1, available.size)
            val consolidated = available.first() as MediaPayload.PlexLibraryNew
            assertEquals("Ghost in the Shell", consolidated.grandParentTitle)
            assertEquals(1, consolidated.seasonNumber)
            assertEquals(listOf(1, 2, 3, 4, 5), consolidated.episodeNumbers)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should batch rapid multi-episode Jellyfin webhooks for same season`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedAvailable = { available.add(it) }
                )

            for (i in 1..4) {
                val jellyfinEp =
                    MediaPayload.JellyfinItemAdded(
                        itemId = "jf_$i",
                        title = "Episode $i",
                        seriesName = "Severance",
                        seasonNumber = 2,
                        episodeNumber = i,
                        instanceName = "Jellyfin"
                    )
                debouncer.submit(jellyfinEp)
                testScope.advanceTimeBy(150L)
            }

            assertEquals(0, available.size)
            assertEquals(1, debouncer.activeBufferCount())

            testScope.advanceTimeBy(2100L)

            assertEquals(1, available.size)
            val consolidated = available.first() as MediaPayload.JellyfinItemAdded
            assertEquals("Severance", consolidated.seriesName)
            assertEquals(2, consolidated.seasonNumber)
            assertEquals(listOf(1, 2, 3, 4), consolidated.episodeNumbers)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should merge all metadata fields when debouncing PlexLibraryNew`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedAvailable = { available.add(it) }
                )

            val plex1 =
                MediaPayload.PlexLibraryNew(
                    title = "Episode 1",
                    grandParentTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    ratingKey = "plex_1",
                    summary = "First episode",
                    rating = 8.0,
                    instanceName = "Kerrlab Plex"
                )

            val plex2 =
                MediaPayload.PlexLibraryNew(
                    title = "Episode 2",
                    grandParentTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    ratingKey = "plex_2",
                    artworkBytes = byteArrayOf(1, 2, 3),
                    posterUrl = "https://example.com/poster.jpg",
                    parentPosterUrl = "https://example.com/parent.jpg",
                    grandparentPosterUrl = "https://example.com/grand.jpg",
                    videoCodec = "HEVC",
                    audioCodec = "AAC",
                    resolution = "1080p",
                    instanceName = "Kerrlab Plex"
                )

            debouncer.submit(plex1)
            debouncer.submit(plex2)

            testScope.advanceTimeBy(2100L)

            assertEquals(1, available.size)
            val merged = available.first() as MediaPayload.PlexLibraryNew
            assertEquals("Futurama", merged.grandParentTitle)
            assertEquals(listOf(1, 2), merged.episodeNumbers)
            assertEquals("https://example.com/poster.jpg", merged.posterUrl)
            assertEquals("https://example.com/parent.jpg", merged.parentPosterUrl)
            assertEquals("https://example.com/grand.jpg", merged.grandparentPosterUrl)
            assertEquals("HEVC", merged.videoCodec)
            assertEquals("AAC", merged.audioCodec)
            assertEquals("1080p", merged.resolution)
            kotlin.test.assertNotNull(merged.artworkBytes)
        }

    @Test
    fun `should merge all metadata fields when debouncing JellyfinItemAdded`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedAvailable = { available.add(it) }
                )

            val jf1 =
                MediaPayload.JellyfinItemAdded(
                    itemId = "jf1",
                    title = "Episode 1",
                    seriesName = "Severance",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    instanceName = "Jellyfin Server"
                )

            val jf2 =
                MediaPayload.JellyfinItemAdded(
                    itemId = "jf2",
                    title = "Episode 2",
                    seriesName = "Severance",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    posterUrl = "https://example.com/jf_poster.jpg",
                    overview = "Merged overview",
                    videoCodec = "x265",
                    audioCodec = "EAC3",
                    resolution = "4k",
                    instanceName = "Jellyfin Server"
                )

            debouncer.submit(jf1)
            debouncer.submit(jf2)

            testScope.advanceTimeBy(2100L)

            assertEquals(1, available.size)
            val merged = available.first() as MediaPayload.JellyfinItemAdded
            assertEquals("Severance", merged.seriesName)
            assertEquals(listOf(1, 2), merged.episodeNumbers)
            assertEquals("https://example.com/jf_poster.jpg", merged.posterUrl)
            assertEquals("Merged overview", merged.overview)
            assertEquals("x265", merged.videoCodec)
            assertEquals("EAC3", merged.audioCodec)
            assertEquals("4k", merged.resolution)
            assertEquals("Jellyfin Server", merged.instanceName)
        }

    @Test
    fun `should flush available buffers by key and ignore submit when onDebouncedAvailable is null`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            // When onDebouncedAvailable is null
            val noCallbackDebouncer =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedAvailable = null
                )

            val movie =
                MediaPayload.PlexLibraryNew(
                    title = "Inception",
                    year = 2010
                )
            val jfMovie =
                MediaPayload.JellyfinItemAdded(
                    itemId = "m1",
                    title = "Inception",
                    year = 2010
                )

            noCallbackDebouncer.submit(movie)
            noCallbackDebouncer.submit(jfMovie)
            assertEquals(0, noCallbackDebouncer.activeBufferCount())
            kotlin.test.assertFalse(noCallbackDebouncer.supportsAvailable)

            // When onDebouncedAvailable is present
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())
            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 60000L,
                    scope = testScope,
                    onDebouncedAvailable = { available.add(it) }
                )
            kotlin.test.assertTrue(debouncer.supportsAvailable)

            val plexMovie =
                MediaPayload.PlexLibraryNew(
                    title = "Interstellar",
                    year = 2014,
                    instanceName = "Plex"
                )

            debouncer.submit(plexMovie)
            assertEquals(1, debouncer.activeBufferCount())
            assertEquals(1, debouncer.activeAvailableBufferCount())

            debouncer.flush("plex:plex:movie:interstellar:2014")
            assertEquals(1, available.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should not flush on substring match and only match exact key or downloadId`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 10000L,
                    scope = testScope,
                    onDebouncedGrab = { received.add(it) }
                )

            val grab1 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_exact_1",
                    title = "Severance - S01E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1)
                )
            val grab2 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_exact_2",
                    title = "Severance Extras - S01E01",
                    seriesOrMovieTitle = "Severance Extras",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1)
                )

            debouncer.submit(grab1)
            debouncer.submit(grab2)
            assertEquals(2, debouncer.activeBufferCount())

            // Substring of source or title must NOT flush anything
            debouncer.flush("sonarr")
            assertEquals(0, received.size)
            assertEquals(2, debouncer.activeBufferCount())

            debouncer.flush("severance")
            assertEquals(0, received.size)
            assertEquals(2, debouncer.activeBufferCount())

            // Exact downloadId matches only grab1
            debouncer.flush("hash_exact_1")
            assertEquals(1, received.size)
            assertEquals("Severance", received.first().seriesOrMovieTitle)
            assertEquals(1, debouncer.activeBufferCount())

            // Exact key matches grab2
            debouncer.flush("sonarr::severance extras:s1")
            assertEquals(2, received.size)
            assertEquals(0, debouncer.activeBufferCount())
        }

    @Test
    fun `should immediately drop stale Plex webhooks when deduplicator is present`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload>())
            val deduplicator = MediaAvailableDeduplicator(maxAgeSeconds = 86_400L)

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 5000L,
                    scope = testScope,
                    deduplicator = deduplicator,
                    onDebouncedAvailable = { received.add(it) }
                )

            val nowSec = System.currentTimeMillis() / 1000L
            val stalePlex =
                MediaPayload.PlexLibraryNew(
                    title = "The Matrix",
                    year = 1999,
                    ratingKey = "matrix-1999",
                    addedAt = nowSec - (10 * 86_400L) // 10 days ago
                )

            debouncer.submit(stalePlex)
            assertEquals(0, debouncer.activeBufferCount())
            testScheduler.advanceTimeBy(6000L)
            assertEquals(0, received.size)
        }

    @Test
    fun `should merge ratingKeys when debouncing multiple Plex episodes`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 5000L,
                    scope = testScope,
                    onDebouncedAvailable = { received.add(it) }
                )

            val ep1 =
                MediaPayload.PlexLibraryNew(
                    title = "Good News About Hell",
                    grandParentTitle = "Severance",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    ratingKey = "rk-101"
                )
            val ep2 =
                MediaPayload.PlexLibraryNew(
                    title = "Half Loop",
                    grandParentTitle = "Severance",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    ratingKey = "rk-102"
                )

            debouncer.submit(ep1)
            debouncer.submit(ep2)
            assertEquals(1, debouncer.activeBufferCount())

            testScheduler.advanceTimeBy(6000L)
            assertEquals(1, received.size)
            val consolidated = received.first() as MediaPayload.PlexLibraryNew
            assertEquals(listOf(1, 2), consolidated.episodeNumbers)
            assertEquals(listOf("rk-101", "rk-102"), consolidated.ratingKeys)
        }

    @Test
    fun `should key show and series mediaType as show in computePlexKey and computeJellyfinKey`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)
            val received = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val debouncer =
                SeasonDebouncer(
                    debounceMillis = 5000L,
                    scope = testScope,
                    onDebouncedAvailable = { received.add(it) }
                )

            val plexShow =
                MediaPayload.PlexLibraryNew(
                    title = "Dark",
                    mediaType = "show"
                )
            val jellyfinSeries =
                MediaPayload.JellyfinItemAdded(
                    itemId = "jf-dark-1",
                    title = "Dark",
                    mediaType = "series"
                )

            debouncer.submit(plexShow)
            debouncer.submit(jellyfinSeries)

            // Both should be keyed as show, not movie
            debouncer.flush("plex::show:dark:s0")
            assertEquals(1, received.size)

            debouncer.flush("jellyfin::show:dark:s0")
            assertEquals(2, received.size)
        }
}
