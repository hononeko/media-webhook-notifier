package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramPollingServiceTest {
    private data class Poll(
        val atMillis: Long,
        val offset: Long?
    )

    private val api = mockk<TelegramBotApi>()
    private val processor = mockk<TelegramCallbackProcessor>(relaxed = true)
    private val events = mutableListOf<String>()
    private val polls = mutableListOf<Poll>()

    private fun network() =
        Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "getUpdates: connection reset"))

    private fun http(status: Int) =
        Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "HTTP $status", httpStatus = status))

    private fun updates(vararg ids: Long) =
        Either.Right(
            ids.map { id ->
                TelegramUpdate(
                    updateId = id,
                    callbackQuery =
                        TelegramCallbackQuery(
                            id = "cq$id",
                            from = TelegramUser(42),
                            message = TelegramMessage(1, TelegramChat(-1001)),
                            data = "mwn1:ping:AAAAAAAAAA"
                        )
                )
            }
        )

    /** Scripts getUpdates; once the script runs out, the next long poll never returns, so the loop goes idle. */
    private fun TestScope.script(vararg results: Either<DomainError.NotificationError, List<TelegramUpdate>>) {
        val remaining = ArrayDeque(results.toList())
        coEvery { api.getUpdates(any(), any()) } coAnswers {
            events += "getUpdates"
            polls += Poll(testScheduler.currentTime, firstArg())
            if (remaining.isEmpty()) awaitCancellation() else remaining.removeFirst()
        }
    }

    private fun deleteWebhookReturns(vararg results: Either<DomainError.NotificationError, Unit>) {
        val remaining = ArrayDeque(results.toList())
        coEvery { api.deleteWebhook() } coAnswers {
            events += "deleteWebhook"
            remaining.removeFirstOrNull() ?: Either.Right(Unit)
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelScopes() = scopes.forEach { it.cancel() }

    // A SupervisorJob scope like the app's, on the virtual clock. Not backgroundScope: advanceUntilIdle ignores it.
    private fun TestScope.startPolling() =
        TelegramPollingService(api, processor, pollTimeoutSeconds = 25)
            .start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it })

    @Test
    fun `deletes webhook before the first getUpdates`() =
        runTest {
            deleteWebhookReturns(Either.Right(Unit))
            script()

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf("deleteWebhook", "getUpdates"), events)
            coVerify { api.getUpdates(null, 25) }
        }

    @Test
    fun `retries deleteWebhook with backoff until it succeeds`() =
        runTest {
            deleteWebhookReturns(network(), network(), Either.Right(Unit))
            script()

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf("deleteWebhook", "deleteWebhook", "deleteWebhook", "getUpdates"), events)
            assertEquals(3_000L, polls.single().atMillis)
        }

    @Test
    fun `unexpected exception from deleteWebhook is retried instead of killing polling`() =
        runTest {
            var attempts = 0
            coEvery { api.deleteWebhook() } coAnswers {
                events += "deleteWebhook"
                if (attempts++ == 0) error("resolver not ready") else Either.Right(Unit)
            }
            script()

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf("deleteWebhook", "deleteWebhook", "getUpdates"), events)
            assertEquals(1_000L, polls.single().atMillis)
        }

    @Test
    fun `advances offset past each update and processes callbacks`() =
        runTest {
            deleteWebhookReturns()
            script(
                updates(5),
                Either.Right(listOf(TelegramUpdate(updateId = 6))),
                updates(7)
            )

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf(null, 6L, 7L, 8L), polls.map { it.offset })
            coVerify(exactly = 1) { processor.process(match { it.id == "cq5" }) }
            coVerify(exactly = 1) { processor.process(match { it.id == "cq7" }) }
            coVerify(exactly = 2) { processor.process(any()) }
        }

    @Test
    fun `409 backs off 30 seconds`() =
        runTest {
            deleteWebhookReturns()
            script(http(409))

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf(0L, 30_000L), polls.map { it.atMillis })
        }

    @Test
    fun `429 waits retry_after`() =
        runTest {
            deleteWebhookReturns()
            script(Either.Left(DomainError.NotificationError.RateLimited("telegram", 17)))

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf(0L, 17_000L), polls.map { it.atMillis })
        }

    @Test
    fun `401 backs off starting at 30 seconds`() =
        runTest {
            deleteWebhookReturns()
            script(http(401), http(404))

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf(0L, 30_000L, 90_000L), polls.map { it.atMillis })
        }

    @Test
    fun `network errors back off from 1s and reset after success`() =
        runTest {
            deleteWebhookReturns()
            script(network(), network(), Either.Right(emptyList()), network())

            startPolling()
            advanceUntilIdle()

            assertEquals(listOf(0L, 1_000L, 3_000L, 3_000L, 4_000L), polls.map { it.atMillis })
        }

    @Test
    fun `processor exception does not stop the loop`() =
        runTest {
            deleteWebhookReturns()
            coEvery { processor.process(any()) } throws IllegalStateException("handler bug")
            script(updates(5), Either.Right(emptyList()))

            startPolling()
            advanceUntilIdle()

            assertEquals(3, polls.size)
        }

    @Test
    fun `cancelling the job stops polling promptly`() =
        runTest {
            deleteWebhookReturns()
            script(network(), network(), network())

            val job = startPolling()
            advanceTimeBy(500)
            job.cancel()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(1, polls.size)
        }
}
