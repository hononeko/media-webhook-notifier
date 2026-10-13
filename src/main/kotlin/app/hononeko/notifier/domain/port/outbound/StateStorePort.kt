package app.hononeko.notifier.domain.port.outbound

interface StateStorePort : AutoCloseable {
    /**
     * Attempts to atomically acquire a key with a TTL in seconds.
     * Returns true if key was acquired (did not previously exist), false otherwise.
     */
    suspend fun tryAcquire(
        key: String,
        ttlSeconds: Long,
        value: String = "1",
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    /**
     * Checks if a key exists in the store.
     */
    suspend fun exists(
        key: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    /**
     * Gets value for a key.
     */
    suspend fun get(
        key: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String?

    /**
     * Sets value for a key with an optional TTL in seconds.
     */
    suspend fun set(
        key: String,
        value: String,
        ttlSeconds: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    )

    /**
     * Deletes a key from the store.
     */
    suspend fun delete(key: String): Boolean

    /**
     * Takes [key] for [owner] for [ttlSeconds] if nobody holds it. Returns false when another owner holds it, or when
     * [owner] already does.
     */
    suspend fun acquireLock(
        key: String,
        owner: String,
        ttlSeconds: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    /**
     * Extends [owner]'s hold on [key] by [ttlSeconds]. A key nobody holds any more is taken again, so a lock that
     * lapsed during a store outage isn't lost.
     */
    suspend fun renewLock(
        key: String,
        owner: String,
        ttlSeconds: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): LockRenewal

    /** Frees [key] only if [owner] holds it. Returns true when it was freed. */
    suspend fun releaseLock(
        key: String,
        owner: String
    ): Boolean

    /**
     * Checks store connectivity and health.
     */
    suspend fun healthCheck(): Boolean

    /**
     * Clears all entries managed by this store.
     */
    suspend fun clear()

    /**
     * Cleanly closes underlying resources/connections.
     */
    override fun close() = Unit
}
