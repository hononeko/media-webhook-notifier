package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SeerrWebhookDto(
    @SerialName("notification_type") val notificationType: String? = null,
    val event: String? = null,
    val subject: String? = null,
    val message: String? = null,
    val image: String? = null,
    val media: SeerrMediaDto? = null,
    val request: SeerrRequestDto? = null,
    val issue: SeerrIssueDto? = null,
    val comment: SeerrCommentDto? = null,
    val extra: List<SeerrExtraDto>? = null,
    @SerialName("application_url") val applicationUrl: String? = null,
    val url: String? = null
)

@Serializable
data class SeerrMediaDto(
    @SerialName("media_type") val mediaType: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val jellyfinMediaId: String? = null,
    val status: String? = null,
    @SerialName("status4k") val status4k: String? = null
)

@Serializable
data class SeerrRequestDto(
    @SerialName("request_id") val requestId: JsonElement? = null,
    @SerialName("requestedBy_email") val requestedByEmail: String? = null,
    @SerialName("requestedBy_username") val requestedByUsername: String? = null,
    @SerialName("requestedBy_avatar") val requestedByAvatar: String? = null,
    @SerialName("requestedBy_jellyfinUserId") val requestedByJellyfinUserId: String? = null,
    @SerialName("requestedBy_settings_discordIds") val requestedBySettingsDiscordIds: String? = null,
    @SerialName("requestedBy_settings_telegramChatId") val requestedBySettingsTelegramChatId: String? = null,
    @SerialName("is4k") val is4k: JsonElement? = null
)

@Serializable
data class SeerrIssueDto(
    @SerialName("issue_id") val issueId: JsonElement? = null,
    @SerialName("issue_type") val issueType: String? = null,
    @SerialName("issue_status") val issueStatus: String? = null,
    @SerialName("reportedBy_email") val reportedByEmail: String? = null,
    @SerialName("reportedBy_username") val reportedByUsername: String? = null,
    @SerialName("reportedBy_avatar") val reportedByAvatar: String? = null,
    @SerialName("reportedBy_settings_discordIds") val reportedBySettingsDiscordIds: String? = null,
    @SerialName("reportedBy_settings_telegramChatId") val reportedBySettingsTelegramChatId: String? = null
)

@Serializable
data class SeerrCommentDto(
    @SerialName("comment_id") val commentId: JsonElement? = null,
    @SerialName("comment_message") val commentMessage: String? = null,
    @SerialName("commentedBy_email") val commentedByEmail: String? = null,
    @SerialName("commentedBy_username") val commentedByUsername: String? = null,
    @SerialName("commentedBy_avatar") val commentedByAvatar: String? = null,
    @SerialName("commentedBy_settings_discordIds") val commentedBySettingsDiscordIds: String? = null,
    @SerialName("commentedBy_settings_telegramChatId") val commentedBySettingsTelegramChatId: String? = null
)

@Serializable
data class SeerrExtraDto(
    val name: String? = null,
    val value: String? = null
)
