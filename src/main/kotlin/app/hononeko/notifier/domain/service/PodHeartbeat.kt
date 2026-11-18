package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Publishes a short-lived liveness key for this replica in [StateStorePort] so peer replicas can detect a crashed pod
 * within seconds instead of waiting for long tracking/monitor leases to expire.
 */
class PodHeartbeat(
    private val stateStore: StateStorePort,
    val ownerId: String,
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val KEY_PREFIX = "pod:alive:"
        const val DEFAULT_TTL_SECONDS = 9L
        const val DEFAULT_INTERVAL_SECONDS = 3L

        fun keyFor(ownerId: String): String = "$KEY_PREFIX$ownerId"
    }

    private val logger = LoggerFactory.getLogger(PodHeartbeat::class.java)

    @Volatile
    private var job: Job? = null

    suspend fun beat() {
        val now = clock()
        stateStore.set(keyFor(ownerId), now.toString(), ttlSeconds, now)
    }

    /**
     * Returns `true` if [targetOwnerId] is this replica, has an active heartbeat, or if the state store itself is
     * unreachable (failing safe rather than falsely declaring a live peer dead during a Valkey blip).
     */
    suspend fun isAlive(targetOwnerId: String): Boolean {
        if (targetOwnerId == ownerId) return true
        if (stateStore.exists(keyFor(targetOwnerId), clock())) return true
        return !stateStore.healthCheck()
    }

    fun start(scope: CoroutineScope): Job {
        val started =
            scope.launch {
                while (isActive) {
                    beatSafely()
                    delay(intervalSeconds * 1000L)
                }
            }
        job = started
        return started
    }

    /** Stops the heartbeat loop and deletes this replica's key so peers can take over immediately. */
    suspend fun stop() {
        job?.cancelAndJoin()
        withContext(NonCancellable) {
            stateStore.delete(keyFor(ownerId))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun beatSafely() {
        try {
            beat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Pod heartbeat update failed for {}: {}", ownerId, e.message)
        }
    }
}
