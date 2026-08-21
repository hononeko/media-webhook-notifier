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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRailTest {
    @Test
    fun `should publish and consume payloads successfully with parallel workers`() =
        runTest {
            val eventRail = EventRail(standardCapacity = 10, urgentCapacity = 5)
            assertFalse(eventRail.isRunning)
            assertFalse(eventRail.isClosed)

            val processedCount = AtomicInteger(0)
            val fakeUseCase =
                IngestWebhookUseCase { _ ->
                    processedCount.incrementAndGet()
                    Either.Right(Unit)
                }

            val scope = CoroutineScope(Dispatchers.Default)
            val job = eventRail.start(scope, fakeUseCase, workerCount = 4)
            assertTrue(eventRail.isRunning)
            assertEquals(1, eventRail.activeWorkersCount)

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
            assertTrue(eventRail.isClosed)
            job.cancel()
            scope.cancel()
        }

    @Test
    fun `should support secondary constructor and drop urgent events when urgent buffer is full`() =
        runTest {
            val eventRail = EventRail(capacity = 5)
            assertFalse(eventRail.isClosed)

            val urgentPayload1 =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    level = "Error",
                    message = "Indexer 1 down"
                )
            val urgentPayload2 =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    level = "Error",
                    message = "Indexer 2 down"
                )

            // Fill urgent capacity (which is (5/5).coerceAtLeast(1) = 1)
            assertTrue(eventRail.publish(urgentPayload1))
            assertFalse(eventRail.publish(urgentPayload2)) // Exceeds urgent capacity 1

            assertEquals(1, eventRail.deadLetterBuffer.size())
            assertTrue(
                eventRail.deadLetterBuffer
                    .getEntries()
                    .first()
                    .payload is MediaPayload.ServarrHealth
            )

            eventRail.close()
            assertTrue(eventRail.isClosed)
        }

    @Test
    fun `should return false and record in dead-letter when channel buffer is full`() =
        runTest {
            val eventRail = EventRail(standardCapacity = 1, urgentCapacity = 1)

            val payload1 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash1",
                    title = "Show 1",
                    seriesOrMovieTitle = "Show 1"
                )
            val payload2 =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash2",
                    title = "Show 2",
                    seriesOrMovieTitle = "Show 2"
                )

            assertTrue(eventRail.publish(payload1))
            assertFalse(eventRail.publish(payload2)) // Exceeds capacity 1

            assertEquals(1, eventRail.deadLetterBuffer.size())
            assertEquals(
                "hash2",
                (
                    eventRail.deadLetterBuffer
                        .getEntries()
                        .first()
                        .payload as MediaPayload.ArrGrab
                ).downloadId
            )

            eventRail.close()
        }

    @Test
    fun `should route urgent health and manual events to urgent channel and record dead-letter on failure`() =
        runTest {
            val eventRail = EventRail(standardCapacity = 10, urgentCapacity = 10)
            val processed = Collections.synchronizedList(mutableListOf<MediaPayload>())

            val fakeUseCase =
                IngestWebhookUseCase { payload ->
                    processed.add(payload)
                    if (payload is MediaPayload.ServarrHealth) {
                        Either.Left(DomainError.NotificationError.DeliveryFailed("test", "Failed health"))
                    } else {
                        Either.Right(Unit)
                    }
                }

            val scope = CoroutineScope(Dispatchers.Default)
            val job = eventRail.start(scope, fakeUseCase, workerCount = 1)

            val healthPayload =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    level = "Error",
                    message = "Indexers unavailable"
                )
            val grabPayload =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = "hash_norm",
                    title = "Show",
                    seriesOrMovieTitle = "Show"
                )

            assertTrue(eventRail.isUrgent(healthPayload))
            assertFalse(eventRail.isUrgent(grabPayload))

            eventRail.publish(healthPayload)
            eventRail.publish(grabPayload)

            var retries = 0
            while (processed.size < 2 && retries < 50) {
                Thread.sleep(10)
                retries++
            }
            assertEquals(2, processed.size)

            // Health error recorded in dead letter buffer
            assertEquals(1, eventRail.deadLetterBuffer.size())
            assertTrue(
                eventRail.deadLetterBuffer
                    .getEntries()
                    .first()
                    .payload is MediaPayload.ServarrHealth
            )

            eventRail.close()
            job.cancel()
            scope.cancel()
        }
}
