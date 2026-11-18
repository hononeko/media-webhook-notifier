package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.StateStorePort

/**
 * Deduplicates Servarr grab and import webhook retries across replicas using `downloadId` + event type in
 * [StateStorePort] before debouncing or tracking starts. Complements [MediaAvailableDeduplicator] for media servers.
 */
class EventDeduplicator(
    private val stateStore: StateStorePort,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val KEY_PREFIX = "dedup:"
        const val DEFAULT_TTL_SECONDS = 3600L
    }

    fun computeKey(payload: MediaPayload): String? =
        when (payload) {
            is MediaPayload.ArrGrab -> grabKey(payload)
            is MediaPayload.ArrDownload -> importKey(payload)
            else -> null
        }

    /** Returns `true` if this delivery is unique (or has no dedup key) and `false` if it is a duplicate retry. */
    suspend fun tryAcquire(payload: MediaPayload): Boolean {
        val key = computeKey(payload) ?: return true
        val now = clock()
        return stateStore.tryAcquire(key, ttlSeconds, now.toString(), now)
    }

    suspend fun release(payload: MediaPayload) {
        val key = computeKey(payload) ?: return
        stateStore.delete(key)
    }

    private fun grabKey(payload: MediaPayload.ArrGrab): String? {
        val id = payload.downloadId.trim().lowercase()
        if (id.isBlank()) return null
        val source = payload.source.name.lowercase()
        val instance = (payload.instanceName ?: "").trim().lowercase()
        val episodes =
            payload.episodeNumbers
                .distinct()
                .sorted()
                .joinToString(",")
        return "${KEY_PREFIX}grab:$source:$instance:$id:s${payload.seasonNumber ?: 0}:$episodes"
    }

    private fun importKey(payload: MediaPayload.ArrDownload): String? {
        val id = payload.downloadId?.trim()?.lowercase()
        if (id.isNullOrBlank()) return null
        val source = payload.source.name.lowercase()
        val instance = (payload.instanceName ?: "").trim().lowercase()
        val episodes =
            payload.episodeNumbers
                .distinct()
                .sorted()
                .joinToString(",")
        return "${KEY_PREFIX}import:$source:$instance:$id:s${payload.seasonNumber ?: 0}:$episodes:${payload.isUpgrade}"
    }
}
