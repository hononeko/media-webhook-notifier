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
}
