package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.port.outbound.ClusterStatePort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared debounce buffer backed by [StateStorePort] + [ClusterStatePort], so rapid season-pack episodes landing on
 * different replicas merge into one payload and flush once when the sliding window expires.
 */
@Suppress("LongParameterList")
internal class DistributedDebounceBuffers<P>(
    private val keyPrefix: String,
    private val debounceMillis: Long,
    private val scope: CoroutineScope,
    private val stateStore: StateStorePort,
    private val clusterStore: ClusterStatePort,
    private val payloadSerializer: KSerializer<P>,
    private val onFlush: (suspend (P) -> Unit)?,
    private val matchesId: (P, String) -> Boolean = { _, _ -> false },
    private val clock: () -> Long = System::currentTimeMillis
) : DebounceBackend<P> {
    @Serializable
    private data class Entry<P>(
        val payload: P,
        val flushAtMillis: Long
    )

    companion object {
        private const val MAX_CAS_ATTEMPTS = 16
        private const val MIN_STORE_TTL_SECONDS = 60L
        private const val TTL_WINDOW_MULTIPLIER = 12L
        private const val CAS_BACKOFF_MILLIS = 10L
    }

    private val logger = LoggerFactory.getLogger(DistributedDebounceBuffers::class.java)
    private val entrySerializer = Entry.serializer(payloadSerializer)
    private val storeTtlSeconds = maxOf(MIN_STORE_TTL_SECONDS, (debounceMillis / 1000L) * TTL_WINDOW_MULTIPLIER)
    private val json =
        Json {
            classDiscriminator = "payloadType"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val timers = ConcurrentHashMap<String, Job>()
    private val timerMutex = Mutex()

    override val size: Int get() = timers.size

    override suspend fun distributedSize(): Int = clusterStore.listKeys(keyPrefix, clock()).size

    override suspend fun submit(
        key: String,
        initial: P,
        merge: (P) -> P
    ) {
        val normalizedKey = key.trim().lowercase()
        val storeKey = "$keyPrefix$normalizedKey"
        repeat(MAX_CAS_ATTEMPTS) { attempt ->
            if (trySubmitOnce(storeKey, initial, merge)) {
                scheduleTimer(normalizedKey, debounceMillis)
                return
            }
            if (attempt < MAX_CAS_ATTEMPTS - 1) delay(CAS_BACKOFF_MILLIS)
        }
        logger.warn("Exhausted CAS attempts buffering key '{}'; invoking directly", normalizedKey)
        onFlush?.invoke(initial)
    }

    override suspend fun flush(key: String) {
        val normalized = key.trim().lowercase()
        val directKey = "$keyPrefix$normalized"
        val directRaw = stateStore.get(directKey, clock())
        if (directRaw != null) {
            val entry = decode(directRaw)
            if (entry != null) {
                popAndFlush(directKey, normalized, directRaw, entry.payload)
                return
            }
        }
        val matched = findById(normalized) ?: return
        popAndFlush(matched.first, matched.first.removePrefix(keyPrefix), matched.second, matched.third.payload)
    }

    override suspend fun flushAll() {
        val localKeys =
            timerMutex.withLock {
                timers.keys.toList().also { keys ->
                    keys.forEach { timers.remove(it)?.cancel() }
                }
            }
        val allStoreKeys = (localKeys.map { "$keyPrefix$it" } + clusterStore.listKeys(keyPrefix, clock())).distinct()
        for (storeKey in allStoreKeys) {
            val raw = stateStore.get(storeKey, clock()) ?: continue
            val entry = decode(raw) ?: continue
            popAndFlush(storeKey, storeKey.removePrefix(keyPrefix), raw, entry.payload)
        }
    }

    override suspend fun sweepExpired() {
        val now = clock()
        for (storeKey in clusterStore.listKeys(keyPrefix, now)) {
            val raw = stateStore.get(storeKey, now) ?: continue
            val entry = decode(raw)
            if (entry == null) {
                clusterStore.deleteIfEquals(storeKey, raw)
                continue
            }
            if (entry.flushAtMillis <= now) {
                popAndFlush(storeKey, storeKey.removePrefix(keyPrefix), raw, entry.payload)
            }
        }
    }

    private suspend fun trySubmitOnce(
        storeKey: String,
        initial: P,
        merge: (P) -> P
    ): Boolean {
        val now = clock()
        val flushAt = now + debounceMillis
        val existingRaw = stateStore.get(storeKey, now)
        if (existingRaw == null) {
            val encoded = encode(Entry(initial, flushAt))
            return stateStore.tryAcquire(storeKey, storeTtlSeconds, encoded, now)
        }
        val existing = decode(existingRaw)
        if (existing == null) {
            clusterStore.deleteIfEquals(storeKey, existingRaw)
            return false
        }
        val merged = merge(existing.payload)
        val encoded = encode(Entry(merged, flushAt))
        return clusterStore.compareAndSet(storeKey, existingRaw, encoded, storeTtlSeconds, now)
    }

    private suspend fun tryFlush(normalizedKey: String) {
        val storeKey = "$keyPrefix$normalizedKey"
        val now = clock()
        val raw = stateStore.get(storeKey, now)
        if (raw == null) {
            removeTimer(normalizedKey)
            return
        }
        val entry = decode(raw)
        if (entry == null) {
            clusterStore.deleteIfEquals(storeKey, raw)
            removeTimer(normalizedKey)
            return
        }
        val remaining = entry.flushAtMillis - now
        if (remaining > 0) {
            scheduleTimer(normalizedKey, remaining)
            return
        }
        popAndFlush(storeKey, normalizedKey, raw, entry.payload)
    }

    private suspend fun popAndFlush(
        storeKey: String,
        normalizedKey: String,
        raw: String,
        payload: P
    ) {
        if (!clusterStore.deleteIfEquals(storeKey, raw)) return
        removeTimer(normalizedKey)
        onFlush?.invoke(payload)
    }

    private suspend fun findById(normalized: String): Triple<String, String, Entry<P>>? {
        val now = clock()
        for (storeKey in clusterStore.listKeys(keyPrefix, now)) {
            val raw = stateStore.get(storeKey, now) ?: continue
            val entry = decode(raw) ?: continue
            if (matchesId(entry.payload, normalized)) {
                return Triple(storeKey, raw, entry)
            }
        }
        return null
    }

    private suspend fun scheduleTimer(
        normalizedKey: String,
        delayMillis: Long
    ) {
        timerMutex.withLock {
            timers.remove(normalizedKey)?.cancel()
            timers[normalizedKey] =
                scope.launch {
                    delay(delayMillis.coerceAtLeast(0L))
                    tryFlush(normalizedKey)
                }
        }
    }

    private suspend fun removeTimer(normalizedKey: String) {
        val currentJob = currentCoroutineContext()[Job]
        timerMutex.withLock {
            val existing = timers.remove(normalizedKey)
            if (existing != null && existing != currentJob) {
                existing.cancel()
            }
        }
    }

    private fun encode(entry: Entry<P>): String = json.encodeToString(entrySerializer, entry)

    private fun decode(raw: String): Entry<P>? =
        runCatching { json.decodeFromString(entrySerializer, raw) }
            .onFailure { logger.warn("Ignoring unreadable debounce buffer entry: {}", it.message) }
            .getOrNull()
}
