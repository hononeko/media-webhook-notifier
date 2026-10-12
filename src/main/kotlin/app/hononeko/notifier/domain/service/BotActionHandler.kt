package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotActor
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import arrow.core.Either

/** One button operation (e.g. `pause`) that [BotInteractionService] dispatches to by [operation]. */
interface BotActionHandler {
    val operation: String

    suspend fun handle(
        actor: BotActor,
        target: CallbackTarget
    ): Either<DomainError, BotReply>
}
