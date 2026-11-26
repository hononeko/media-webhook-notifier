package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.ClusterStatePort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val crossSeedSweeperLogger = LoggerFactory.getLogger(CrossSeedDebouncer::class.java)

/**
 * Buffers rapid cross-seed match webhooks for the same media release for [debounceMillis], merging trackers,
 * decisions, and info hashes into a single notification. Injection failures bypass buffering so they send at once.
 */
class CrossSeedDebouncer(
    internal val debounceMillis: Long = 30_000L,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    internal val onDebouncedResult: (suspend (MediaPayload.CrossSeedResult) -> Unit)? = null
) {
    internal var results: DebounceBackend<MediaPayload.CrossSeedResult> =
        DebounceBuffers(debounceMillis, scope, onDebouncedResult)

    val activeBufferCount: Int get() = results.size

    suspend fun submit(result: MediaPayload.CrossSeedResult) {
        if (result.eventType == EventType.CROSS_SEED_FAILED) {
            onDebouncedResult?.invoke(result)
            return
        }
        val normalized = normalizeCrossSeed(result)
        results.submit(debounceKey(normalized), normalized) { prev -> mergeCrossSeed(prev, normalized) }
    }

    suspend fun flush(key: String) {
        results.flush(key.trim().lowercase())
    }

    suspend fun flushAll() {
        results.flushAll()
    }
}

/** Switches [CrossSeedDebouncer] to shared [StateStorePort] buffers when [stateStore] supports [ClusterStatePort]. */
fun CrossSeedDebouncer.withDistributedStore(
    stateStore: StateStorePort,
    clock: () -> Long = System::currentTimeMillis
): CrossSeedDebouncer {
    val cluster = stateStore as? ClusterStatePort ?: return this
    results =
        DistributedDebounceBuffers(
            keyPrefix = "debounce:cross_seed:",
            debounceMillis = debounceMillis,
            scope = scope,
            stateStore = stateStore,
            clusterStore = cluster,
            payloadSerializer = MediaPayload.CrossSeedResult.serializer(),
            onFlush = onDebouncedResult,
            clock = clock
        )
    return this
}

/** Sweeps expired distributed cross-seed buffers so entries from a crashed replica still flush promptly. */
fun CrossSeedDebouncer.startDistributedSweeper(
    sweepScope: CoroutineScope = scope,
    intervalMillis: Long = maxOf(MIN_DISTRIBUTED_SWEEP_INTERVAL_MILLIS, debounceMillis)
): Job =
    launchDebounceSweeper(sweepScope, intervalMillis, crossSeedSweeperLogger) {
        results.sweepExpired()
    }

suspend fun CrossSeedDebouncer.distributedBufferCount(): Int = results.distributedSize()

private fun normalizeCrossSeed(result: MediaPayload.CrossSeedResult): MediaPayload.CrossSeedResult =
    result.copy(
        trackers = result.trackers.mapNotNull { it.trim().ifEmpty { null } }.distinct(),
        decisions = result.decisions.mapNotNull { it.trim().ifEmpty { null } }.distinct(),
        infoHashes = result.infoHashes.mapNotNull { it.trim().ifEmpty { null } }.distinct()
    )

private fun mergeCrossSeed(
    prev: MediaPayload.CrossSeedResult,
    incoming: MediaPayload.CrossSeedResult
): MediaPayload.CrossSeedResult =
    prev.copy(
        trackers = (prev.trackers + incoming.trackers).distinct(),
        decisions = (prev.decisions + incoming.decisions).distinct(),
        infoHashes = (prev.infoHashes + incoming.infoHashes).distinct(),
        paused = prev.paused && incoming.paused,
        sizeBytes = prev.sizeBytes?.takeIf { it > 0 } ?: incoming.sizeBytes?.takeIf { it > 0 },
        category = prev.category?.takeIf { it.isNotBlank() } ?: incoming.category?.takeIf { it.isNotBlank() },
        trigger = prev.trigger?.takeIf { it.isNotBlank() } ?: incoming.trigger?.takeIf { it.isNotBlank() },
        searcheeSource =
            prev.searcheeSource?.takeIf { it.isNotBlank() } ?: incoming.searcheeSource?.takeIf { it.isNotBlank() },
        clientHost = prev.clientHost?.takeIf { it.isNotBlank() } ?: incoming.clientHost?.takeIf { it.isNotBlank() },
        instanceName =
            prev.instanceName?.takeIf { it.isNotBlank() } ?: incoming.instanceName?.takeIf { it.isNotBlank() }
    )
