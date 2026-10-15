package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.Logger
import kotlin.time.Instant

/** One button operation (e.g. `ack`) that [BotInteractionService] dispatches to by [operation]. */
interface BotActionHandler {
    val operation: String

    /** Admin-only operations are refused for non-admin actors before their ref is even looked up. */
    val requiresAdmin: Boolean get() = false

    /** [interaction] carries the actor, the ref and the clicked message ([BotInteraction.origin]). */
    suspend fun handle(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Either<DomainError, BotReply>
}

/** Reply to a press while another action holds the card's ref (a double-tap, or two admins at once). */
const val ALREADY_HANDLING = "⏳ Someone is already handling this."

/**
 * The other copies of a card delivered to several chats (e.g. one per admin), so a handler can update them along
 * with [origin], the copy that was pressed.
 */
internal fun otherCopies(
    target: CallbackTarget,
    origin: NotificationHandle
): List<NotificationHandle> =
    target.deliveries.filterNot {
        it.channelOrChatId == origin.channelOrChatId && it.messageReferenceId == origin.messageReferenceId
    }

/**
 * Updates every copy of [target]'s card except [origin] (the pressed one) to [card]. Best effort: the press already
 * took effect, so a copy that can't be edited (deleted, bot blocked) is only logged. [callbackRef] keeps remaining
 * buttons on the existing ref (see [NotificationPublisherPort.editCard]).
 */
internal suspend fun NotificationPublisherPort.editOtherCopies(
    target: CallbackTarget,
    origin: NotificationHandle,
    card: NotificationCard,
    logger: Logger,
    callbackRef: String? = null
) {
    otherCopies(target, origin).forEach { copy ->
        editCard(copy, card, callbackRef).onLeft { error ->
            logger.warn("Could not update the copy of '{}' in chat {}: {}", card.title, copy.channelOrChatId, error)
        }
    }
}

/** `HH:mm` of [epochMillis] in [timeZone], for the "Handled by" / "Approved by" stamps on edited cards. */
internal fun hourMinute(
    epochMillis: Long,
    timeZone: TimeZone
): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
