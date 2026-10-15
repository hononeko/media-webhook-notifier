package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import org.slf4j.LoggerFactory

private const val MIN_LEASE_TTL_SECONDS = 30L
private const val LEASE_TTL_POLL_INTERVALS = 3L

/** Long enough to survive a couple of slow ticks, and never shorter than three poll intervals. */
fun trackingLeaseTtlSeconds(pollIntervalSeconds: Long): Long =
    maxOf(MIN_LEASE_TTL_SECONDS, LEASE_TTL_POLL_INTERVALS * pollIntervalSeconds)

/**
 * Cross-instance ownership of tracking loops: one lock per download hash, so only one instance edits a live card.
 * A `hash1|hash2` download holds a lock for each hash, which keeps a later sweep that sees only `hash2` out too.
 */
class TrackingLeases(
    private val stateStore: StateStorePort,
    val ownerId: String,
    val ttlSeconds: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun keysFor(downloadId: String): List<String> =
        downloadId
            .split('|')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .map { "$KEY_PREFIX$it" }

    /** Takes every hash's lock or none of them. */
    suspend fun acquire(downloadId: String): Boolean {
        val acquired = mutableListOf<String>()
        for (key in keysFor(downloadId)) {
            if (!stateStore.acquireLock(key, ownerId, ttlSeconds, clock())) {
                acquired.forEach { stateStore.releaseLock(it, ownerId) }
                return false
            }
            acquired += key
        }
        return true
    }

    /** [LockRenewal.LOST] if any hash now belongs to someone else; otherwise unavailable if any couldn't be checked. */
    suspend fun renew(downloadId: String): LockRenewal {
        val results = keysFor(downloadId).map { stateStore.renewLock(it, ownerId, ttlSeconds, clock()) }
        return when {
            LockRenewal.LOST in results -> LockRenewal.LOST
            LockRenewal.STORE_UNAVAILABLE in results -> LockRenewal.STORE_UNAVAILABLE
            else -> LockRenewal.RENEWED
        }
    }

    suspend fun release(downloadId: String) {
        keysFor(downloadId).forEach { stateStore.releaseLock(it, ownerId) }
    }

    private companion object {
        const val KEY_PREFIX = "lock:track:"
    }
}

// Logged under the tracker's name, where these checks used to live.
private val trackerLogger = LoggerFactory.getLogger(DownloadTrackerEngine::class.java)

/** Takes [hash]'s leases before tracking starts; always true without leases (a single instance). */
internal suspend fun TrackingLeases?.acquireOrSkip(hash: String): Boolean {
    if (this == null || acquire(hash)) return true
    trackerLogger.info("Download {} is tracked by another instance; not starting a second tracker", hash)
    return false
}

/** Renews [hash]'s leases each tick; false only when another instance owns the download now. */
internal suspend fun TrackingLeases?.stillOwns(hash: String): Boolean =
    when (this?.renew(hash)) {
        LockRenewal.LOST -> {
            trackerLogger.warn("Another instance owns {} now; stopping without touching its card", hash)
            false
        }
        LockRenewal.STORE_UNAVAILABLE -> {
            trackerLogger.debug("Couldn't renew the tracking lease for {}; state store unavailable, carrying on", hash)
            true
        }
        LockRenewal.RENEWED, null -> true
    }
