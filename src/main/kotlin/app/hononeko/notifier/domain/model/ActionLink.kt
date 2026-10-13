package app.hononeko.notifier.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ActionLink(
    val label: String,
    val url: String,
    val style: ActionStyle = ActionStyle.DEFAULT
)

@Serializable
enum class ActionStyle {
    DEFAULT,
    PRIMARY,
    SUCCESS,
    DANGER
}
