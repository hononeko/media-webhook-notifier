package app.hononeko.notifier.adapter.outbound.notification

import app.hononeko.notifier.config.NotificationConfig

interface NotificationSchemeParser {
    val schemes: Set<String>

    fun parse(
        scheme: String,
        rawUrl: String,
        pathAndAuth: String,
        queryParams: Map<String, String>
    ): NotificationConfig
}
