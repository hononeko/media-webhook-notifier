package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.ClusterStatePort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

private val sweeperLogger = LoggerFactory.getLogger(SeasonDebouncer::class.java)

/**
 * Switches [SeasonDebouncer] to shared [StateStorePort] buffers when [stateStore] supports [ClusterStatePort],
 * so rapid season-pack episodes landing on different replicas merge into a single card.
 */
fun SeasonDebouncer.withDistributedStore(
    stateStore: StateStorePort,
    clock: () -> Long = System::currentTimeMillis
): SeasonDebouncer {
    val cluster = stateStore as? ClusterStatePort ?: return this
    grabs =
        DistributedDebounceBuffers(
            keyPrefix = "debounce:grab:",
            debounceMillis = debounceMillis,
            scope = scope,
            stateStore = stateStore,
            clusterStore = cluster,
            payloadSerializer = MediaPayload.ArrGrab.serializer(),
            onFlush = onDebouncedGrab,
            matchesId = ::grabMatchesId,
            clock = clock
        )
    downloads =
        DistributedDebounceBuffers(
            keyPrefix = "debounce:import:",
            debounceMillis = debounceMillis,
            scope = scope,
            stateStore = stateStore,
            clusterStore = cluster,
            payloadSerializer = MediaPayload.ArrDownload.serializer(),
            onFlush = onDebouncedDownload,
            matchesId = ::downloadMatchesId,
            clock = clock
        )
    val availableCallback = onDebouncedAvailable
    available =
        DistributedDebounceBuffers(
            keyPrefix = "debounce:available:",
            debounceMillis = debounceMillis,
            scope = scope,
            stateStore = stateStore,
            clusterStore = cluster,
            payloadSerializer = MediaPayload.serializer(),
            onFlush =
                availableCallback?.let { cb ->
                    { payload ->
                        val enriched =
                            if (payload is MediaPayload.PlexLibraryNew) {
                                val cached = localArtworkCache.remove(debounceKey(payload))
                                if (payload.artworkBytes == null && cached != null) {
                                    payload.copy(artworkBytes = cached)
                                } else {
                                    payload
                                }
                            } else {
                                payload
                            }
                        cb(enriched)
                    }
                },
            clock = clock
        )
    return this
}

/** Sweeps expired distributed debounce buffers so entries from a crashed replica still flush promptly. */
fun SeasonDebouncer.startDistributedSweeper(
    sweepScope: CoroutineScope = scope,
    intervalMillis: Long = maxOf(MIN_DISTRIBUTED_SWEEP_INTERVAL_MILLIS, debounceMillis)
): Job =
    launchDebounceSweeper(sweepScope, intervalMillis, sweeperLogger) {
        grabs.sweepExpired()
        downloads.sweepExpired()
        available.sweepExpired()
    }

suspend fun SeasonDebouncer.distributedBufferCount(): Int =
    grabs.distributedSize() + downloads.distributedSize() + available.distributedSize()
