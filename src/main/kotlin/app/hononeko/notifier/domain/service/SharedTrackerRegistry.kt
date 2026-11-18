package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TrackerSnapshot
import app.hononeko.notifier.domain.model.TrackerStatus
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.ClusterStatePort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class SharedTrackerRecord(
    val hash: String,
    val ownerId: String,
    val payload: MediaPayload.ArrGrab,
    val handle: NotificationHandle,
    val isPhoto: Boolean,
    val startedAtMillis: Long,
    val lastProgressPercent: Double = 0.0,
    val downloadSpeedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val status: TrackerStatus = TrackerStatus.TRACKING
) {
    fun toSnapshot(): TrackerSnapshot =
        TrackerSnapshot(
            hash = hash,
            title = payload.seriesOrMovieTitle.ifBlank { payload.title },
            source = payload.source,
            handle = handle,
            isPhoto = isPhoto,
            startedAtMillis = startedAtMillis,
            lastProgressPercent = lastProgressPercent,
            downloadSpeedBytesPerSec = downloadSpeedBytesPerSec,
            etaSeconds = etaSeconds,
            status = status
        )
}

/**
 * Shares active download tracking sessions in [StateStorePort] so:
 * 1. `/metrics` can report cluster-wide active trackers across all replicas.
 * 2. Standby replicas can detect an orphaned download within seconds of a pod crash and resume it via
 *    [TrackDownloadUseCase.trackExisting] with the original payload and [NotificationHandle].
 */
class SharedTrackerRegistry(
    private val stateStore: StateStorePort,
    private val localStore: ActiveTrackerStore,
    val ownerId: String,
    private val isOwnerAlive: suspend (String) -> Boolean = { true },
    maxPollingMinutes: Long = 30L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val KEY_PREFIX = "tracker:active:"
        const val DEFAULT_WATCH_INTERVAL_SECONDS = 3L
        private const val MIN_TTL_SECONDS = 300L
        private const val TTL_BUFFER_SECONDS = 120L
    }

    private val logger = LoggerFactory.getLogger(SharedTrackerRegistry::class.java)
    private val cluster = stateStore as? ClusterStatePort
    private val ttlSeconds = maxOf(MIN_TTL_SECONDS, maxPollingMinutes * 60L + TTL_BUFFER_SECONDS)
    private val json =
        Json {
            classDiscriminator = "payloadType"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun recordStarted(session: ActiveTrackerSession) {
        val now = clock()
        val key = keyFor(session.hash)
        val existing = loadRecord(key, now)?.second
        val record =
            SharedTrackerRecord(
                hash = session.hash,
                ownerId = ownerId,
                payload = session.payload,
                handle = session.handle,
                isPhoto = session.isPhoto,
                startedAtMillis = existing?.startedAtMillis ?: session.startedAtMillis,
                lastProgressPercent = existing?.lastProgressPercent ?: 0.0,
                downloadSpeedBytesPerSec = existing?.downloadSpeedBytesPerSec ?: 0L,
                etaSeconds = existing?.etaSeconds ?: 0L,
                status = session.status
            )
        stateStore.set(key, json.encodeToString(SharedTrackerRecord.serializer(), record), ttlSeconds, now)
    }

    suspend fun recordProgress(
        hash: String,
        progress: DownloadItemProgress,
        stalledSeconds: Long
    ) {
        val now = clock()
        val key = keyFor(hash)
        val (raw, existing) = loadRecord(key, now) ?: return
        if (existing.ownerId != ownerId) return
        val updated =
            existing.copy(
                lastProgressPercent = progress.progressPercent,
                downloadSpeedBytesPerSec = progress.downloadSpeedBytesPerSec,
                etaSeconds = progress.etaSeconds,
                status = if (stalledSeconds > 0) TrackerStatus.STALLED else TrackerStatus.TRACKING
            )
        val encoded = json.encodeToString(SharedTrackerRecord.serializer(), updated)
        if (cluster != null) {
            cluster.compareAndSet(key, raw, encoded, ttlSeconds, now)
        } else {
            stateStore.set(key, encoded, ttlSeconds, now)
        }
    }

    suspend fun recordFinished(hash: String) {
        val now = clock()
        val key = keyFor(hash)
        val (raw, existing) = loadRecord(key, now) ?: return
        if (existing.ownerId != ownerId) return
        if (cluster != null) {
            cluster.deleteIfEquals(key, raw)
        } else {
            stateStore.delete(key)
        }
    }

    suspend fun allSnapshots(): List<TrackerSnapshot> {
        val local = localStore.getAllSnapshots()
        val keys = cluster?.listKeys(KEY_PREFIX, clock()).orEmpty()
        if (keys.isEmpty()) return local
        val now = clock()
        val localHashes = local.mapTo(HashSet()) { it.hash }
        val remote =
            keys
                .mapNotNull { key -> loadRecord(key, now)?.second?.toSnapshot() }
                .filter { it.hash !in localHashes }
        return (local + remote).distinctBy { it.hash }
    }

    suspend fun sweepOrphanedTrackers(trackDownloadUseCase: TrackDownloadUseCase): Int {
        val keys = cluster?.listKeys(KEY_PREFIX, clock()).orEmpty()
        if (keys.isEmpty()) return 0
        val now = clock()
        val orphaned =
            keys
                .mapNotNull { key -> loadRecord(key, now)?.second }
                .filter { isOrphaned(it, now) }
        var resumed = 0
        for (record in orphaned) {
            val result =
                trackDownloadUseCase.trackExisting(
                    record.hash,
                    record.payload,
                    record.handle,
                    record.isPhoto
                )
            if (result.getOrNull() == TrackingStart.STARTED) {
                resumed++
            }
        }
        return resumed
    }

    @Suppress("TooGenericExceptionCaught")
    fun startWatcher(
        scope: CoroutineScope,
        trackDownloadUseCase: TrackDownloadUseCase,
        intervalSeconds: Long = DEFAULT_WATCH_INTERVAL_SECONDS
    ): Job =
        scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                try {
                    val resumed = sweepOrphanedTrackers(trackDownloadUseCase)
                    if (resumed > 0) {
                        logger.info("Fast tracker takeover resumed {} orphaned download(s)", resumed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Orphaned tracker watcher sweep failed: {}", e.message)
                }
            }
        }

    private suspend fun isOrphaned(
        record: SharedTrackerRecord,
        now: Long
    ): Boolean {
        if (record.ownerId == ownerId || localStore.isTracking(record.hash)) return false
        val firstHash =
            record.hash
                .split('|')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.lowercase()
        val lockOwner = firstHash?.let { stateStore.get(TrackingLeases.KEY_PREFIX + it, now) }
        return lockOwner == null || (lockOwner != ownerId && !isOwnerAlive(lockOwner))
    }

    private suspend fun loadRecord(
        key: String,
        now: Long
    ): Pair<String, SharedTrackerRecord>? {
        val raw = stateStore.get(key, now) ?: return null
        val parsed = runCatching { json.decodeFromString(SharedTrackerRecord.serializer(), raw) }.getOrNull()
        return parsed?.let { raw to it }
    }

    private fun keyFor(hash: String): String = "$KEY_PREFIX${hash.trim().lowercase()}"
}
