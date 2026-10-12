package app.hononeko.notifier.domain.port.inbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import arrow.core.Either

/** Runs a chat-bot button click whose actor the inbound adapter has already authorized. */
fun interface HandleBotInteractionUseCase {
    suspend fun handle(interaction: BotInteraction): Either<DomainError, BotReply>
}
