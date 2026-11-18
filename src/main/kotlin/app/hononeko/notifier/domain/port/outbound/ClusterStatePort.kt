package app.hononeko.notifier.domain.port.outbound

/**
 * Atomic compare-and-set, conditional lock takeover, and prefix key listing for multi-replica coordination.
 * Implemented alongside [StateStorePort] by stores that support cluster-safe updates.
 */
interface ClusterStatePort {
    /** Replaces [key] with [newValue] (and optional [ttlSeconds]) only if its current value equals [expectedValue]. */
    suspend fun compareAndSet(
        key: String,
        expectedValue: String,
        newValue: String,
        ttlSeconds: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    /** Deletes [key] only if its current value equals [expectedValue]. */
    suspend fun deleteIfEquals(
        key: String,
        expectedValue: String
    ): Boolean

    /**
     * Claims [key] for [newOwner] for [ttlSeconds] only if [key] is unowned/expired or currently held by
     * [expectedOwner]. Used to take over locks left behind by a crashed replica.
     */
    suspend fun takeoverLock(
        key: String,
        expectedOwner: String,
        newOwner: String,
        ttlSeconds: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    /** Returns non-expired keys starting with [prefix]. */
    suspend fun listKeys(
        prefix: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<String>
}
