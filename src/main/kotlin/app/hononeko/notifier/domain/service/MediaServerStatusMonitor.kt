package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.ClusterStatePort
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.MediaServerHealthPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.ZoneId

/** Settings of the media server status monitor; durations in seconds. */
data class MediaServerStatusConfig(
    val serverName: String,
    val sourceName: String,
    /** This instance's name on the polling lock. */
    val ownerId: String,
    val pollIntervalSeconds: Long = 30,
    /** Failed checks only turn the status offline once they've lasted this long, so restarts go unnoticed. */
    val graceSeconds: Long = 90,
    /** How often an unchanged card is refreshed (last check time, the running downtime). */
    val refreshSeconds: Long = 300,
    /**
     * How long after recovery the `"Back up at ... (downtime: ...)"` line is kept before editing back to steady state.
     */
    val recoverySeconds: Long = 3600,
    val pinEnabled: Boolean = true,
    /** The time zone the card's times are shown in. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** How often a standby replica checks the lock for a crashed leader when heartbeats are active. */
    val standbyCheckSeconds: Long = 5L,
    /** Optional liveness predicate for fast takeover when another replica dies without releasing its lock. */
    val isOwnerAlive: (suspend (String) -> Boolean)? = null
)

enum class ServerStatus { UP, DOWN }

/**
 * What the monitor remembers between checks, kept in the state store so a restarted (or another) instance keeps
 * editing the same pinned message. Times are epoch millis.
 */
@Serializable
data class MediaServerStatusState(
    /** Null until the first verdict: online at once, offline only after the grace period. */
    val status: ServerStatus? = null,
    val handle: NotificationHandle? = null,
    /** When [status] began: the recovery, or the first failed check of the outage. */
    val since: Long? = null,
    /** The last outage's length, shown on the online card. */
    val lastDowntimeMillis: Long? = null,
    /** The first of the current run of failed checks; null while checks pass. */
    val firstFailedAt: Long? = null,
    val lastError: String? = null,
    val checkedAt: Long? = null,
    /** The status the message shows, and when it was last rendered. */
    val renderedStatus: ServerStatus? = null,
    val renderedAt: Long? = null,
    /** Whether the message is pinned; a message sent while pinning was off is pinned once it's turned on. */
    val pinned: Boolean = false,
    /** Whether the currently rendered message still includes the `"Back up at ... (downtime: ...)"` banner. */
    val renderedRecovery: Boolean = false,
    /** Monotonic state version used for fenced compare-and-set saves across replicas. */
    val version: Long = 0L
) {
    fun needsRender(
        nextStatus: ServerStatus,
        now: Long,
        refreshMillis: Long
    ): Boolean {
        val due = renderedAt == null || now - renderedAt >= refreshMillis
        val recoveryExpired = renderedRecovery && lastDowntimeMillis == null
        return renderedStatus != nextStatus || due || recoveryExpired
    }

    fun isRecovery(nextStatus: ServerStatus): Boolean =
        nextStatus == ServerStatus.UP && (lastDowntimeMillis != null || renderedStatus == ServerStatus.DOWN)

    fun shouldReplaceForAlert(
        nextStatus: ServerStatus,
        card: NotificationCard
    ): Boolean = renderedStatus != null && renderedStatus != nextStatus && !card.silent
}

/**
 * Polls the media server and keeps one pinned status message up to date, edited in place: 🟢 online, or 🔴 offline
 * once checks have failed for the whole grace period. Recovery shows how long the outage lasted.
 *
 * Only one instance polls at a time (a lock in the state store), and the message and status live in the state store,
 * so rollouts and restarts keep editing the same message instead of pinning a new one.
 */
class MediaServerStatusMonitor(
    private val healthCheck: MediaServerHealthPort,
    private val publisher: NotificationPublisherPort,
    private val stateStore: StateStorePort,
    private val config: MediaServerStatusConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val engine: () -> TemplateEngine = { CardTemplates.templateEngine }
) {
    companion object {
        const val STATE_KEY = "media_server:status"
        const val LOCK_KEY = "lock:media_server:status"
    }

    private val isOwnerAlive get() = config.isOwnerAlive

    private val logger = LoggerFactory.getLogger(MediaServerStatusMonitor::class.java)
    private val states = StatusStates(stateStore, clock)
    private val lockTtlSeconds = trackingLeaseTtlSeconds(config.pollIntervalSeconds)
    private val graceMillis = config.graceSeconds * 1000

    // Checks are a poll interval apart plus their own latency; only a gap well past that means this instance was away.
    private val staleGapMillis =
        maxOf(graceMillis, config.pollIntervalSeconds * 1000) + config.pollIntervalSeconds * 1000
    private val refreshMillis = config.refreshSeconds * 1000
    private val recoveryMillis = config.recoverySeconds * 1000

    @Volatile private var job: Job? = null

    @Volatile private var pinWarned = false

    fun start(scope: CoroutineScope) {
        logger.info(
            "Media server status monitor started for {} (every {}s, grace {}s)",
            config.serverName,
            config.pollIntervalSeconds,
            config.graceSeconds
        )
        job =
            scope.launch {
                while (isActive) {
                    val activeOwner = tickSafely()
                    val delaySeconds =
                        if (!activeOwner && isOwnerAlive != null) {
                            minOf(config.pollIntervalSeconds, config.standbyCheckSeconds)
                        } else {
                            config.pollIntervalSeconds
                        }
                    delay(delaySeconds * 1000)
                }
            }
    }

    /** Stops polling and frees the lock, so another instance takes over at once. */
    suspend fun stop() {
        job?.cancelAndJoin()
        withContext(NonCancellable) { stateStore.releaseLock(LOCK_KEY, config.ownerId) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun tickSafely(): Boolean =
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Media server status check failed unexpectedly: {}", e.message, e)
            true
        }

    /** One check: polls the server, moves the status on, and renders the message when it needs it. */
    suspend fun tick(): Boolean {
        val initialLock = stateStore.renewOrTakeoverStatusLock(config.ownerId, lockTtlSeconds, clock(), isOwnerAlive)
        if (initialLock != LockRenewal.RENEWED) {
            val msg =
                if (initialLock == LockRenewal.LOST) {
                    "Another instance monitors the media server"
                } else {
                    "State store unavailable; skipping status check"
                }
            logger.debug(msg)
            return false
        }
        val health = healthCheck.checkHealth()
        // Re-check lock after the health check HTTP call so a paused pod cannot send a duplicate recovery card.
        val stillHeld = stateStore.renewLock(LOCK_KEY, config.ownerId, lockTtlSeconds, clock()) != LockRenewal.LOST
        if (stillHeld) {
            val now = clock()
            val loaded = states.loadWithRaw()
            val next = advance(loaded.state, health, now)
            states.save(render(next, now), loaded.raw)
        } else {
            logger.warn("Lost media server status lock during health check; skipping render")
        }
        return stillHeld
    }

    /** The state after a check at [now] with [health]. */
    internal fun advance(
        state: MediaServerStatusState,
        health: Either<DomainError.MediaServerError, Unit>,
        now: Long
    ): MediaServerStatusState {
        if (health is Either.Left) return failedCheck(state, health.value, now)
        val up =
            when (state.status) {
                ServerStatus.DOWN -> {
                    val downtime = now - (state.since ?: now)
                    logger.info(
                        "{} is back up after {}",
                        config.serverName,
                        MediaFormat.formatDuration(downtime / 1000)
                    )
                    state.copy(
                        status = ServerStatus.UP,
                        since = now,
                        lastDowntimeMillis = downtime.takeIf { config.recoverySeconds > 0 }
                    )
                }
                ServerStatus.UP -> {
                    val expired =
                        state.lastDowntimeMillis != null && now - (state.since ?: now) >= recoveryMillis
                    if (expired) state.copy(lastDowntimeMillis = null) else state
                }
                null -> state.copy(status = ServerStatus.UP, since = now)
            }
        return up.copy(firstFailedAt = null, lastError = null, checkedAt = now)
    }

    private fun failedCheck(
        state: MediaServerStatusState,
        error: DomainError.MediaServerError,
        now: Long
    ): MediaServerStatusState {
        // After a long gap (this instance was down), the failures before it say nothing about the current run.
        val continuing = state.checkedAt != null && now - state.checkedAt <= staleGapMillis
        val firstFailedAt = state.firstFailedAt?.takeIf { continuing } ?: now
        val failing = state.copy(firstFailedAt = firstFailedAt, lastError = error.describe(), checkedAt = now)
        return when {
            state.status == ServerStatus.DOWN -> failing
            now - firstFailedAt < graceMillis -> {
                logger.debug("{} check failed: {}", config.serverName, failing.lastError)
                failing
            }
            else -> {
                logger.warn("{} is down: {}", config.serverName, failing.lastError)
                failing.copy(status = ServerStatus.DOWN, since = firstFailedAt)
            }
        }
    }

    /** Edits, sends or leaves the message: changes render at once, an unchanged card every refresh interval. */
    private suspend fun render(
        state: MediaServerStatusState,
        now: Long
    ): MediaServerStatusState {
        val status = state.status ?: return state
        val templateEngine = engine()
        // A message in another chat or topic (the config changed) is left behind and replaced.
        val handle =
            MediaServerStatusCards.matchingHandle(state.handle, status == ServerStatus.UP, publisher, templateEngine)
        if (handle != null && !state.needsRender(status, now, refreshMillis)) return state

        val card = MediaServerStatusCards.build(viewOf(state, status, now), config.zone, templateEngine)
        val rendered =
            state.copy(
                renderedStatus = status,
                renderedAt = now,
                renderedRecovery = state.lastDowntimeMillis != null
            )
        val replaceForAlert = handle != null && state.shouldReplaceForAlert(status, card)
        val editFailure =
            if (handle != null && !replaceForAlert) {
                publisher.editCard(handle, card).leftOrNull()
            } else {
                null
            }
        return when {
            handle == null || replaceForAlert -> send(card, rendered, previous = state, replaceHandle = handle)
            editFailure == null -> if (state.pinned) rendered else rendered.copy(pinned = pin(handle))
            editFailure is DomainError.NotificationError.MessageNotFound -> {
                logger.info("The media server status message is gone; sending a new one")
                send(card, rendered, previous = state)
            }
            else -> {
                // Kept unrendered, so the next check tries again.
                logger.debug("Could not update the media server status message: {}", editFailure)
                state
            }
        }
    }

    /** Sends [card] as a new message, deletes [replaceHandle] if replaced for an audible alert, and pins it. */
    private suspend fun send(
        card: NotificationCard,
        rendered: MediaServerStatusState,
        previous: MediaServerStatusState,
        replaceHandle: NotificationHandle? = null
    ): MediaServerStatusState =
        when (val sent = publisher.sendCard(card)) {
            is Either.Right -> {
                if (replaceHandle != null) {
                    publisher.deleteMessage(replaceHandle).onLeft { err ->
                        logger.debug("Could not delete previous media server status message: {}", err)
                    }
                }
                rendered.copy(handle = sent.value, pinned = pin(sent.value))
            }
            is Either.Left -> {
                logger.warn("Could not send the media server status message: {}", sent.value)
                // Keep any existing handle when replacing for an alert fails; otherwise clear so next tick retries.
                if (replaceHandle != null) previous else rendered.copy(handle = null, renderedStatus = null)
            }
        }

    /** Pins the message when pinning is on; true once it's pinned. */
    private suspend fun pin(handle: NotificationHandle): Boolean {
        if (!config.pinEnabled) return false
        val pinned = publisher.pinMessage(handle, silent = true)
        if (pinned is Either.Left && !pinWarned) {
            pinWarned = true
            logger.warn(
                "Could not pin the media server status message ({}); give the bot the Pin Messages right, or set " +
                    "MEDIA_SERVER_STATUS_PIN_ENABLED=false. The message is still kept up to date.",
                pinned.value
            )
        }
        return pinned.isRight()
    }

    private fun viewOf(
        state: MediaServerStatusState,
        status: ServerStatus,
        now: Long
    ) = MediaServerStatusCards.StatusView(
        serverName = config.serverName,
        sourceName = config.sourceName,
        online = status == ServerStatus.UP,
        since = state.since,
        downtimeMillis =
            when (status) {
                ServerStatus.UP -> state.lastDowntimeMillis
                ServerStatus.DOWN -> now - (state.since ?: now)
            },
        checkedAt = now,
        error = state.lastError,
        recovery = state.isRecovery(status)
    )
}

private const val MAX_ERROR_LENGTH = 200

private fun DomainError.MediaServerError.describe(): String =
    when (this) {
        is DomainError.MediaServerError.Unhealthy -> "HTTP $httpStatus"
        is DomainError.MediaServerError.Unreachable -> reason.take(MAX_ERROR_LENGTH)
    }

private suspend fun StateStorePort.renewOrTakeoverStatusLock(
    ownerId: String,
    ttlSeconds: Long,
    now: Long,
    isOwnerAlive: (suspend (String) -> Boolean)?
): LockRenewal {
    val renewal = renewLock(MediaServerStatusMonitor.LOCK_KEY, ownerId, ttlSeconds, now)
    if (renewal != LockRenewal.LOST || isOwnerAlive == null) return renewal
    val cluster = this as? ClusterStatePort ?: return renewal
    val currentOwner = get(MediaServerStatusMonitor.LOCK_KEY, now)
    val dead = currentOwner != null && currentOwner != ownerId && !isOwnerAlive(currentOwner)
    val taken =
        dead &&
            cluster.takeoverLock(
                MediaServerStatusMonitor.LOCK_KEY,
                currentOwner,
                ownerId,
                ttlSeconds,
                now
            )
    return if (taken) LockRenewal.RENEWED else LockRenewal.LOST
}

private data class LoadedStatusState(
    val state: MediaServerStatusState,
    val raw: String?
)

/** The monitor's state in the state store, as JSON under [MediaServerStatusMonitor.STATE_KEY]. */
private class StatusStates(
    private val stateStore: StateStorePort,
    private val clock: () -> Long
) {
    private val logger = LoggerFactory.getLogger(MediaServerStatusMonitor::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cluster = stateStore as? ClusterStatePort

    suspend fun loadWithRaw(): LoadedStatusState {
        val raw =
            stateStore.get(MediaServerStatusMonitor.STATE_KEY, clock())
                ?: return LoadedStatusState(MediaServerStatusState(), null)
        val parsed =
            runCatching { json.decodeFromString(MediaServerStatusState.serializer(), raw) }
                .getOrElse {
                    logger.warn("Ignoring unreadable media server status state: {}", it.message)
                    MediaServerStatusState()
                }
        return LoadedStatusState(parsed, raw)
    }

    suspend fun save(
        state: MediaServerStatusState,
        previousRaw: String?
    ) {
        val versioned = state.copy(version = state.version + 1)
        val encoded = json.encodeToString(MediaServerStatusState.serializer(), versioned)
        val now = clock()
        if (previousRaw != null && cluster != null) {
            if (!cluster.compareAndSet(MediaServerStatusMonitor.STATE_KEY, previousRaw, encoded, null, now)) {
                logger.warn("Media server status state changed concurrently; skipping stale write")
            }
        } else {
            stateStore.set(MediaServerStatusMonitor.STATE_KEY, encoded, null, now)
        }
    }
}
