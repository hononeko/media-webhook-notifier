package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventRailReplayTest {
    private val payload =
        MediaPayload.ArrDownload(
            source = AppSource.RADARR,
            title = "Dune.Part.Two.2024.2160p",
            seriesOrMovieTitle = "Dune: Part Two",
            instanceName = "Radarr-4K"
        )

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    @Test
    fun `should capture failures with stack traces and replay them once the cause is fixed`() =
        runBlocking<Unit> {
            val healthy = AtomicBoolean(false)
            val processed = AtomicInteger(0)
            val ingest =
                IngestWebhookUseCase {
                    processed.incrementAndGet()
                    check(healthy.get()) { "Telegram unreachable" }
                    Either.Right(Unit)
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventRail = EventRail(standardCapacity = 10, urgentCapacity = 5)
            eventRail.start(scope, ingest, workerCount = 1)

            try {
                assertTrue(eventRail.publish(payload))
                awaitUntil { eventRail.deadLetterBuffer.size() == 1 }

                val deadLetter = eventRail.deadLetterBuffer.getEntries().single()
                assertEquals("Telegram unreachable", deadLetter.errorMessage)
                assertEquals(1, deadLetter.attemptCount)
                assertTrue(assertNotNull(deadLetter.stackTrace).contains("IllegalStateException"))

                healthy.set(true)
                val result = eventRail.replayDeadLetter(deadLetter.id)
                assertIs<DeadLetterReplayResult.Replayed>(result)
                awaitUntil { processed.get() == 2 }

                assertEquals(1, eventRail.deadLetterBuffer.size())
                assertEquals(DeadLetterStatus.RESOLVED, eventRail.deadLetterBuffer.get(deadLetter.id)?.status)
                assertIs<DeadLetterReplayResult.AlreadyResolved>(eventRail.replayDeadLetter(deadLetter.id))
            } finally {
                eventRail.close()
                scope.cancel()
            }
        }

    @Test
    fun `should record a new dead letter with incremented attempt when a replay fails again`() =
        runBlocking<Unit> {
            val cause = java.io.IOException("connection reset")
            val ingest =
                IngestWebhookUseCase {
                    Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "send failed", cause))
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventRail = EventRail(standardCapacity = 10, urgentCapacity = 5)
            eventRail.start(scope, ingest, workerCount = 1)

            try {
                eventRail.publish(payload)
                awaitUntil { eventRail.deadLetterBuffer.size() == 1 }
                val original = eventRail.deadLetterBuffer.getEntries().single()
                assertTrue(assertNotNull(original.stackTrace).contains("connection reset"))

                eventRail.replayDeadLetter(original.id)
                awaitUntil { eventRail.deadLetterBuffer.size() == 2 }

                val retry = eventRail.deadLetterBuffer.getEntries().last()
                assertEquals(2, retry.attemptCount)
                assertEquals(payload, retry.payload)
                assertEquals(DeadLetterStatus.PENDING, retry.status)
                assertEquals(DeadLetterStatus.RESOLVED, eventRail.deadLetterBuffer.get(original.id)?.status)
            } finally {
                eventRail.close()
                scope.cancel()
            }
        }

    @Test
    fun `should leave entry pending without duplicating it when the rail rejects a replay`() {
        val eventRail = EventRail(standardCapacity = 1, urgentCapacity = 1)
        val entry = eventRail.deadLetterBuffer.record(payload, "Ingest failed")
        eventRail.close()

        assertIs<DeadLetterReplayResult.Rejected>(eventRail.replayDeadLetter(entry.id))
        assertEquals(1, eventRail.deadLetterBuffer.size())
        assertEquals(DeadLetterStatus.PENDING, eventRail.deadLetterBuffer.get(entry.id)?.status)
        assertEquals(DeadLetterReplayResult.NotFound, eventRail.replayDeadLetter("unknown"))
    }
}
