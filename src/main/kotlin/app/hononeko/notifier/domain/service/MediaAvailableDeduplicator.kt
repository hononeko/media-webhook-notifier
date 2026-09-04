package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class MediaAvailableDeduplicator(
    private val stateStore: StateStorePort? = null,
    private val ttlMillis: Long = 30L * 86_400_000L,
    private val maxCapacity: Int = 10_000,
    private val maxAgeSeconds: Long = 86_400L
) {
    private val logger = LoggerFactory.getLogger(MediaAvailableDeduplicator::class.java)
    private val fallbackCache = ConcurrentHashMap<String, Long>()

    fun computeKeys(payload: MediaPayload): List<String> =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> {
                val instance = (payload.instanceName ?: "").trim().lowercase()
                val serverId = (payload.serverMachineIdentifier ?: "").trim().lowercase()
                val keys = mutableListOf<String>()
                if (payload.ratingKeys.isNotEmpty()) {
                    for (rk in payload.ratingKeys) {
                        if (rk.isNotBlank()) {
                            keys.add("plex:$instance:$serverId:${rk.trim()}")
                        }
                    }
                } else if (!payload.ratingKey.isNullOrBlank()) {
                    keys.add("plex:$instance:$serverId:${payload.ratingKey.trim()}")
                } else {
                    val grandParent = (payload.grandParentTitle ?: "").trim().lowercase()
                    val parent = (payload.parentTitle ?: "").trim().lowercase()
                    val title = payload.title.trim().lowercase()
                    val season = payload.seasonNumber ?: 0
                    val ep = payload.episodeNumber ?: 0
                    val yr = payload.year ?: 0
                    keys.add("plex:$instance:$grandParent:$parent:$title:s$season:e$ep:$yr")
                }
                keys
            }
            is MediaPayload.JellyfinItemAdded -> {
                computeKey(payload)?.let { listOf(it) } ?: emptyList()
            }
            else -> emptyList()
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

        val store = stateStore
        if (store != null) {
            for (key in keys) {
                if (store.exists(key, nowMillis = now)) {
                    return true
                }
            }
            return false
        }

        for (key in keys) {
            val timestamp = fallbackCache[key] ?: continue
            if (now - timestamp > ttlMillis) {
                fallbackCache.remove(key)
            } else {
                return true
            }
        }
        return false
    }

    suspend fun tryAcquire(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isStale(payload, now)) {
            return false
        }
        val keys = computeKeys(payload)
        if (keys.isEmpty()) return true

        val store = stateStore
        if (store != null) {
            for (key in keys) {
                if (store.exists(key, nowMillis = now)) {
                    return false
                }
            }
            val ttlSeconds = (ttlMillis / 1000L).coerceAtLeast(1L)
            if (keys.size == 1) {
                return store.tryAcquire(keys.first(), ttlSeconds = ttlSeconds, value = now.toString(), nowMillis = now)
            }
            for (key in keys) {
                store.set(key, value = now.toString(), ttlSeconds = ttlSeconds, nowMillis = now)
            }
            return true
        }

        pruneIfNecessary(now)
        for (key in keys) {
            val existing = fallbackCache[key]
            if (existing != null && (now - existing) <= ttlMillis) {
                return false
            }
        }
        for (key in keys) {
            fallbackCache[key] = now
        }
        return true
    }

    suspend fun release(payload: MediaPayload) {
        val keys = computeKeys(payload)
        val store = stateStore
        for (key in keys) {
            store?.delete(key)
            fallbackCache.remove(key)
        }
    }

    suspend fun clear() {
        stateStore?.clear()
        fallbackCache.clear()
    }

    fun size(): Int = fallbackCache.size

    private fun pruneIfNecessary(now: Long) {
        if (fallbackCache.size >= maxCapacity) {
            val iterator = fallbackCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > ttlMillis) {
                    iterator.remove()
                }
            }
            if (fallbackCache.size >= maxCapacity) {
                val targetSize = (maxCapacity * 3 / 4).coerceAtLeast(1).coerceAtMost(maxCapacity - 1)
                val toRemoveCount = (fallbackCache.size - targetSize).coerceAtLeast(1)
                val keysToRemove =
                    fallbackCache.entries
                        .sortedBy { it.value }
                        .take(toRemoveCount)
                        .map { it.key }
                for (k in keysToRemove) {
                    fallbackCache.remove(k)
                }
            }
        }
    }
}
