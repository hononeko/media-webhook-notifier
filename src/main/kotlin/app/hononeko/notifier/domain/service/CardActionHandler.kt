package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Reply to a press while another action holds the card's ref (a double-tap, or two admins at once). */
const val ALREADY_HANDLING = "⏳ Someone is already handling this."

/**
 * A button that acts on the card it sits on. Every such press runs the same way; subclasses only supply [act]:
 * 1. the press must come from a message, which is updated afterwards;
 * 2. the card's ref is claimed, so a double-tap, or two admins pressing buttons on their copies, can't act twice;
 * 3. [act] runs. If it fails, or only answers ([Outcome.Answer]), the claim is dropped and the button can be pressed
 *    again;
 * 4. on [Outcome.Stamp], every copy of the card (the pressed one, and the other admins' copies) shows the new card.
 *    Buttons left on it stay on the same ref, which is kept; otherwise the ref is released, so later presses read as
 *    expired.
 */
abstract class CardActionHandler(
    protected val publisher: NotificationPublisherPort,
    protected val refRegistry: CallbackRefRegistry,
    protected val clock: () -> Long,
    protected val timeZone: TimeZone
) : BotActionHandler {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /** What a press led to. */
    protected sealed interface Outcome {
        val reply: BotReply

        /**
         * Show [card] on every copy. [mustUpdate] is for actions whose only effect is the edit itself (Acknowledge):
         * then a failed edit of the pressed copy fails the press. Otherwise the action already happened and edit
         * failures are only logged.
         */
        data class Stamp(
            val card: NotificationCard,
            override val reply: BotReply,
            val mustUpdate: Boolean = false
        ) : Outcome

        /** Nothing changed: the card stays as it is. */
        data class Answer(
            override val reply: BotReply
        ) : Outcome
    }

    /** The action itself. Raised errors leave the card untouched and its button usable. */
    protected abstract suspend fun Raise<DomainError>.act(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Outcome

    final override suspend fun handle(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Either<DomainError, BotReply> =
        either {
            val origin =
                ensureNotNull(interaction.origin) {
                    DomainError.NotificationError.DeliveryFailed(publisher.providerId, "no message to update")
                }
            if (!refRegistry.claim(interaction.ref)) return@either BotReply(ALREADY_HANDLING, alert = true)

            val outcome = either { act(interaction, target) }.onLeft { refRegistry.unclaim(interaction.ref) }.bind()
            when (outcome) {
                is Outcome.Answer -> refRegistry.unclaim(interaction.ref)
                is Outcome.Stamp -> show(outcome, interaction, target, origin)
            }
            outcome.reply
        }

    private suspend fun Raise<DomainError>.show(
        outcome: Outcome.Stamp,
        interaction: BotInteraction,
        target: CallbackTarget,
        origin: NotificationHandle
    ) {
        val ref = interaction.ref
        val keepsButtons = outcome.card.interactions.isNotEmpty()
        // Buttons left on the card keep resolving this ref, which must now hold the new card and still list every copy.
        if (keepsButtons) {
            refRegistry.update(
                ref,
                target.copy(card = outcome.card),
                CallbackRefRegistry.DEFAULT_TTL_SECONDS
            )
        }
        val buttonsRef = ref.takeIf { keepsButtons }

        val pressed = publisher.editCard(origin, outcome.card, buttonsRef)
        if (outcome.mustUpdate) {
            pressed.onLeft { refRegistry.unclaim(ref) }.bind()
        } else {
            pressed.onLeft { logger.warn("'{}' done, but the pressed card could not be updated: {}", operation, it) }
        }
        otherCopies(target, origin).forEach { copy ->
            publisher.editCard(copy, outcome.card, buttonsRef).onLeft { error ->
                logger.warn(
                    "Could not update the copy of '{}' in chat {}: {}",
                    outcome.card.title,
                    copy.channelOrChatId,
                    error
                )
            }
        }
        if (keepsButtons) refRegistry.unclaim(ref) else refRegistry.release(ref)
    }

    /** The usual stamp on a card someone acted on: `<prefix> @alex · 14:32`. */
    protected fun byline(
        prefix: String,
        interaction: BotInteraction
    ): String = "$prefix ${interaction.actor.displayName} · ${hourMinute(clock(), timeZone)}"

    /** The [key] param of this handler's own button on the pressed card, or `null` when it has none. */
    protected fun paramOn(
        target: CallbackTarget,
        key: String
    ): String? =
        target.card.interactions
            .firstOrNull { it.operation == operation }
            ?.params
            ?.get(key)
            ?.ifBlank { null }

    /** The other copies of a card delivered to several chats (e.g. one per admin). */
    private fun otherCopies(
        target: CallbackTarget,
        origin: NotificationHandle
    ): List<NotificationHandle> =
        target.deliveries.filterNot {
            it.channelOrChatId == origin.channelOrChatId && it.messageReferenceId == origin.messageReferenceId
        }
}
