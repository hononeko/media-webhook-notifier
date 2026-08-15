package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.adapter.outbound.notification.NotificationSchemeParser
import app.hononeko.notifier.config.NotificationConfig

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

        val (botToken, chatId) =
            when {
                pathAndAuth.contains("@") -> {
                    val atSplit = pathAndAuth.split("@", limit = 2)
                    atSplit[0].trim() to atSplit[1].trim().removePrefix("/").trim()
                }
                pathAndAuth.contains("/") -> {
                    val slashSplit = pathAndAuth.split("/", limit = 2)
                    slashSplit[0].trim() to slashSplit[1].trim()
                }
                else -> {
                    pathAndAuth.trim() to ""
                }
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
