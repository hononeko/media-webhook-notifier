package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class ServarrWebhookDto(
    val eventType: String? = null,
    val series: ServarrSeriesDto? = null,
    val movie: ServarrMovieDto? = null,
    val episodes: List<ServarrEpisodeDto> = emptyList(),
    val episodeFile: ServarrEpisodeFileDto? = null,
    val movieFile: ServarrMovieFileDto? = null,
    val release: ServarrReleaseDto? = null,
    val downloadInfo: ServarrDownloadInfoDto? = null,
    val downloadStatusMessages: List<ServarrDownloadStatusMessageDto> = emptyList(),
    val downloadId: String? = null,
    val downloadClient: String? = null,
    val isUpgrade: Boolean? = null,
    val upgrade: ServarrUpgradeDto? = null,
    val level: String? = null,
    val message: String? = null,
    val type: String? = null,
    val wikiUrl: String? = null,
    val instanceName: String? = null,
    val applicationUrl: String? = null
)

@Serializable
data class ServarrSeriesDto(
    val id: Int? = null,
    val title: String? = null,
    val path: String? = null,
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val images: List<ServarrImageDto> = emptyList()
)

@Serializable
data class ServarrMovieDto(
    val id: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val overview: String? = null,
    val images: List<ServarrImageDto> = emptyList()
)

@Serializable
data class ServarrEpisodeDto(
    val id: Int? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val title: String? = null,
    val overview: String? = null
)

@Serializable
data class ServarrReleaseDto(
    val quality: String? = null,
    val qualityVersion: Int? = null,
    val releaseGroup: String? = null,
    val releaseTitle: String? = null,
    val indexer: String? = null,
    val size: Long? = null,
    val customFormatScore: Int? = null
)

@Serializable
data class ServarrUpgradeDto(
    val isUpgrade: Boolean? = null
)

@Serializable
data class ServarrImageDto(
    val coverType: String? = null,
    val remoteUrl: String? = null,
    val url: String? = null
)

@Serializable
data class ServarrMovieFileDto(
    val id: Int? = null,
    val relativePath: String? = null,
    val path: String? = null,
    val quality: String? = null,
    val qualityVersion: Int? = null,
    val releaseGroup: String? = null,
    val size: Long? = null,
    val mediaInfo: ServarrMediaInfoDto? = null
)

@Serializable
data class ServarrEpisodeFileDto(
    val id: Int? = null,
    val relativePath: String? = null,
    val path: String? = null,
    val quality: String? = null,
    val qualityVersion: Int? = null,
    val releaseGroup: String? = null,
    val size: Long? = null,
    val mediaInfo: ServarrMediaInfoDto? = null
)

@Serializable
data class ServarrMediaInfoDto(
    val audioChannels: Double? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val videoDynamicRange: String? = null,
    val videoDynamicRangeType: String? = null,
    val height: Int? = null,
    val width: Int? = null
)

@Serializable
data class ServarrDownloadInfoDto(
    val quality: String? = null,
    val qualityVersion: Int? = null,
    val title: String? = null,
    val indexer: String? = null,
    val size: Long? = null
)

@Serializable
data class ServarrDownloadStatusMessageDto(
    val title: String? = null,
    val messages: List<String> = emptyList()
)
