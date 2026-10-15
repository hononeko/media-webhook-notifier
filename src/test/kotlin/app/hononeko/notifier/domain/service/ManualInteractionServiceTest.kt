package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.testing.RecordingPublisher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualInteractionServiceTest {
    @Test
    fun `should announce manual interaction required events successfully`() =
        runTest {
            val publisher = RecordingPublisher()
            val service = ManualInteractionService(publisher)

            val payload =
                MediaPayload.ServarrManualInteraction(
                    source = AppSource.SONARR,
                    eventType = EventType.MANUAL_INTERACTION,
                    title = "Severance - S02E01",
                    seriesOrMovieTitle = "Severance",
                    seasonNumber = 2,
                    episodeNumbers = listOf(1),
                    episodeTitle = "Hello World",
                    releaseTitle = "Severance.S02E01.Sample.1080p",
                    reason = "Sample file detected",
                    instanceName = "Sonarr-TV"
                )

            val result = service.announce(payload)
            assertTrue(result.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertEquals(
                "✋ Manual Import Required: Severance - S02E01 - Hello World",
                publisher.sentCards.first().title
            )

            // Without episodeTitle
            val payloadNoEpTitle = payload.copy(episodeTitle = null)
            val resultNoEpTitle = service.announce(payloadNoEpTitle)
            assertTrue(resultNoEpTitle.isRight())
            assertEquals(2, publisher.sentCards.size)
            assertEquals("✋ Manual Import Required: Severance (S02E01)", publisher.sentCards.last().title)
        }

    @Test
    fun `should return error when notification publisher fails`() =
        runTest {
            val publisher = RecordingPublisher(shouldFail = true)
            val service = ManualInteractionService(publisher)

            val payload =
                MediaPayload.ServarrManualInteraction(
                    source = AppSource.RADARR,
                    title = "Dune (2021)",
                    seriesOrMovieTitle = "Dune"
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }
}
