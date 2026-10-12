package app.hononeko.notifier.domain.model

import kotlinx.serialization.Serializable

/** Who triggered a chat-bot interaction. Already authorized by the inbound adapter; used for attribution only. */
data class BotActor(
    val platform: String,
    val id: String,
    val displayName: String
)

/** A decoded button click: the operation to run on the target that [ref] points to. */
data class BotInteraction(
    val actor: BotActor,
    val operation: String,
    val ref: String
)

/** Short acknowledgement shown to the actor; [alert] asks the platform for a modal popup instead of a toast. */
data class BotReply(
    val text: String,
    val alert: Boolean = false
)

/** What a callback ref resolves to. Stored as JSON, so fields can be added without changing existing refs. */
@Serializable
data class CallbackTarget(
    val downloadId: String
)
