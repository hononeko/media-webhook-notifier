package app.hononeko.notifier.domain.model

data class ProgressUpdate(
    val trackingKey: String,
    val title: String,
    val percent: Double,
    val progressBar: String,
    val speedFormatted: String,
    val etaFormatted: String,
    val sizeFormatted: String,
    val peersInfo: String,
    val stateText: String,
    val subtitle: String? = null,
    val customBody: String? = null,
    val episodeTracks: String? = null,
    val actions: List<ActionLink> = emptyList()
)
