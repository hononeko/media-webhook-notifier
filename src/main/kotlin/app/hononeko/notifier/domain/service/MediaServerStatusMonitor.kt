package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.MediaServerHealthPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import app.hononeko.notifier.domain.port.outbound.resolveDestination
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
    val pinEnabled: Boolean = true,
    /** The time zone the card's times are shown in. */
    val zone: ZoneId = ZoneId.systemDefault()
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
    val pinned: Boolean = false
)

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

    private val logger = LoggerFactory.getLogger(MediaServerStatusMonitor::class.java)
    private val states = StatusStates(stateStore, clock)
    private val lockTtlSeconds = trackingLeaseTtlSeconds(config.pollIntervalSeconds)
    private val graceMillis = config.graceSeconds * 1000

    // Checks are a poll interval apart plus their own latency; only a gap well past that means this instance was away.
    private val staleGapMillis =
        maxOf(graceMillis, config.pollIntervalSeconds * 1000) + config.pollIntervalSeconds * 1000
    private val refreshMillis = config.refreshSeconds * 1000

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
                    tickSafely()
                    delay(config.pollIntervalSeconds * 1000)
                }
            }
    }

    /** Stops polling and frees the lock, so another instance takes over at once. */
    suspend fun stop() {
        job?.cancelAndJoin()
        withContext(NonCancellable) { stateStore.releaseLock(LOCK_KEY, config.ownerId) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun tickSafely() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Media server status check failed unexpectedly: {}", e.message, e)
        }
    }

    /** One check: polls the server, moves the status on, and renders the message when it needs it. */
    suspend fun tick() {
        when (stateStore.renewLock(LOCK_KEY, config.ownerId, lockTtlSeconds, clock())) {
            LockRenewal.RENEWED -> Unit
            LockRenewal.LOST -> return logger.debug("Another instance monitors the media server")
            // Without the store, the message id is unknown and a new message would be pinned on every check.
            LockRenewal.STORE_UNAVAILABLE -> return logger.debug("State store unavailable; skipping status check")
        }
        val health = healthCheck.checkHealth()
        val now = clock()
        val next = advance(states.load(), health, now)
        states.save(render(next, now))
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
                    state.copy(status = ServerStatus.UP, since = now, lastDowntimeMillis = downtime)
                }
                ServerStatus.UP -> state
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
        val eventName =
            if (status == ServerStatus.UP) MediaServerStatusCards.ONLINE_EVENT else MediaServerStatusCards.OFFLINE_EVENT
        val template = engine().getEventTemplate(eventName)
        val target = publisher.resolveDestination(template?.chatId, template?.topicId)
        // A message in another chat or topic (the config changed) is left behind and replaced.
        val handle =
            state.handle?.takeIf {
                target.chatId.isBlank() || (it.channelOrChatId == target.chatId && it.topicId == target.topicId)
            }
        val due = state.renderedAt == null || now - state.renderedAt >= refreshMillis
        if (handle != null && state.renderedStatus == status && !due) return state

        val card = MediaServerStatusCards.build(viewOf(state, status, now), config.zone, engine())
        val rendered = state.copy(renderedStatus = status, renderedAt = now)
        val editFailure = handle?.let { publisher.editCard(it, card).leftOrNull() }
        return when {
            handle == null -> send(card, rendered)
            editFailure == null -> if (state.pinned) rendered else rendered.copy(pinned = pin(handle))
            editFailure is DomainError.NotificationError.MessageNotFound -> {
                logger.info("The media server status message is gone; sending a new one")
                send(card, rendered)
            }
            else -> {
                // Kept unrendered, so the next check tries again.
                logger.debug("Could not update the media server status message: {}", editFailure)
                state
            }
        }
    }

    /** Sends [card] as a new message and pins it; [rendered] is the state once that worked. */
    private suspend fun send(
        card: NotificationCard,
        rendered: MediaServerStatusState
    ): MediaServerStatusState =
        when (val sent = publisher.sendCard(card)) {
            is Either.Right -> rendered.copy(handle = sent.value, pinned = pin(sent.value))
            is Either.Left -> {
                logger.warn("Could not send the media server status message: {}", sent.value)
                // Nothing to edit: the next check sends again.
                rendered.copy(handle = null, renderedStatus = null)
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
        error = state.lastError
    )
}

private const val MAX_ERROR_LENGTH = 200

private fun DomainError.MediaServerError.describe(): String =
    when (this) {
        is DomainError.MediaServerError.Unhealthy -> "HTTP $httpStatus"
        is DomainError.MediaServerError.Unreachable -> reason.take(MAX_ERROR_LENGTH)
    }

/** The monitor's state in the state store, as JSON under [MediaServerStatusMonitor.STATE_KEY]. */
private class StatusStates(
    private val stateStore: StateStorePort,
    private val clock: () -> Long
) {
    private val logger = LoggerFactory.getLogger(MediaServerStatusMonitor::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): MediaServerStatusState {
        val raw = stateStore.get(MediaServerStatusMonitor.STATE_KEY, clock()) ?: return MediaServerStatusState()
        return runCatching { json.decodeFromString(MediaServerStatusState.serializer(), raw) }
            .getOrElse {
                logger.warn("Ignoring unreadable media server status state: {}", it.message)
                MediaServerStatusState()
            }
    }

    suspend fun save(state: MediaServerStatusState) {
        val encoded = json.encodeToString(MediaServerStatusState.serializer(), state)
        stateStore.set(MediaServerStatusMonitor.STATE_KEY, encoded, null, clock())
    }
}
