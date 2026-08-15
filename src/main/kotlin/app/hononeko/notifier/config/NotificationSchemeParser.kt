package app.hononeko.notifier.config

interface NotificationSchemeParser {
    val schemes: Set<String>

    fun parse(
        scheme: String,
        rawUrl: String,
        pathAndAuth: String,
        queryParams: Map<String, String>
    ): NotificationConfig
}
