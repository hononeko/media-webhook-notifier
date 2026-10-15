package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.days

class MediaAvailableDeduplicator(
    private val stateStore: StateStorePort? = null,
    private val ttlMillis: Long = 30.days.inWholeMilliseconds,
    private val maxCapacity: Int = 10_000,
    private val maxAgeSeconds: Long = 86_400L
) {
    private val logger = LoggerFactory.getLogger(MediaAvailableDeduplicator::class.java)
    private val fallback = TtlCache(ttlMillis, maxCapacity)

    fun computeKeys(payload: MediaPayload): List<String> =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> computePlexKeys(payload)
            is MediaPayload.JellyfinItemAdded -> {
                computeKey(payload)?.let { listOf(it) } ?: emptyList()
            }
            else -> emptyList()
        }

    private fun computePlexKeys(payload: MediaPayload.PlexLibraryNew): List<String> {
        val instance = (payload.instanceName ?: "").trim().lowercase()
        val serverId = (payload.serverMachineIdentifier ?: "").trim().lowercase()
        if (payload.ratingKeys.isNotEmpty()) {
            return payload.ratingKeys
                .filter { it.isNotBlank() }
                .map { "plex:$instance:$serverId:${it.trim()}" }
        }
        if (!payload.ratingKey.isNullOrBlank()) {
            return listOf("plex:$instance:$serverId:${payload.ratingKey.trim()}")
        }
        val grandParent = (payload.grandParentTitle ?: "").trim().lowercase()
        val parent = (payload.parentTitle ?: "").trim().lowercase()
        val title = payload.title.trim().lowercase()
        val season = payload.seasonNumber ?: 0
        val ep = payload.episodeNumber ?: 0
        val yr = payload.year ?: 0
        return listOf("plex:$instance:$grandParent:$parent:$title:s$season:e$ep:$yr")
    }

    fun computeKey(payload: MediaPayload): String? =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> computeKeys(payload).firstOrNull()
            is MediaPayload.JellyfinItemAdded -> {
                val instance = (payload.instanceName ?: "").trim().lowercase()
                val serverId = (payload.serverId ?: "").trim().lowercase()
                if (payload.itemId.isNotBlank()) {
                    "jellyfin:$instance:$serverId:${payload.itemId.trim()}"
                } else {
                    val series = (payload.seriesName ?: "").trim().lowercase()
                    val title = payload.title.trim().lowercase()
                    val season = payload.seasonNumber ?: 0
                    val ep = payload.episodeNumber ?: 0
                    val yr = payload.year ?: 0
                    "jellyfin:$instance:$series:$title:s$season:e$ep:$yr"
                }
            }
            else -> null
        }

    fun isStale(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (maxAgeSeconds <= 0) return false
        val addedAt =
            when (payload) {
                is MediaPayload.PlexLibraryNew -> payload.addedAt
                else -> null
            } ?: return false

        val addedAtSeconds = if (addedAt > 100_000_000_000L) addedAt / 1000L else addedAt
        val nowSeconds = now / 1000L
        val ageSeconds = nowSeconds - addedAtSeconds
        if (ageSeconds > maxAgeSeconds) {
            val title =
                when (payload) {
                    is MediaPayload.PlexLibraryNew -> payload.title
                    else -> "Media"
                }
            logger.info(
                "Dropping stale media available event for '{}': addedAt ({}) is {} hours old (max age: {} hours)",
                title,
                addedAt,
                ageSeconds / 3600,
                maxAgeSeconds / 3600
            )
            return true
        }
        return false
    }

    suspend fun isDuplicate(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isStale(payload, now)) return true
        val keys = computeKeys(payload)
        if (keys.isEmpty()) return false
        val store = stateStore ?: return keys.any { fallback.isFresh(it, now) }
        return keys.any { store.exists(it, nowMillis = now) }
    }

    suspend fun tryAcquire(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isStale(payload, now)) return false
        val keys = computeKeys(payload)
        if (keys.isEmpty()) return true
        val store = stateStore ?: return fallback.tryRecord(keys, now)
        return tryAcquireInStore(store, keys, now)
    }

    /** Claims the first key atomically and records the others, unless any key was already seen. */
    private suspend fun tryAcquireInStore(
        store: StateStorePort,
        keys: List<String>,
        now: Long
    ): Boolean {
        if (keys.any { store.exists(it, nowMillis = now) }) return false
        val ttlSeconds = (ttlMillis / 1000L).coerceAtLeast(1L)
        val acquired = store.tryAcquire(keys.first(), ttlSeconds = ttlSeconds, value = now.toString(), nowMillis = now)
        if (acquired) {
            keys.drop(1).forEach { store.set(it, value = now.toString(), ttlSeconds = ttlSeconds, nowMillis = now) }
        }
        return acquired
    }

    suspend fun release(payload: MediaPayload) {
        val keys = computeKeys(payload)
        val store = stateStore
        for (key in keys) {
            store?.delete(key)
            fallback.remove(key)
        }
    }

    suspend fun clear() {
        stateStore?.clear()
        fallback.clear()
    }

    fun size(): Int = fallback.size
}
