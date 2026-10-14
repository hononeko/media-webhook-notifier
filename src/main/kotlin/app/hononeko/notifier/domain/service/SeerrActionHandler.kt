package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.port.outbound.MediaRequestManagerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory

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
 * Runs one [SeerrAction] for an admin: calls Seerr with the id stored on the card, then stamps every admin's copy of
 * the card with who did it and removes its callback buttons. The ref is claimed for the duration of the call, so a double-tap can't approve
 * twice or approve and decline at once, and released once Seerr has accepted the decision.
 */
class SeerrActionHandler(
    private val action: SeerrAction,
    private val requestManager: MediaRequestManagerPort,
    private val publisher: NotificationPublisherPort,
    private val refRegistry: CallbackRefRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : BotActionHandler {
    companion object {
        const val BUSY = "⏳ Someone is already handling this."

        fun notFound(resource: String) = "⚠️ This $resource no longer exists in Seerr."
    }

    private val logger = LoggerFactory.getLogger(SeerrActionHandler::class.java)

    override val operation: String = action.operation
    override val requiresAdmin: Boolean = true

    override suspend fun handle(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Either<DomainError, BotReply> =
        either {
            val origin =
                ensureNotNull(interaction.origin) {
                    DomainError.NotificationError.DeliveryFailed(publisher.providerId, "no message to update")
                }
            val id =
                ensureNotNull(idOn(target)) {
                    logger.warn("Card '{}' has no {} for '{}'", target.card.title, action.param, operation)
                    DomainError.InteractionError.UnknownAction(operation)
                }
            if (!refRegistry.claim(interaction.ref)) return@either BotReply(BUSY, alert = true)

            val decided = call(id)
            decided.onLeft { refRegistry.unclaim(interaction.ref) }
            if (decided.leftOrNull() is DomainError.RequestManagerError.NotFound) {
                return@either BotReply(notFound(action.resource), alert = true)
            }
            decided.bind()

            val stamped =
                target.card.copy(
                    interactions = emptyList(),
                    footnote = "${action.stamp} ${interaction.actor.displayName} · ${hourMinute(clock(), timeZone)}"
                )
            // Every admin got a copy; stamp them all so nobody acts on a decided request. Seerr already has the
            // decision, so a failed edit must not turn into "Action failed" or allow a re-press.
            (listOf(origin) + otherCopies(target, origin)).forEach { copy ->
                publisher.editCard(copy, stamped).onLeft { error ->
                    logger.warn(
                        "Seerr {} {} done, but the copy in chat {} could not be updated: {}",
                        action.resource,
                        id,
                        copy.channelOrChatId,
                        error
                    )
                }
            }
            refRegistry.release(interaction.ref)
            BotReply(action.reply)
        }

    private fun idOn(target: CallbackTarget): Long? =
        target.card.interactions
            .firstOrNull { it.operation == operation }
            ?.params
            ?.get(action.param)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

    private suspend fun call(id: Long): Either<DomainError.RequestManagerError, Unit> =
        when (action) {
            SeerrAction.APPROVE -> requestManager.approveRequest(id)
            SeerrAction.DECLINE -> requestManager.declineRequest(id)
            SeerrAction.RESOLVE -> requestManager.resolveIssue(id)
        }
}
