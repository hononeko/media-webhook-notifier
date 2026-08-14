package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JellyfinWebhookDto(
    @SerialName("NotificationType") val notificationType: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("ItemType") val itemType: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonNumber") val seasonNumber: Int? = null,
    @SerialName("EpisodeNumber") val episodeNumber: Int? = null,
    @SerialName("Year") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("ServerUrl") val serverUrl: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("Resolution") val resolution: String? = null,
    @SerialName("PosterUrl") val posterUrl: String? = null
)
