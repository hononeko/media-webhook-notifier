package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class MediaAvailableDeduplicator(
    private val ttlMillis: Long = 30L * 86_400_000L,
    private val maxCapacity: Int = 10_000,
    private val maxAgeSeconds: Long = 86_400L
) {
    private val logger = LoggerFactory.getLogger(MediaAvailableDeduplicator::class.java)
    private val cache = ConcurrentHashMap<String, Long>()

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

    fun isDuplicate(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isStale(payload, now)) return true
        val keys = computeKeys(payload)
        if (keys.isEmpty()) return false
        for (key in keys) {
            val timestamp = cache[key] ?: continue
            if (now - timestamp > ttlMillis) {
                cache.remove(key)
            } else {
                return true
            }
        }
        return false
    }

    fun tryAcquire(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isStale(payload, now)) {
            return false
        }
        val keys = computeKeys(payload)
        if (keys.isEmpty()) return true
        pruneIfNecessary(now)
        for (key in keys) {
            val existing = cache[key]
            if (existing != null && (now - existing) <= ttlMillis) {
                return false
            }
        }
        for (key in keys) {
            cache[key] = now
        }
        return true
    }

    fun release(payload: MediaPayload) {
        val keys = computeKeys(payload)
        for (key in keys) {
            cache.remove(key)
        }
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size

    private fun pruneIfNecessary(now: Long) {
        if (cache.size >= maxCapacity) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > ttlMillis) {
                    iterator.remove()
                }
            }
            if (cache.size >= maxCapacity) {
                val targetSize = (maxCapacity * 3 / 4).coerceAtLeast(1).coerceAtMost(maxCapacity - 1)
                val toRemoveCount = (cache.size - targetSize).coerceAtLeast(1)
                val keysToRemove =
                    cache.entries
                        .sortedBy { it.value }
                        .take(toRemoveCount)
                        .map { it.key }
                for (k in keysToRemove) {
                    cache.remove(k)
                }
            }
        }
    }
}
