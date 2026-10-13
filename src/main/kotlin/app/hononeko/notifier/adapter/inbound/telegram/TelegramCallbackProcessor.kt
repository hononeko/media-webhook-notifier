package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.inbound.HandleBotInteractionUseCase
import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles one button click end to end and always answers Telegram, so the client's loading spinner clears:
 * guard → decode → use case (bounded by [answerBudget]) → `answerCallbackQuery`.
 *
 * The use case runs in [scope] rather than the caller's coroutine, so a slow action keeps running after the
 * "Working on it…" answer has been sent.
 */
internal class TelegramCallbackProcessor(
    private val guard: TelegramCallbackGuard,
    private val useCase: HandleBotInteractionUseCase,
    private val api: TelegramBotApi,
    private val scope: CoroutineScope,
    private val answerBudget: Duration = 2500.milliseconds
) {
    companion object {
        const val UNAUTHORIZED = "⛔ You're not allowed to control this bot."
        const val EXPIRED = "⌛ This button has expired."
        const val UNSUPPORTED = "This action isn't supported."
        const val FAILED = "⚠️ Action failed."
        const val WORKING = "⏳ Working on it…"
    }

    private val logger = LoggerFactory.getLogger(TelegramCallbackProcessor::class.java)

    suspend fun process(query: TelegramCallbackQuery) {
        val reply = replyFor(query)
        api.answerCallbackQuery(query.id, reply.text, reply.alert).onLeft { error ->
            logger.warn("Failed to answer Telegram callback {}: {}", query.id, error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun replyFor(query: TelegramCallbackQuery): BotReply {
        val actor = guard.check(query).getOrElse { return BotReply(UNAUTHORIZED, alert = true) }
        val decoded =
            TelegramCallbackData.decode(query.data) ?: run {
                logger.debug("Ignoring Telegram callback {} with foreign data '{}'", query.id, query.data)
                return BotReply("")
            }

        val origin =
            query.message?.let {
                NotificationHandle(
                    "telegram",
                    it.chat.id.toString(),
                    it.messageId.toString(),
                    isPhoto =
                        it.photo != null
                )
            }
        val interaction = BotInteraction(actor, decoded.operation, decoded.ref, origin)
        val pending = scope.async { useCase.handle(interaction) }
        val result =
            try {
                withTimeoutOrNull(answerBudget) { pending.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Bot action '{}' threw: {}", interaction.operation, e.message, e)
                return BotReply(FAILED, alert = true)
            }

        if (result == null) {
            scope.launch {
                val late = runCatching { pending.await() }
                logger.info("Bot action '{}' finished after the answer budget: {}", interaction.operation, late)
            }
            return BotReply(WORKING)
        }
        return toReply(interaction, result)
    }

    private fun toReply(
        interaction: BotInteraction,
        result: Either<DomainError, BotReply>
    ): BotReply =
        result.fold(
            ifRight = { it },
            ifLeft = { error ->
                when (error) {
                    is DomainError.InteractionError.UnauthorizedUser -> BotReply(UNAUTHORIZED, alert = true)
                    is DomainError.InteractionError.ExpiredCallback -> BotReply(EXPIRED, alert = true)
                    is DomainError.InteractionError.UnknownAction -> BotReply(UNSUPPORTED, alert = true)
                    else -> {
                        logger.warn("Bot action '{}' failed: {}", interaction.operation, error)
                        BotReply(FAILED, alert = true)
                    }
                }
            }
        )
}
