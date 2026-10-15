package app.hononeko.notifier.domain.service

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory "seen recently" set: keys are remembered for [ttlMillis]. Once it holds [maxCapacity] keys, expired entries
 * are pruned first, then the oldest. [MediaAvailableDeduplicator] uses it when there's no state store.
 */
internal class TtlCache(
    private val ttlMillis: Long,
    private val maxCapacity: Int
) {
    private val seenAt = ConcurrentHashMap<String, Long>()

    val size: Int get() = seenAt.size

    /** Whether [key] was recorded within the TTL; an expired entry is dropped on the way. */
    fun isFresh(
        key: String,
        now: Long
    ): Boolean {
        val timestamp = seenAt[key] ?: return false
        if (now - timestamp <= ttlMillis) return true
        seenAt.remove(key)
        return false
    }

    /** Records [keys] unless any of them was seen within the TTL; returns whether they were recorded. */
    fun tryRecord(
        keys: List<String>,
        now: Long
    ): Boolean {
        pruneIfNecessary(now)
        if (keys.any { key -> seenAt[key]?.let { now - it <= ttlMillis } == true }) return false
        keys.forEach { seenAt[it] = now }
        return true
    }

    fun remove(key: String) {
        seenAt.remove(key)
    }

    fun clear() = seenAt.clear()

    private fun pruneIfNecessary(now: Long) {
        if (seenAt.size >= maxCapacity) {
            val iterator = seenAt.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > ttlMillis) {
                    iterator.remove()
                }
            }
            if (seenAt.size >= maxCapacity) {
                val targetSize = (maxCapacity * 3 / 4).coerceAtLeast(1).coerceAtMost(maxCapacity - 1)
                val toRemoveCount = (seenAt.size - targetSize).coerceAtLeast(1)
                val keysToRemove =
                    seenAt.entries
                        .sortedBy { it.value }
                        .take(toRemoveCount)
                        .map { it.key }
                for (k in keysToRemove) {
                    seenAt.remove(k)
                }
            }
        }
    }
}
