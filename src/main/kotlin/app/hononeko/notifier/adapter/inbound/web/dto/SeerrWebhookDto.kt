package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val status: String? = null,
    @SerialName("status4k") val status4k: String? = null
)

@Serializable
data class SeerrRequestDto(
    @SerialName("request_id") val requestId: Long? = null,
    @SerialName("requestedBy_email") val requestedByEmail: String? = null,
    @SerialName("requestedBy_username") val requestedByUsername: String? = null,
    @SerialName("requestedBy_avatar") val requestedByAvatar: String? = null,
    @SerialName("is4k") val is4k: Boolean? = null
)

@Serializable
data class SeerrIssueDto(
    @SerialName("issue_id") val issueId: Long? = null,
    @SerialName("issue_type") val issueType: String? = null,
    @SerialName("issue_status") val issueStatus: String? = null,
    @SerialName("reportedBy_email") val reportedByEmail: String? = null,
    @SerialName("reportedBy_username") val reportedByUsername: String? = null,
    @SerialName("reportedBy_avatar") val reportedByAvatar: String? = null
)

@Serializable
data class SeerrCommentDto(
    @SerialName("comment_id") val commentId: Long? = null,
    @SerialName("comment_message") val commentMessage: String? = null,
    @SerialName("commentedBy_email") val commentedByEmail: String? = null,
    @SerialName("commentedBy_username") val commentedByUsername: String? = null,
    @SerialName("commentedBy_avatar") val commentedByAvatar: String? = null
)

@Serializable
data class SeerrExtraDto(
    val name: String? = null,
    val value: String? = null
)
