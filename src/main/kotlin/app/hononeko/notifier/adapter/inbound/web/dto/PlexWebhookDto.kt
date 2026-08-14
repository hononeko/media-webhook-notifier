package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlexWebhookDto(
    val event: String? = null,
    val user: Boolean? = null,
    val owner: Boolean? = null,
    val Server: PlexServerDto? = null,
    val Metadata: PlexMetadataDto? = null
)

@Serializable
data class PlexServerDto(
    val title: String? = null,
    val uuid: String? = null
)

@Serializable
data class PlexMetadataDto(
    val librarySectionType: String? = null,
    val ratingKey: String? = null,
    val key: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val title: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val type: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val duration: Long? = null,
    val rating: Double? = null,
    val thumb: String? = null,
    val art: String? = null,
    val Media: List<PlexMediaStreamDto> = emptyList()
)

@Serializable
data class PlexMediaStreamDto(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val container: String? = null,
    val duration: Long? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null
)
