package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.port.outbound.MediaRequestManagerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone

/** [CardInteraction.params] keys holding the Seerr ids the buttons act on. */
const val SEERR_REQUEST_ID = "request_id"
const val SEERR_ISSUE_ID = "issue_id"

/** The Seerr buttons: what each one is called, which id it carries, and how the card is stamped afterwards. */
enum class SeerrAction(
    val operation: String,
    val label: String,
    val param: String,
    val stamp: String,
    val reply: String,
    val resource: String
) {
    APPROVE("seerr_approve", "✅ Approve", SEERR_REQUEST_ID, "✅ Approved by", "✅ Approved", "request"),
    DECLINE("seerr_decline", "❌ Decline", SEERR_REQUEST_ID, "❌ Declined by", "❌ Declined", "request"),
    RESOLVE("seerr_resolve", "✔️ Resolve", SEERR_ISSUE_ID, "✔️ Resolved by", "✔️ Resolved", "issue");

    /** The button for this action on the request or issue [id]. */
    fun interaction(id: Long): CardInteraction = CardInteraction(label, operation, mapOf(param to id.toString()))
}

/**
 * Runs one [SeerrAction] for an admin: calls Seerr with the id stored on the card, then stamps every admin's copy with
 * who did it and removes the buttons. The claim on the card's ref (see [CardActionHandler]) means a double-tap can't
 * approve twice, nor two admins approve and decline at once.
 */
class SeerrActionHandler(
    private val action: SeerrAction,
    private val requestManager: MediaRequestManagerPort,
    publisher: NotificationPublisherPort,
    refRegistry: CallbackRefRegistry,
    clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CardActionHandler(publisher, refRegistry, clock, timeZone) {
    companion object {
        const val BUSY = ALREADY_HANDLING

        fun notFound(resource: String) = "⚠️ This $resource no longer exists in Seerr."
    }

    override val operation: String = action.operation
    override val requiresAdmin: Boolean = true

    override suspend fun Raise<DomainError>.act(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Outcome {
        val id =
            ensureNotNull(paramOn(target, action.param)?.toLongOrNull()?.takeIf { it > 0 }) {
                logger.warn("Card '{}' has no {} for '{}'", target.card.title, action.param, operation)
                DomainError.InteractionError.UnknownAction(operation)
            }
        val decided = call(id)
        if (decided.leftOrNull() is DomainError.RequestManagerError.NotFound) {
            return Outcome.Answer(BotReply(notFound(action.resource), alert = true))
        }
        decided.bind()
        return Outcome.Stamp(
            target.card.copy(interactions = emptyList(), footnote = byline(action.stamp, interaction)),
            BotReply(action.reply)
        )
    }

    private suspend fun call(id: Long): Either<DomainError.RequestManagerError, Unit> =
        when (action) {
            SeerrAction.APPROVE -> requestManager.approveRequest(id)
            SeerrAction.DECLINE -> requestManager.declineRequest(id)
            SeerrAction.RESOLVE -> requestManager.resolveIssue(id)
        }
}
