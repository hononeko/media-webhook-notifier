package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import arrow.core.Either

/** One button operation (e.g. `ack`) that [BotInteractionService] dispatches to by [operation]. */
interface BotActionHandler {
    val operation: String

    /** [interaction] carries the actor, the ref and the clicked message ([BotInteraction.origin]). */
    suspend fun handle(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Either<DomainError, BotReply>
}
