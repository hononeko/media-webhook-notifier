package app.hononeko.notifier.domain.model

data class ProgressUpdate(
    val trackingKey: String,
    val title: String,
    val percent: Int,
    val progressBar: String,
    val speedFormatted: String,
    val etaFormatted: String,
    val sizeFormatted: String,
    val peersInfo: String,
    val stateText: String,
    val actions: List<ActionLink> = emptyList()
)
