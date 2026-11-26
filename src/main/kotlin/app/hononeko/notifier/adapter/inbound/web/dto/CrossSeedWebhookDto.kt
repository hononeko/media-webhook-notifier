package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.Serializable

/**
 * cross-seed's push notification (`notificationWebhookUrls`, v7 `PushNotifier`): a `title` / `body` summary plus the
 * structured `extra`. `extra.event` is `TEST` for `cross-seed test-notification` and `RESULTS` for matches.
 */
@Serializable
data class CrossSeedWebhookDto(
    val title: String? = null,
    val body: String? = null,
    val extra: CrossSeedExtraDto? = null
)

@Serializable
data class CrossSeedExtraDto(
    val event: String? = null,
    val name: String? = null,
    val infoHashes: List<String>? = null,
    val trackers: List<String>? = null,
    /** The search that found the match: `search`, `rss`, `announce`, `webhook` or `inject`. */
    val source: String? = null,
    /** `INJECTED`, `SAVED` or `FAILURE`. */
    val result: String? = null,
    val paused: Boolean? = null,
    val decisions: List<String>? = null,
    val searchee: CrossSeedSearcheeDto? = null
)

/** The torrent or data cross-seed matched against. */
@Serializable
data class CrossSeedSearcheeDto(
    val category: String? = null,
    val tags: List<String>? = null,
    val trackers: List<String>? = null,
    val length: Long? = null,
    val clientHost: String? = null,
    val infoHash: String? = null,
    val path: String? = null,
    /** `torrentClient`, `torrentFile`, `dataDir` or `virtual`. */
    val source: String? = null
)
