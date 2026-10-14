package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory

/**
 * Marks a card as handled: re-renders the stored card with a "Handled by" footnote and without its callback
 * buttons, edits the message it was pressed on (and every other copy, for a card sent to each admin), then releases
 * the ref so a second press reads as expired.
 */
class AcknowledgeHandler(
    private val publisher: NotificationPublisherPort,
    private val refRegistry: CallbackRefRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : BotActionHandler {
    companion object {
        const val OPERATION = "ack"
        const val REPLY = "✅ Acknowledged"
    }

    private val logger = LoggerFactory.getLogger(AcknowledgeHandler::class.java)

    override val operation: String = OPERATION

    override suspend fun handle(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Either<DomainError, BotReply> =
        either {
            val origin =
                ensureNotNull(interaction.origin) {
                    DomainError.NotificationError.DeliveryFailed(publisher.providerId, "no message to acknowledge")
                }
            val stamped =
                target.card.copy(
                    interactions = emptyList(),
                    footnote = "✅ Handled by ${interaction.actor.displayName} · ${hourMinute(clock(), timeZone)}"
                )
            publisher.editCard(origin, stamped).bind()
            // A card sent to every admin is marked handled on each copy; those edits are best effort.
            otherCopies(target, origin).forEach { copy ->
                publisher.editCard(copy, stamped).onLeft { error ->
                    logger.warn("Could not mark the copy in chat {} as handled: {}", copy.channelOrChatId, error)
                }
            }
            refRegistry.release(interaction.ref)
            BotReply(REPLY)
        }
}
