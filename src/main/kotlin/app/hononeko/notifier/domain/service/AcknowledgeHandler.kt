package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.raise.Raise
import kotlinx.datetime.TimeZone

/**
 * Marks a card as handled: stamps every copy with "Handled by" and removes its callback buttons, so a second press
 * reads as expired. The edit is the whole action, so a pressed card that can't be edited fails the press.
 */
class AcknowledgeHandler(
    publisher: NotificationPublisherPort,
    refRegistry: CallbackRefRegistry,
    clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CardActionHandler(publisher, refRegistry, clock, timeZone) {
    companion object {
        const val OPERATION = "ack"
        const val REPLY = "✅ Acknowledged"
    }

    override val operation: String = OPERATION

    override suspend fun Raise<DomainError>.act(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Outcome =
        Outcome.Stamp(
            target.card.copy(interactions = emptyList(), footnote = byline("✅ Handled by", interaction)),
            BotReply(REPLY),
            mustUpdate = true
        )
}
