package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
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
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class DistributedSeasonDebouncerTest {
    @Test
    fun `episodes of a season grab split across two replicas merge into one card`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val sharedStore = InMemoryStateStore()
            val flushed = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val podA =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedGrab = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            val podB =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedGrab = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            podA.submit(
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "h1",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    instanceName = "Sonarr-TV"
                )
            )
            testScope.advanceTimeBy(1200L)

            // Episode 2 lands on Pod B before the 2000ms window expires, sliding the shared flush deadline out.
            podB.submit(
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "h2",
                    title = "Severance - S02E02",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(2),
                    instanceName = "Sonarr-TV"
                )
            )

            // At t=2200ms (200ms after Pod A's original deadline), Pod A must see the extended deadline and wait.
            testScope.advanceTimeBy(1000L)
            assertEquals(0, flushed.size)
            assertEquals(1, podA.distributedBufferCount())

            // At t=3300ms (past t=3200ms), exactly one replica flushes the merged payload.
            testScope.advanceTimeBy(1100L)
            assertEquals(1, flushed.size)
            val merged = flushed.single()
            assertEquals(listOf(1, 2), merged.episodeNumbers)
            assertEquals("h1|h2", merged.downloadId)
            assertEquals(0, podA.distributedBufferCount())
        }

    @Test
    fun `imports and Plex available bursts split across two replicas merge into one card each`() =
        runTest {
            val testScope = TestScope(StandardTestDispatcher(testScheduler))
            val sharedStore = InMemoryStateStore()
            val imports = Collections.synchronizedList(mutableListOf<MediaPayload.ArrDownload>())
            val available = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val podA =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedDownload = { imports.add(it) },
                    onDebouncedAvailable = { available.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            val podB =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = testScope,
                    onDebouncedDownload = { imports.add(it) },
                    onDebouncedAvailable = { available.add(it) }
                ).withDistributedStore(sharedStore, clock = { testScope.testScheduler.currentTime })

            podA.submit(
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    downloadId = "dl1",
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1)
                )
            )
            podB.submit(
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    downloadId = "dl2",
                    title = "Severance - S02E02",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(2)
                )
            )

            podA.submit(
                MediaPayload.PlexLibraryNew(
                    title = "Ep 1",
                    grandParentTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumber = 1,
                    ratingKey = "rk1",
                    artworkBytes = byteArrayOf(7, 8, 9)
                )
            )
            podB.submit(
                MediaPayload.PlexLibraryNew(
                    title = "Ep 2",
                    grandParentTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumber = 2,
                    ratingKey = "rk2"
                )
            )

            testScope.advanceTimeBy(2500L)

            assertEquals(1, imports.size)
            assertEquals(listOf(1, 2), imports.single().episodeNumbers)

            assertEquals(1, available.size)
            val mergedPlex = available.single() as MediaPayload.PlexLibraryNew
            assertEquals(listOf(1, 2), mergedPlex.episodeNumbers)
            assertEquals(listOf("rk1", "rk2"), mergedPlex.ratingKeys)
        }

    @Test
    fun `distributed sweeper flushes expired buffer from a crashed pod and flush by downloadId works`() =
        runTest {
            val crashedScope = TestScope(StandardTestDispatcher(testScheduler))
            val liveScope = TestScope(StandardTestDispatcher(testScheduler))
            val sharedStore = InMemoryStateStore()
            val flushed = Collections.synchronizedList(mutableListOf<MediaPayload.ArrGrab>())

            val crashedPod =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = crashedScope,
                    onDebouncedGrab = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { liveScope.testScheduler.currentTime })

            val livePod =
                SeasonDebouncer(
                    debounceMillis = 2000L,
                    scope = liveScope,
                    onDebouncedGrab = { flushed.add(it) }
                ).withDistributedStore(sharedStore, clock = { liveScope.testScheduler.currentTime })

            crashedPod.submit(
                MediaPayload.ArrGrab(
                    source = AppSource.RADARR,
                    downloadId = "crashed-hash",
                    title = "Dune",
                    seriesOrMovieTitle = "Dune"
                )
            )
            val sweeper = livePod.startDistributedSweeper(liveScope, intervalMillis = 1000L)

            // Simulate Pod A dying before its local timer fires (only liveScope advances).
            liveScope.advanceTimeBy(3500L)
            assertEquals(1, flushed.size)
            assertEquals("crashed-hash", flushed.single().downloadId)

            // Test flush by downloadId and flushAll on livePod.
            livePod.submit(
                MediaPayload.ArrGrab(
                    source = AppSource.RADARR,
                    downloadId = "flush-by-id",
                    title = "Arrival",
                    seriesOrMovieTitle = "Arrival"
                )
            )
            livePod.flushGrab("flush-by-id")
            assertEquals(2, flushed.size)

            livePod.submit(
                MediaPayload.PlexLibraryNew(
                    title = "Arrival",
                    year = 2016,
                    artworkBytes = byteArrayOf(1, 2)
                )
            )
            // Also verify local artwork re-attachment when flushAll runs on the receiving pod.
            val availableOut = mutableListOf<MediaPayload>()
            val podWithArt =
                SeasonDebouncer(
                    debounceMillis = 5000L,
                    scope = liveScope,
                    onDebouncedAvailable = { availableOut.add(it) }
                ).withDistributedStore(sharedStore, clock = { liveScope.testScheduler.currentTime })
            podWithArt.submit(
                MediaPayload.PlexLibraryNew(
                    title = "Interstellar",
                    year = 2014,
                    artworkBytes = byteArrayOf(4, 5)
                )
            )
            podWithArt.flushAll()
            val flushedArt = availableOut.single() as MediaPayload.PlexLibraryNew
            assertNotNull(flushedArt.artworkBytes)

            sweeper.cancel()
        }
}
