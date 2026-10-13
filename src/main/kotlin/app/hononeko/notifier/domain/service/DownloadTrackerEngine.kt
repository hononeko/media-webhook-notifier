package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TrackerSnapshot
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.domain.port.outbound.LockRenewal
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DownloadTrackerConfig(
    val pollIntervalSeconds: Long = 5,
    val maxPollingMinutes: Long = 30,
    val stalledTimeoutMinutes: Long = 15,
    val missingGraceAttempts: Int = 6,
    val webuiPublicUrl: String? = null,
    val tagPrefix: String = "mwn_"
)

class DownloadTrackerEngine(
    private val downloadClient: DownloadClientPort,
    private val notificationPublisher: NotificationPublisherPort,
    private val activeTrackerStore: ActiveTrackerStore,
    private val config: DownloadTrackerConfig = DownloadTrackerConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Null with the in-memory state store: locks there can't coordinate separate instances.
    private val leases: TrackingLeases? = null
) : TrackDownloadUseCase {
    private val pollIntervalSeconds: Long get() = config.pollIntervalSeconds
    private val maxPollingMinutes: Long get() = config.maxPollingMinutes
    private val stalledTimeoutMinutes: Long get() = config.stalledTimeoutMinutes
    private val missingGraceAttempts: Int get() = config.missingGraceAttempts
    private val webuiPublicUrl: String? get() = config.webuiPublicUrl
    private val tagPrefix: String get() = config.tagPrefix

    private val logger = LoggerFactory.getLogger(DownloadTrackerEngine::class.java)
    private val trackingLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun track(
        hash: String,
        initialPayload: MediaPayload.ArrGrab
    ): Either<DomainError, TrackingStart> {
        val normalizedHash = hash.trim().lowercase()
        if (normalizedHash.isBlank()) {
            return Either.Left(DomainError.WebhookError.MissingTorrentHash)
        }

        val mutex = trackingLocks.computeIfAbsent(normalizedHash) { Mutex() }
        return mutex.withLock {
            if (activeTrackerStore.isTracking(normalizedHash)) {
                logger.info("Download tracker already running for hash: {}", normalizedHash)
                return@withLock Either.Right(TrackingStart.ALREADY_TRACKED)
            }
            if (!acquireLeases(normalizedHash)) {
                return@withLock Either.Right(TrackingStart.TRACKED_ELSEWHERE)
            }

            val initialCard = CardFormatterService.buildGrabInitialCard(initialPayload, webuiPublicUrl)
            val handleResult = notificationPublisher.startLiveProgress(initialCard)

            val handle: NotificationHandle =
                when (handleResult) {
                    is Either.Right -> handleResult.value
                    is Either.Left -> {
                        logger.warn("Failed to send initial card for {}: {}", normalizedHash, handleResult.value)
                        leases?.release(normalizedHash)
                        return@withLock Either.Left(handleResult.value)
                    }
                }

            val isPhoto = initialCard.artworkBytes != null || !initialCard.artworkUrl.isNullOrBlank()

            // Tag torrent in qBittorrent so it can be resumed after container restart
            val tags =
                buildList {
                    add("${tagPrefix}msg:${handle.messageReferenceId}")
                    add("${tagPrefix}photo:${if (isPhoto) 1 else 0}")
                    if (handle.channelOrChatId.isNotBlank()) {
                        add("${tagPrefix}chat:${handle.channelOrChatId}")
                    }
                }
            downloadClient.addTags(normalizedHash, tags)

            lateinit var trackingJob: Job
            trackingJob =
                // ATOMIC: a loop cancelled before it starts still runs its finally and releases its leases.
                scope.launch(start = CoroutineStart.ATOMIC) {
                    runTrackingLoop(normalizedHash, initialPayload, handle)
                }

            val session =
                ActiveTrackerSession(
                    hash = normalizedHash,
                    payload = initialPayload,
                    handle = handle,
                    isPhoto = isPhoto,
                    job = trackingJob
                )
            val registered = activeTrackerStore.register(session)
            if (!registered) {
                logger.warn("Session for hash {} was already registered, cancelling duplicate job", normalizedHash)
                trackingJob.cancel()
            }

            Either.Right(TrackingStart.STARTED)
        }
    }

    override suspend fun trackExisting(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        isPhoto: Boolean
    ): Either<DomainError, TrackingStart> {
        val normalizedHash = hash.trim().lowercase()
        if (normalizedHash.isBlank()) {
            return Either.Left(DomainError.WebhookError.MissingTorrentHash)
        }

        val mutex = trackingLocks.computeIfAbsent(normalizedHash) { Mutex() }
        return mutex.withLock {
            if (activeTrackerStore.isTracking(normalizedHash)) {
                logger.info("Download tracker already running for restored hash: {}", normalizedHash)
                return@withLock Either.Right(TrackingStart.ALREADY_TRACKED)
            }
            if (!acquireLeases(normalizedHash)) {
                return@withLock Either.Right(TrackingStart.TRACKED_ELSEWHERE)
            }

            logger.info("Resuming active tracking loop for existing card {} ({})", payload.title, normalizedHash)

            lateinit var trackingJob: Job
            trackingJob =
                // ATOMIC: a loop cancelled before it starts still runs its finally and releases its leases.
                scope.launch(start = CoroutineStart.ATOMIC) {
                    runTrackingLoop(normalizedHash, payload, handle)
                }

            val session =
                ActiveTrackerSession(
                    hash = normalizedHash,
                    payload = payload,
                    handle = handle,
                    isPhoto = isPhoto,
                    job = trackingJob
                )
            val registered = activeTrackerStore.register(session)
            if (!registered) {
                logger.warn(
                    "Session for restored hash {} was already registered, cancelling duplicate job",
                    normalizedHash
                )
                trackingJob.cancel()
            }

            Either.Right(TrackingStart.STARTED)
        }
    }

    private class TrackingLoopState(
        var missingCount: Int = 0,
        var stalledDurationSeconds: Long = 0L,
        var lastDownloadedBytes: Long = 0L,
        var lastKnownProgress: DownloadItemProgress? = null
    )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTrackingLoop(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle
    ) {
        logger.info("Starting live tracking loop for {} ({})", payload.title, hash)
        val state = TrackingLoopState()
        var elapsedSeconds = 0L
        val maxPollingSeconds = maxPollingMinutes * 60
        var isRunning = true

        try {
            while (elapsedSeconds < maxPollingSeconds && isRunning) {
                delay(pollIntervalSeconds * 1000)
                elapsedSeconds += pollIntervalSeconds

                if (!stillOwned(hash)) return

                isRunning = processTrackingTick(hash, payload, handle, state)
            }

            if (elapsedSeconds >= maxPollingSeconds) {
                logger.warn("Tracking for {} reached max limit of {}m. Halting.", hash, maxPollingMinutes)
                val stalledCard =
                    CardFormatterService.buildStalledCard(
                        payload,
                        state.lastKnownProgress,
                        webuiPublicUrl
                    )
                notificationPublisher.cancelProgress(handle, stalledCard)
                cleanupTags(hash, handle)
                activeTrackerStore.cancel(hash)
            }
        } catch (e: CancellationException) {
            logger.debug("Tracking loop cancelled for {}", hash)
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error in tracking loop for {}", hash, e)
        } finally {
            // Tags stay: every real ending removes them itself, and a cancelled or failed loop must stay resumable.
            val selfJob = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                val registered = activeTrackerStore.get(hash)
                // A duplicate loop that lost register() to another session must not tear that session down.
                if (registered == null || registered.job == selfJob) {
                    leases?.release(hash)
                    if (registered != null) activeTrackerStore.cancel(hash)
                    trackingLocks.remove(hash)
                }
            }
        }
    }

    private suspend fun processTrackingTick(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        state: TrackingLoopState
    ): Boolean {
        val progress = fetchProgress(hash)
        if (progress == null) {
            state.missingCount++
            val shouldHalt = handleMissingTorrent(hash, payload, handle, state.lastKnownProgress, state.missingCount)
            return !shouldHalt
        }

        state.missingCount = 0
        state.lastKnownProgress = progress

        val isTerminal =
            processActiveProgress(
                hash = hash,
                payload = payload,
                handle = handle,
                progress = progress,
                state = state
            )

        return !isTerminal
    }

    private suspend fun fetchProgress(hash: String): DownloadItemProgress? =
        when (val result = downloadClient.getProgress(hash)) {
            is Either.Right -> result.value
            is Either.Left -> {
                logger.debug("Fetch error for {}: {}", hash, result.value)
                null
            }
        }

    private suspend fun handleMissingTorrent(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        lastKnownProgress: DownloadItemProgress?,
        missingCount: Int
    ): Boolean {
        if (missingCount < missingGraceAttempts) {
            return false
        }

        logger.warn("Torrent {} missing after {} attempts. Halting.", hash, missingCount)
        val stalledCard = CardFormatterService.buildStalledCard(payload, lastKnownProgress, webuiPublicUrl)
        notificationPublisher.cancelProgress(handle, stalledCard)
        cleanupTags(hash, handle)
        activeTrackerStore.cancel(hash)
        return true
    }

    private suspend fun processActiveProgress(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        progress: DownloadItemProgress,
        state: TrackingLoopState
    ): Boolean {
        if (progress.progressPercent >= 100 || progress.state.isComplete) {
            logger.info("Torrent {} reached 100%. Dispatching completion card.", hash)
            val completionCard = CardFormatterService.buildCompletionCard(payload, progress, webuiPublicUrl)
            notificationPublisher.completeProgress(handle, completionCard)
            cleanupTags(hash, handle)
            activeTrackerStore.complete(hash)
            state.stalledDurationSeconds = 0L
            state.lastDownloadedBytes = progress.downloadedBytes
            return true
        }

        val maxStalledSeconds = stalledTimeoutMinutes * 60
        val isStalled =
            progress.state.isStalled ||
                (progress.downloadSpeedBytesPerSec == 0L && progress.downloadedBytes == state.lastDownloadedBytes)

        val updatedStalledSeconds = if (isStalled) state.stalledDurationSeconds + pollIntervalSeconds else 0L
        state.stalledDurationSeconds = updatedStalledSeconds
        state.lastDownloadedBytes = progress.downloadedBytes

        if (updatedStalledSeconds >= maxStalledSeconds) {
            logger.warn("Torrent {} stalled for {}s. Halting.", hash, updatedStalledSeconds)
            val stalledCard = CardFormatterService.buildStalledCard(payload, progress, webuiPublicUrl)
            notificationPublisher.cancelProgress(handle, stalledCard)
            cleanupTags(hash, handle)
            activeTrackerStore.cancel(hash)
            return true
        }

        dispatchProgressUpdate(hash, payload, handle, progress)
        activeTrackerStore.updateProgress(hash, progress, state.stalledDurationSeconds)
        return false
    }

    private suspend fun dispatchProgressUpdate(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        progress: DownloadItemProgress
    ) {
        val update = CardFormatterService.buildProgressUpdate(payload, progress, webuiPublicUrl)
        val updateResult = notificationPublisher.updateProgress(handle, update)
        if (updateResult is Either.Left) {
            logger.debug("Dropped progress tick for {}: {}", hash, updateResult.value)
        }
    }

    private suspend fun cleanupTags(
        hash: String,
        handle: NotificationHandle
    ) {
        val currentProgress = downloadClient.getProgress(hash)
        val currentTags =
            when (currentProgress) {
                is Either.Right -> currentProgress.value?.tags ?: emptyList()
                is Either.Left -> emptyList()
            }

        val mwnTagsOnTorrent = currentTags.filter { it.startsWith(tagPrefix) }
        val tagsToRemove =
            buildSet {
                addAll(mwnTagsOnTorrent)
                if (handle.messageReferenceId.isNotBlank()) {
                    add("${tagPrefix}msg:${handle.messageReferenceId}")
                }
                add("${tagPrefix}photo:0")
                add("${tagPrefix}photo:1")
                if (handle.channelOrChatId.isNotBlank()) {
                    add("${tagPrefix}chat:${handle.channelOrChatId}")
                }
            }.toList()

        downloadClient.removeTags(hash, tagsToRemove)

        val tagsToDelete =
            buildSet {
                if (handle.messageReferenceId.isNotBlank()) {
                    add("${tagPrefix}msg:${handle.messageReferenceId}")
                }
                addAll(mwnTagsOnTorrent.filter { it.startsWith("${tagPrefix}msg:") })
            }.toList()

        if (tagsToDelete.isNotEmpty()) {
            downloadClient.deleteTags(tagsToDelete)
        }
    }

    /** Cancels every tracking loop and waits up to [timeout] for them to release their leases. */
    suspend fun stopAll(timeout: Duration = 3.seconds) {
        logger.info("Stopping all active download trackers (count: {})", activeTrackerStore.activeCount())
        val jobs = activeTrackerStore.stopAll()
        if (withTimeoutOrNull(timeout) { jobs.joinAll() } == null) {
            logger.warn("Some download trackers didn't stop within {}", timeout)
        }
    }

    private suspend fun acquireLeases(hash: String): Boolean {
        val leases = leases ?: return true
        if (leases.acquire(hash)) return true
        logger.info("Download {} is tracked by another instance; not starting a second tracker", hash)
        return false
    }

    /** Renews the leases; false only when another instance owns the download now. */
    private suspend fun stillOwned(hash: String): Boolean =
        when (leases?.renew(hash)) {
            LockRenewal.LOST -> {
                logger.warn("Another instance owns {} now; stopping without touching its card", hash)
                false
            }
            LockRenewal.STORE_UNAVAILABLE -> {
                logger.debug("Couldn't renew the tracking lease for {}; state store unavailable, carrying on", hash)
                true
            }
            LockRenewal.RENEWED, null -> true
        }

    fun activeTrackerCount(): Int = activeTrackerStore.activeCount()

    fun isTracking(hash: String): Boolean = activeTrackerStore.isTracking(hash)

    fun getAllSnapshots(): List<TrackerSnapshot> = activeTrackerStore.getAllSnapshots()
}
