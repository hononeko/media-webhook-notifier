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

class TelegramSchemeParser : NotificationSchemeParser {
    override val schemes: Set<String> = setOf("telegram", "tgram")

    override fun parse(
        scheme: String,
        rawUrl: String,
        pathAndAuth: String,
        queryParams: Map<String, String>
    ): NotificationConfig {
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

        return NotificationConfig(
            url = rawUrl,
            provider = "telegram",
            botToken = botToken,
            chatId = chatId,
            topicId = topicId,
            sendPhotos = sendPhotos,
            rateLimitPerMinute = rateLimitPerMinute,
            timeoutSeconds = timeoutSeconds
        )
    }
}

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
