package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.raise.Raise
import arrow.core.raise.ensureNotNull
import kotlinx.datetime.TimeZone

/** [CardInteraction.params] key holding the [HealthSnoozes.checkOf] key the snooze buttons act on. */
const val SNOOZE_CHECK = "check"

/**
 * `🔕 Snooze 24h` on a health issue card sent to the admins: silences that check for 24h, stamps every admin's copy
 * with who snoozed it and until when, and swaps the buttons for `🔔 Unsnooze` on the same ref.
 */
class SnoozeHandler(
    private val snoozes: HealthSnoozes,
    publisher: NotificationPublisherPort,
    refRegistry: CallbackRefRegistry,
    clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CardActionHandler(publisher, refRegistry, clock, timeZone) {
    companion object {
        const val OPERATION = "snooze"
        const val REPLY = "🔕 Snoozed for 24h"

        fun button(check: String) = CardInteraction("🔕 Snooze 24h", OPERATION, mapOf(SNOOZE_CHECK to check))
    }

    override val operation: String = OPERATION
    override val requiresAdmin: Boolean = true

    override suspend fun Raise<DomainError>.act(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Outcome {
        val check =
            ensureNotNull(paramOn(target, SNOOZE_CHECK)) { DomainError.InteractionError.UnknownAction(operation) }
        val until = snoozes.snooze(check, interaction.actor.displayName)
        logger.info("Health check '{}' snoozed by {} {}", check, interaction.actor.platform, interaction.actor.id)
        val footnote = "🔕 Snoozed until tomorrow ${hourMinute(until, timeZone)} by ${interaction.actor.displayName}"
        return Outcome.Stamp(
            target.card.copy(interactions = listOf(UnsnoozeHandler.button(check)), footnote = footnote),
            BotReply(REPLY)
        )
    }
}

/**
 * `🔔 Unsnooze` on a snoozed health card: ends the snooze early, stamps every admin's copy and removes the button. When
 * the 24h already ran out, it says so and removes the button all the same.
 */
class UnsnoozeHandler(
    private val snoozes: HealthSnoozes,
    publisher: NotificationPublisherPort,
    refRegistry: CallbackRefRegistry,
    clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CardActionHandler(publisher, refRegistry, clock, timeZone) {
    companion object {
        const val OPERATION = "unsnooze"
        const val REPLY = "🔔 Unsnoozed"
        const val ALREADY_ENDED = "🔔 The snooze had already ended."

        fun button(check: String) = CardInteraction("🔔 Unsnooze", OPERATION, mapOf(SNOOZE_CHECK to check))
    }

    override val operation: String = OPERATION
    override val requiresAdmin: Boolean = true

    override suspend fun Raise<DomainError>.act(
        interaction: BotInteraction,
        target: CallbackTarget
    ): Outcome {
        val check =
            ensureNotNull(paramOn(target, SNOOZE_CHECK)) { DomainError.InteractionError.UnknownAction(operation) }
        val wasActive = snoozes.unsnooze(check)
        if (wasActive) {
            logger.info(
                "Health check '{}' unsnoozed by {} {}",
                check,
                interaction.actor.platform,
                interaction.actor.id
            )
        }
        val footnote = if (wasActive) byline("🔔 Unsnoozed by", interaction) else "🔔 Snooze ended"
        return Outcome.Stamp(
            target.card.copy(interactions = emptyList(), footnote = footnote),
            BotReply(if (wasActive) REPLY else ALREADY_ENDED)
        )
    }
}
