package app.hononeko.notifier.adapter.outbound.notification

import app.hononeko.notifier.adapter.outbound.telegram.TelegramSchemeParser
import app.hononeko.notifier.config.NotificationConfig

object NotificationUrlParser {
    private val parsers: List<NotificationSchemeParser> =
        listOf(
            TelegramSchemeParser()
        )

    fun parse(rawUrl: String): NotificationConfig {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return NotificationConfig()
        }

        val schemeSplit = trimmed.split("://", limit = 2)
        if (schemeSplit.size < 2) {
            return NotificationConfig(url = rawUrl)
        }

        val scheme = schemeSplit[0].lowercase()
        val rest = schemeSplit[1]

        val querySplit = rest.split("?", limit = 2)
        val pathAndAuth = querySplit[0]
        val queryParams = if (querySplit.size > 1) parseQueryParams(querySplit[1]) else emptyMap()

        val matchedParser = parsers.firstOrNull { it.schemes.contains(scheme) }
        return matchedParser?.parse(scheme, rawUrl, pathAndAuth, queryParams)
            ?: NotificationConfig(url = rawUrl, provider = scheme)
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (pair in queryString.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val key = parts[0].trim().lowercase()
                val value = if (parts.size > 1) parts[1].trim() else ""
                params[key] = value
            }
        }
        return params
    }
}
