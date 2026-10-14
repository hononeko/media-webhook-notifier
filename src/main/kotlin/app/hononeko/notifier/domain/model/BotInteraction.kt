package app.hononeko.notifier.domain.model

import kotlinx.serialization.Serializable

/**
 * Who triggered a chat-bot interaction. Already authorized by the inbound adapter, which also decides [isAdmin];
 * handlers marked [app.hononeko.notifier.domain.service.BotActionHandler.requiresAdmin] only run for admins.
 */
data class BotActor(
    val platform: String,
    val id: String,
    val displayName: String,
    val isAdmin: Boolean = false
)

/**
 * A decoded button click: the operation to run on the target that [ref] points to.
 * [origin] is the message the button was pressed on, when the platform reports one.
 */
data class BotInteraction(
    val actor: BotActor,
    val operation: String,
    val ref: String,
    val origin: NotificationHandle? = null
)

/** Short acknowledgement shown to the actor; [alert] asks the platform for a modal popup instead of a toast. */
data class BotReply(
    val text: String,
    val alert: Boolean = false
)

/**
 * What a callback ref resolves to: the card the buttons were sent on, so a handler can re-render it.
 * Stored as JSON, so fields can be added without changing existing refs.
 */
@Serializable
data class CallbackTarget(
    val card: NotificationCard
)
