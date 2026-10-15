package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import arrow.core.Either
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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

/** `HH:mm` of [epochMillis] in [timeZone], for the "Handled by" / "Approved by" stamps on edited cards. */
internal fun hourMinute(
    epochMillis: Long,
    timeZone: TimeZone
): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
