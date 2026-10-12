package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Delay sequence `initial, 2×initial, 4×initial, …` capped at [max]; [reset] starts over after a success. */
internal class ExponentialBackoff(
    private val initial: Duration,
    private val max: Duration
) {
    private var current = initial

    fun next(): Duration = current.also { current = (current * 2).coerceAtMost(max) }

    fun reset() {
        current = initial
    }
}

private val retryLogger = LoggerFactory.getLogger("app.hononeko.notifier.adapter.inbound.telegram.Retry")

/**
 * Repeats [block] until it succeeds, honouring Telegram's `retry_after` and otherwise backing off. An unexpected
 * exception is retried too: these calls run once at startup, and a job that dies here would never come back.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun retryUntilSuccess(
    name: String,
    backoff: ExponentialBackoff,
    block: suspend () -> Either<DomainError.NotificationError, Unit>
) {
    while (true) {
        val failure: Any =
            try {
                block().leftOrNull() ?: return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        val wait =
            when (failure) {
                is DomainError.NotificationError.RateLimited -> failure.retryAfterSeconds.seconds
                else -> backoff.next()
            }
        retryLogger.warn("Telegram {} failed, retrying in {}: {}", name, wait, failure)
        delay(wait)
    }
}
