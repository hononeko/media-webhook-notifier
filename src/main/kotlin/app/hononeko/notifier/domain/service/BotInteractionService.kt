package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.port.inbound.HandleBotInteractionUseCase
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull

class BotInteractionService(
    handlers: List<BotActionHandler>,
    private val refRegistry: CallbackRefRegistry
) : HandleBotInteractionUseCase {
    private val handlersByOperation = handlers.associateBy { it.operation }

    init {
        require(handlersByOperation.size == handlers.size) {
            "Duplicate bot action operations: ${handlers.groupBy { it.operation }.filterValues { it.size > 1 }.keys}"
        }
    }

    override suspend fun handle(interaction: BotInteraction): Either<DomainError, BotReply> =
        either {
            // Operation first: it needs no I/O, so unknown operations never touch the state store.
            val handler =
                ensureNotNull(handlersByOperation[interaction.operation]) {
                    DomainError.InteractionError.UnknownAction(interaction.operation)
                }
            val target =
                ensureNotNull(refRegistry.resolve(interaction.ref)) {
                    DomainError.InteractionError.ExpiredCallback(interaction.ref)
                }
            handler.handle(interaction.actor, target).bind()
        }
}
