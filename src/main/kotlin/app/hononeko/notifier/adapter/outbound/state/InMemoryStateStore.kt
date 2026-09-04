package app.hononeko.notifier.adapter.outbound.state

import app.hononeko.notifier.domain.port.outbound.StateStorePort
import java.util.concurrent.ConcurrentHashMap

class InMemoryStateStore(
    private val maxCapacity: Int = 20_000
) : StateStorePort {
    private data class StoredItem(
        val value: String,
        val expiresAtMillis: Long?,
        var lastAccessedMillis: Long
    )

    private val store = ConcurrentHashMap<String, StoredItem>()

    override suspend fun tryAcquire(
        key: String,
        ttlSeconds: Long,
        value: String,
        nowMillis: Long
    ): Boolean {
        pruneIfNecessary(nowMillis)

        val existing = store[key]
        if (existing != null) {
            val expiresAt = existing.expiresAtMillis
            if (expiresAt != null && nowMillis >= expiresAt) {
                store.remove(key)
            } else {
                existing.lastAccessedMillis = nowMillis
                return false
            }
        }

        val expiresAtMillis = if (ttlSeconds > 0) nowMillis + (ttlSeconds * 1000L) else null
        store[key] = StoredItem(value = value, expiresAtMillis = expiresAtMillis, lastAccessedMillis = nowMillis)
        return true
    }

    override suspend fun exists(
        key: String,
        nowMillis: Long
    ): Boolean {
        val existing = store[key] ?: return false
        val expiresAt = existing.expiresAtMillis
        if (expiresAt != null && nowMillis >= expiresAt) {
            store.remove(key)
            return false
        }
        existing.lastAccessedMillis = nowMillis
        return true
    }

    override suspend fun get(
        key: String,
        nowMillis: Long
    ): String? {
        val existing = store[key] ?: return null
        val expiresAt = existing.expiresAtMillis
        if (expiresAt != null && nowMillis >= expiresAt) {
            store.remove(key)
            return null
        }
        existing.lastAccessedMillis = nowMillis
        return existing.value
    }

    override suspend fun set(
        key: String,
        value: String,
        ttlSeconds: Long?,
        nowMillis: Long
    ) {
        pruneIfNecessary(nowMillis)
        val expiresAtMillis = if (ttlSeconds != null && ttlSeconds > 0) nowMillis + (ttlSeconds * 1000L) else null
        store[key] = StoredItem(value = value, expiresAtMillis = expiresAtMillis, lastAccessedMillis = nowMillis)
    }

    override suspend fun delete(key: String): Boolean = store.remove(key) != null

    override suspend fun healthCheck(): Boolean = true

    override suspend fun clear() {
        store.clear()
    }

    fun size(nowMillis: Long = System.currentTimeMillis()): Int =
        store.values.count { it.expiresAtMillis == null || nowMillis < it.expiresAtMillis }

    private fun pruneIfNecessary(now: Long) {
        if (store.size >= maxCapacity) {
            val iterator = store.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val expiresAt = entry.value.expiresAtMillis
                if (expiresAt != null && now >= expiresAt) {
                    iterator.remove()
                }
            }

            if (store.size >= maxCapacity) {
                val targetSize = (maxCapacity * 3 / 4).coerceAtLeast(1).coerceAtMost(maxCapacity - 1)
                val toRemoveCount = (store.size - targetSize).coerceAtLeast(1)
                val keysToRemove =
                    store.entries
                        .sortedBy { it.value.lastAccessedMillis }
                        .take(toRemoveCount)
                        .map { it.key }
                for (k in keysToRemove) {
                    store.remove(k)
                }
            }
        }
    }
}
