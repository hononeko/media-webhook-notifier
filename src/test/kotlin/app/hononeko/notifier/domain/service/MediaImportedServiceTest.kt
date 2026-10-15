package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.testing.RecordingPublisher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaImportedServiceTest {
    @Test
    fun `should publish media imported card successfully`() =
        runTest {
            val publisher = RecordingPublisher()
            val service = MediaImportedService(publisher)

            val payload =
                MediaPayload.ArrDownload(
                    source = AppSource.SONARR,
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    episodeTitle = "Hello World",
                    isUpgrade = false
                )

            val result = service.announce(payload)
            assertTrue(result.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertEquals("📁 File Imported: Severance - S02E01 - Hello World", publisher.sentCards.first().title)

            // Without episodeTitle
            val payloadNoEpTitle = payload.copy(episodeTitle = null)
            val resultNoEpTitle = service.announce(payloadNoEpTitle)
            assertTrue(resultNoEpTitle.isRight())
            assertEquals(2, publisher.sentCards.size)
            assertEquals("📁 File Imported: Severance (S02E01)", publisher.sentCards.last().title)
        }

    @Test
    fun `should return error when publisher delivery fails`() =
        runTest {
            val failingPublisher = RecordingPublisher(shouldFail = true)
            val service = MediaImportedService(failingPublisher)

            val payload =
                MediaPayload.ArrDownload(
                    source = AppSource.RADARR,
                    title = "Dune: Part Two",
                    seriesOrMovieTitle = "Dune: Part Two",
                    isUpgrade = true
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }
}
