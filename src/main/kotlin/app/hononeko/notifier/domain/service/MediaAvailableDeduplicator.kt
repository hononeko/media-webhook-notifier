package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import java.util.concurrent.ConcurrentHashMap

class MediaAvailableDeduplicator(
    private val ttlMillis: Long = 86_400_000L, // 24 hours
    private val maxCapacity: Int = 10_000
) {
    private val cache = ConcurrentHashMap<String, Long>()

    fun computeKey(payload: MediaPayload): String? =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> {
                val instance = (payload.instanceName ?: "").trim().lowercase()
                val serverId = (payload.serverMachineIdentifier ?: "").trim().lowercase()
                if (!payload.ratingKey.isNullOrBlank()) {
                    "plex:$instance:$serverId:${payload.ratingKey.trim()}"
                } else {
                    val grandParent = (payload.grandParentTitle ?: "").trim().lowercase()
                    val parent = (payload.parentTitle ?: "").trim().lowercase()
                    val title = payload.title.trim().lowercase()
                    val season = payload.seasonNumber ?: 0
                    val ep = payload.episodeNumber ?: 0
                    val yr = payload.year ?: 0
                    "plex:$instance:$grandParent:$parent:$title:s$season:e$ep:$yr"
                }
            }
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

    fun isDuplicate(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val key = computeKey(payload) ?: return false
        val timestamp = cache[key] ?: return false
        if (now - timestamp > ttlMillis) {
            cache.remove(key)
            return false
        }
        return true
    }

    fun tryAcquire(
        payload: MediaPayload,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val key = computeKey(payload) ?: return true
        pruneIfNecessary(now)
        val existing = cache[key]
        if (existing != null && (now - existing) <= ttlMillis) {
            return false
        }
        cache[key] = now
        return true
    }

    fun release(payload: MediaPayload) {
        val key = computeKey(payload) ?: return
        cache.remove(key)
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
            // If still at or over capacity, remove oldest entries
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
