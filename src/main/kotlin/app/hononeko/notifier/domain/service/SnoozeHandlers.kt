package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory

/** [CardInteraction.params] key holding the [HealthSnoozes.checkOf] key the snooze buttons act on. */
const val SNOOZE_CHECK = "check"

/**
 * `🔕 Snooze 24h` on a health issue card sent to the admins: silences that check for 24h, stamps every admin's copy
 * with who snoozed it and until when, and swaps the buttons for `🔔 Unsnooze` on the same ref.
 */
class SnoozeHandler(
    private val snoozes: HealthSnoozes,
    private val publisher: NotificationPublisherPort,
    private val refRegistry: CallbackRefRegistry,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : BotActionHandler {
    companion object {
        const val OPERATION = "snooze"
        const val REPLY = "🔕 Snoozed for 24h"

        fun button(check: String) = CardInteraction("🔕 Snooze 24h", OPERATION, mapOf(SNOOZE_CHECK to check))
    }

    private val logger = LoggerFactory.getLogger(SnoozeHandler::class.java)

    override val operation: String = OPERATION
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
            val check =
                ensureNotNull(checkOn(target, OPERATION)) { DomainError.InteractionError.UnknownAction(operation) }
            if (!refRegistry.claim(interaction.ref)) return@either BotReply(ALREADY_HANDLING, alert = true)

            val until = snoozes.snooze(check, interaction.actor.displayName)
            logger.info("Health check '{}' snoozed by {} {}", check, interaction.actor.platform, interaction.actor.id)
            val stamped =
                target.card.copy(
                    interactions = listOf(UnsnoozeHandler.button(check)),
                    footnote = "🔕 Snoozed until tomorrow ${hourMinute(
                        until,
                        timeZone
                    )} by ${interaction.actor.displayName}"
                )
            // Every copy's Unsnooze stays on this ref, which must now hold the stamped card (and still list the copies).
            refRegistry.update(interaction.ref, target.copy(card = stamped), CallbackRefRegistry.DEFAULT_TTL_SECONDS)
            publisher.editCard(origin, stamped, interaction.ref).onLeft { error ->
                logger.warn("Check '{}' snoozed, but the pressed card could not be updated: {}", check, error)
            }
            publisher.editOtherCopies(target, origin, stamped, logger, interaction.ref)
            refRegistry.unclaim(interaction.ref)
            BotReply(REPLY)
        }
}

/**
 * `🔔 Unsnooze` on a snoozed health card: ends the snooze early, stamps every admin's copy and removes the button. When
 * the 24h already ran out, it says so and removes the button all the same.
 */
class UnsnoozeHandler(
    private val snoozes: HealthSnoozes,
    private val publisher: NotificationPublisherPort,
    private val refRegistry: CallbackRefRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : BotActionHandler {
    companion object {
        const val OPERATION = "unsnooze"
        const val REPLY = "🔔 Unsnoozed"
        const val ALREADY_ENDED = "🔔 The snooze had already ended."

        fun button(check: String) = CardInteraction("🔔 Unsnooze", OPERATION, mapOf(SNOOZE_CHECK to check))
    }

    private val logger = LoggerFactory.getLogger(UnsnoozeHandler::class.java)

    override val operation: String = OPERATION
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
            val check =
                ensureNotNull(checkOn(target, OPERATION)) { DomainError.InteractionError.UnknownAction(operation) }
            if (!refRegistry.claim(interaction.ref)) return@either BotReply(ALREADY_HANDLING, alert = true)

            val wasActive = snoozes.unsnooze(check)
            val time = hourMinute(clock(), timeZone)
            val footnote =
                if (wasActive) "🔔 Unsnoozed by ${interaction.actor.displayName} · $time" else "🔔 Snooze ended"
            if (wasActive) {
                logger.info(
                    "Health check '{}' unsnoozed by {} {}",
                    check,
                    interaction.actor.platform,
                    interaction.actor.id
                )
            }
            val stamped = target.card.copy(interactions = emptyList(), footnote = footnote)
            publisher.editCard(origin, stamped).onLeft { error ->
                logger.warn("Check '{}' unsnoozed, but the pressed card could not be updated: {}", check, error)
            }
            publisher.editOtherCopies(target, origin, stamped, logger)
            refRegistry.release(interaction.ref)
            BotReply(if (wasActive) REPLY else ALREADY_ENDED)
        }
}

private fun checkOn(
    target: CallbackTarget,
    operation: String
): String? =
    target.card.interactions
        .firstOrNull { it.operation == operation }
        ?.params
        ?.get(SNOOZE_CHECK)
        ?.ifBlank { null }
