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
