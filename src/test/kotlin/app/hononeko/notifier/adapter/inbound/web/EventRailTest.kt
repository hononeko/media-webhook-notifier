package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRailTest {
    @Test
    fun `should publish and consume payloads successfully`() =
        runTest {
            val eventRail = EventRail(capacity = 10)
            val processedCount = AtomicInteger(0)

            val fakeUseCase =
                IngestWebhookUseCase { _ ->
                    processedCount.incrementAndGet()
                    Either.Right(Unit)
                }

            val scope = CoroutineScope(Dispatchers.Default)
            val job = eventRail.start(scope, fakeUseCase)

            val payload =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash123",
                    title = "Severance.S02E01",
                    seriesOrMovieTitle = "Severance"
                )

            val published = eventRail.publish(payload)
            assertTrue(published)

            var retries = 0
            while (processedCount.get() < 1 && retries < 50) {
                Thread.sleep(10)
                retries++
            }
            assertEquals(1, processedCount.get())

            eventRail.close()
            job.cancel()
            scope.cancel()
        }

    @Test
    fun `should return false when event rail buffer is full`() =
        runTest {
            val eventRail = EventRail(capacity = 1)

            val payload =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash1",
                    title = "Show",
                    seriesOrMovieTitle = "Show"
                )

            assertTrue(eventRail.publish(payload))
            assertFalse(eventRail.publish(payload)) // Exceeds capacity 1

            eventRail.close()
        }

    @Test
    fun `should continue processing when use case returns error or throws exception`() =
        runTest {
            val eventRail = EventRail(capacity = 10)
            val processedCount = AtomicInteger(0)

            val fakeUseCase =
                IngestWebhookUseCase { _ ->
                    val count = processedCount.incrementAndGet()
                    if (count == 1) {
                        throw RuntimeException("Simulated error")
                    }
                    Either.Left(DomainError.NotificationError.DeliveryFailed("test", "Failed"))
                }

            val scope = CoroutineScope(Dispatchers.Default)
            val job = eventRail.start(scope, fakeUseCase)

            val payload =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash1",
                    title = "Show",
                    seriesOrMovieTitle = "Show"
                )

            assertTrue(eventRail.publish(payload))
            assertTrue(eventRail.publish(payload))

            var retries = 0
            while (processedCount.get() < 2 && retries < 50) {
                Thread.sleep(10)
                retries++
            }
            assertEquals(2, processedCount.get())

            eventRail.close()
            job.cancel()
            scope.cancel()
        }
}
