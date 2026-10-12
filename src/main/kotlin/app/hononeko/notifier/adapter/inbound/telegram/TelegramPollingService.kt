package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Long-polls `getUpdates` for callback queries. Supports exactly one polling instance per bot: a second poller (or a
 * registered webhook) makes Telegram answer 409, which is logged and backed off rather than fought over.
 */
internal class TelegramPollingService(
    private val api: TelegramBotApi,
    private val processor: TelegramCallbackProcessor,
    private val pollTimeoutSeconds: Int
) {
    companion object {
        private val CONFLICT_BACKOFF = 30.seconds
    }

    private val logger = LoggerFactory.getLogger(TelegramPollingService::class.java)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            // A webhook left over from webhook mode would make every getUpdates fail with 409.
            retryUntilSuccess("deleteWebhook", ExponentialBackoff(1.seconds, 60.seconds)) { api.deleteWebhook() }
            logger.info("Telegram callback polling started (timeout {}s)", pollTimeoutSeconds)
            pollLoop(scope)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollLoop(scope: CoroutineScope) {
        val networkBackoff = ExponentialBackoff(1.seconds, 30.seconds)
        val rejectedTokenBackoff = ExponentialBackoff(30.seconds, 5.minutes)
        var offset: Long? = null
        while (true) {
            try {
                api.getUpdates(offset, pollTimeoutSeconds).fold(
                    ifLeft = { error -> delay(backoffFor(error, networkBackoff, rejectedTokenBackoff)) },
                    ifRight = { updates ->
                        networkBackoff.reset()
                        rejectedTokenBackoff.reset()
                        for (update in updates) {
                            // Advance first so an update that breaks processing is never fetched again.
                            offset = update.updateId + 1
                            update.callbackQuery?.let { query -> dispatch(scope, query) }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Telegram polling iteration failed: {}", e.message, e)
                delay(networkBackoff.next())
            }
        }
    }

    private fun backoffFor(
        error: DomainError.NotificationError,
        networkBackoff: ExponentialBackoff,
        rejectedTokenBackoff: ExponentialBackoff
    ): Duration {
        val status = (error as? DomainError.NotificationError.DeliveryFailed)?.httpStatus
        val wait =
            when {
                error is DomainError.NotificationError.RateLimited -> error.retryAfterSeconds.seconds
                status == 409 -> CONFLICT_BACKOFF
                status == 401 || status == 404 -> rejectedTokenBackoff.next()
                else -> networkBackoff.next()
            }
        when (status) {
            409 ->
                logger.warn(
                    "getUpdates returned 409: another instance is polling this bot, or a webhook is registered. Retrying in {}",
                    wait
                )
            401, 404 -> logger.error("getUpdates rejected the bot token (HTTP {}). Retrying in {}", status, wait)
            else -> logger.warn("getUpdates failed, retrying in {}: {}", wait, error)
        }
        return wait
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(
        scope: CoroutineScope,
        query: TelegramCallbackQuery
    ) {
        scope.launch {
            try {
                processor.process(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Processing Telegram callback {} failed: {}", query.id, e.message, e)
            }
        }
    }
}
