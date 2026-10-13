package app.hononeko.notifier.domain.model

import kotlinx.serialization.Serializable

/**
 * A button that calls back into the notifier (as opposed to an [ActionLink], which opens a URL).
 * [operation] selects the bot action handler; [params] carry whatever that handler needs from the card.
 */
@Serializable
data class CardInteraction(
    val label: String,
    val operation: String,
    val params: Map<String, String> = emptyMap()
)
