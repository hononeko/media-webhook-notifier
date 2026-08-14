package app.hononeko.notifier.domain.model

data class ActionLink(
    val label: String,
    val url: String,
    val style: ActionStyle = ActionStyle.DEFAULT
)

enum class ActionStyle {
    DEFAULT,
    PRIMARY,
    SUCCESS,
    DANGER
}
