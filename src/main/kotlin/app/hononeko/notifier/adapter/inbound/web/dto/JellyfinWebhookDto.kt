package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class JellyfinWebhookDto(
    val NotificationType: String? = null,
    val ItemId: String? = null,
    val ItemType: String? = null,
    val Name: String? = null,
    val SeriesName: String? = null,
    val SeasonNumber: Int? = null,
    val EpisodeNumber: Int? = null,
    val Year: Int? = null,
    val Overview: String? = null,
    val ServerId: String? = null,
    val ServerName: String? = null,
    val ServerUrl: String? = null,
    val VideoCodec: String? = null,
    val AudioCodec: String? = null,
    val Resolution: String? = null,
    val PosterUrl: String? = null
)
