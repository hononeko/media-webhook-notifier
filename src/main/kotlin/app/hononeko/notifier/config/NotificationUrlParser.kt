package app.hononeko.notifier.config

data class ParsedNotificationUrl(
    val provider: String = "telegram",
    val botToken: String = "",
    val chatId: String = "",
    val topicId: Long? = null,
    val sendPhotos: Boolean = true,
    val rateLimitPerMinute: Int = 30,
    val timeoutSeconds: Long = 5
)

object NotificationUrlParser {
    fun parse(rawUrl: String): ParsedNotificationUrl {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return ParsedNotificationUrl()
        }

        val schemeSplit = trimmed.split("://", limit = 2)
        if (schemeSplit.size < 2) {
            return ParsedNotificationUrl()
        }

        val scheme = schemeSplit[0].lowercase()
        val rest = schemeSplit[1]

        val querySplit = rest.split("?", limit = 2)
        val pathAndAuth = querySplit[0]
        val queryParams = if (querySplit.size > 1) parseQueryParams(querySplit[1]) else emptyMap()

        val topicId =
            queryParams["topic"]?.toLongOrNull()
                ?: queryParams["thread"]?.toLongOrNull()
                ?: queryParams["topic_id"]?.toLongOrNull()

        val sendPhotos =
            queryParams["photos"]?.toBooleanStrictOrNull()
                ?: queryParams["send_photos"]?.toBooleanStrictOrNull()
                ?: queryParams["photo"]?.toBooleanStrictOrNull()
                ?: true

        val rateLimitPerMinute =
            queryParams["rate_limit"]?.toIntOrNull()
                ?: queryParams["rate_limit_per_minute"]?.toIntOrNull()
                ?: 30

        val timeoutSeconds =
            queryParams["timeout"]?.toLongOrNull()
                ?: queryParams["timeout_seconds"]?.toLongOrNull()
                ?: 5L

        var botToken = ""
        var chatId = ""

        if (pathAndAuth.contains("@")) {
            val atSplit = pathAndAuth.split("@", limit = 2)
            botToken = atSplit[0].trim()
            chatId = atSplit[1].trim().removePrefix("/").trim()
        } else if (pathAndAuth.contains("/")) {
            val slashSplit = pathAndAuth.split("/", limit = 2)
            botToken = slashSplit[0].trim()
            chatId = slashSplit[1].trim()
        } else {
            botToken = pathAndAuth.trim()
        }

        return ParsedNotificationUrl(
            provider = scheme,
            botToken = botToken,
            chatId = chatId,
            topicId = topicId,
            sendPhotos = sendPhotos,
            rateLimitPerMinute = rateLimitPerMinute,
            timeoutSeconds = timeoutSeconds
        )
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
