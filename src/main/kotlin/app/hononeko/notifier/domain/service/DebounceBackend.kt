package app.hononeko.notifier.domain.service

internal interface DebounceBackend<P> {
    val size: Int

    suspend fun submit(
        key: String,
        initial: P,
        merge: (P) -> P
    )

    suspend fun flush(key: String)

    suspend fun flushAll()

    suspend fun sweepExpired() {}

    suspend fun distributedSize(): Int = size
}
