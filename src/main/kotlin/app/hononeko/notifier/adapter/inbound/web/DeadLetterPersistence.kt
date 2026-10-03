package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Shared JSON codec for dead letters, used for persistence snapshots and the DLQ API payload views. */
internal object DeadLetterCodec {
    val json =
        Json {
            // ServarrHealth carries its own `type` property, so the default discriminator name would collide.
            classDiscriminator = "payloadType"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

@Serializable
internal data class DeadLetterSnapshot(
    val version: Int = 1,
    val entries: List<DeadLetterEntry> = emptyList()
)

/**
 * Mirrors a [DeadLetterRingBuffer] into a [StateStorePort] (Valkey) so dead letters survive restarts.
 *
 * The whole buffer is written as one snapshot key: dead letters are rare and the buffer is bounded, and a
 * single conflated writer keeps the persisted copy ordered without per-entry bookkeeping.
 */
class DeadLetterPersistence(
    private val buffer: DeadLetterRingBuffer,
    private val stateStore: StateStorePort,
    private val key: String = SNAPSHOT_KEY
) {
    companion object {
        const val SNAPSHOT_KEY = "dlq:snapshot"
    }

    private val logger = LoggerFactory.getLogger(DeadLetterPersistence::class.java)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()

    /** Restores the persisted snapshot, then writes a fresh snapshot after every buffer mutation. */
    fun start(scope: CoroutineScope): Job {
        buffer.changeListener = { dirty.trySend(Unit) }
        return scope.launch {
            restore()
            dirty.consumeEach { flush() }
        }
    }

    suspend fun restore() {
        val raw = stateStore.get(key) ?: return
        val snapshot =
            try {
                DeadLetterCodec.json.decodeFromString(DeadLetterSnapshot.serializer(), raw)
            } catch (e: SerializationException) {
                logger.warn("Ignoring unreadable dead letter snapshot '{}': {}", key, e.message)
                return
            }
        buffer.restore(snapshot.entries)
        logger.info("Restored {} dead letter entries from state store", snapshot.entries.size)
    }

    suspend fun flush() {
        writeMutex.withLock {
            val snapshot = DeadLetterSnapshot(entries = buffer.getEntries())
            try {
                stateStore.set(key, DeadLetterCodec.json.encodeToString(DeadLetterSnapshot.serializer(), snapshot))
            } catch (e: SerializationException) {
                logger.error("Failed to serialize dead letter snapshot: {}", e.message, e)
            }
        }
    }
}
