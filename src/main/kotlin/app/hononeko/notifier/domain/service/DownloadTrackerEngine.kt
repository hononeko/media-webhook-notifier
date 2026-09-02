package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TrackerSnapshot
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class DownloadTrackerEngine(
    private val torrentClient: TorrentClientPort,
    private val notificationPublisher: NotificationPublisherPort,
    private val activeTrackerStore: ActiveTrackerStore,
    private val pollIntervalSeconds: Long = 5,
    private val maxPollingMinutes: Long = 30,
    private val stalledTimeoutMinutes: Long = 15,
    private val missingGraceAttempts: Int = 6,
    private val webuiPublicUrl: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TrackDownloadUseCase {
    private val logger = LoggerFactory.getLogger(DownloadTrackerEngine::class.java)
    private val trackingLocks = ConcurrentHashMap<String, Mutex>()

    private data class TrackerStep(
        val isTerminal: Boolean,
        val newStalledDurationSeconds: Long,
        val newDownloadedBytes: Long
    )

    override suspend fun track(
        hash: String,
        initialPayload: MediaPayload.ArrGrab
    ): Either<DomainError, Unit> {
        val normalizedHash = hash.trim().lowercase()
        if (normalizedHash.isBlank()) {
            return Either.Left(DomainError.WebhookError.MissingTorrentHash)
        }

        val mutex = trackingLocks.computeIfAbsent(normalizedHash) { Mutex() }
        return mutex.withLock {
            if (activeTrackerStore.isTracking(normalizedHash)) {
                logger.info("Download tracker already running for hash: {}", normalizedHash)
                return@withLock Either.Right(Unit)
            }

            val initialCard = CardFormatterService.buildGrabInitialCard(initialPayload, webuiPublicUrl)
            val handleResult = notificationPublisher.startLiveProgress(initialCard)

            val handle: NotificationHandle =
                when (handleResult) {
                    is Either.Right -> handleResult.value
                    is Either.Left -> {
                        logger.warn("Failed to send initial card for {}: {}", normalizedHash, handleResult.value)
                        return@withLock Either.Left(handleResult.value)
                    }
                }

            val isPhoto = initialCard.artworkBytes != null || !initialCard.artworkUrl.isNullOrBlank()

            // Tag torrent in qBittorrent so it can be resumed after container restart
            val tags =
                buildList {
                    add("mwn_msg:${handle.messageReferenceId}")
                    add("mwn_photo:${if (isPhoto) 1 else 0}")
                    if (handle.channelOrChatId.isNotBlank()) {
                        add("mwn_chat:${handle.channelOrChatId}")
                    }
                }
            torrentClient.addTorrentTags(normalizedHash, tags)

            lateinit var trackingJob: Job
            trackingJob =
                scope.launch {
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

            Either.Right(Unit)
        }
    }

    override suspend fun trackExisting(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        isPhoto: Boolean
    ): Either<DomainError, Unit> {
        val normalizedHash = hash.trim().lowercase()
        if (normalizedHash.isBlank()) {
            return Either.Left(DomainError.WebhookError.MissingTorrentHash)
        }

        val mutex = trackingLocks.computeIfAbsent(normalizedHash) { Mutex() }
        return mutex.withLock {
            if (activeTrackerStore.isTracking(normalizedHash)) {
                logger.info("Download tracker already running for restored hash: {}", normalizedHash)
                return@withLock Either.Right(Unit)
            }

            logger.info("Resuming active tracking loop for existing card {} ({})", payload.title, normalizedHash)

            lateinit var trackingJob: Job
            trackingJob =
                scope.launch {
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

            Either.Right(Unit)
        }
    }

    private suspend fun runTrackingLoop(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle
    ) {
        logger.info("Starting live tracking loop for {} ({})", payload.title, hash)
        var missingCount = 0
        var stalledDurationSeconds = 0L
        var elapsedSeconds = 0L
        var lastDownloadedBytes = 0L
        var lastKnownProgress: TorrentProgress? = null

        val maxPollingSeconds = maxPollingMinutes * 60

        try {
            while (elapsedSeconds < maxPollingSeconds) {
                delay(pollIntervalSeconds * 1000)
                elapsedSeconds += pollIntervalSeconds

                val progress = fetchTorrentProgress(hash)
                if (progress == null) {
                    missingCount++
                    if (handleMissingTorrent(hash, payload, handle, lastKnownProgress, missingCount)) {
                        break
                    }
                } else {
                    missingCount = 0
                    lastKnownProgress = progress

                    val step =
                        processActiveProgress(
                            hash = hash,
                            payload = payload,
                            handle = handle,
                            progress = progress,
                            lastDownloadedBytes = lastDownloadedBytes,
                            stalledDurationSeconds = stalledDurationSeconds
                        )

                    stalledDurationSeconds = step.newStalledDurationSeconds
                    lastDownloadedBytes = step.newDownloadedBytes
                    activeTrackerStore.updateProgress(hash, progress, stalledDurationSeconds)

                    if (step.isTerminal) {
                        break
                    }
                }
            }

            if (elapsedSeconds >= maxPollingSeconds) {
                logger.warn("Tracking for {} reached max limit of {}m. Halting.", hash, maxPollingMinutes)
                val stalledCard = CardFormatterService.buildStalledCard(payload, lastKnownProgress, webuiPublicUrl)
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
            withContext(NonCancellable) {
                cleanupTags(hash, handle)
                if (activeTrackerStore.isTracking(hash)) {
                    activeTrackerStore.cancel(hash)
                }
                trackingLocks.remove(hash)
            }
        }
    }

    private suspend fun fetchTorrentProgress(hash: String): TorrentProgress? =
        when (val result = torrentClient.getTorrentProgress(hash)) {
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
        lastKnownProgress: TorrentProgress?,
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
        progress: TorrentProgress,
        lastDownloadedBytes: Long,
        stalledDurationSeconds: Long
    ): TrackerStep {
        if (progress.progressPercent >= 100 || progress.state.isComplete) {
            logger.info("Torrent {} reached 100%. Dispatching completion card.", hash)
            val completionCard = CardFormatterService.buildCompletionCard(payload, progress, webuiPublicUrl)
            notificationPublisher.completeProgress(handle, completionCard)
            cleanupTags(hash, handle)
            activeTrackerStore.complete(hash)
            return TrackerStep(
                isTerminal = true,
                newStalledDurationSeconds = 0L,
                newDownloadedBytes = progress.downloadedBytes
            )
        }

        val maxStalledSeconds = stalledTimeoutMinutes * 60
        val isStalled =
            progress.state.isStalled ||
                (progress.downloadSpeedBytesPerSec == 0L && progress.downloadedBytes == lastDownloadedBytes)

        val updatedStalledSeconds = if (isStalled) stalledDurationSeconds + pollIntervalSeconds else 0L

        if (updatedStalledSeconds >= maxStalledSeconds) {
            logger.warn("Torrent {} stalled for {}s. Halting.", hash, updatedStalledSeconds)
            val stalledCard = CardFormatterService.buildStalledCard(payload, progress, webuiPublicUrl)
            notificationPublisher.cancelProgress(handle, stalledCard)
            cleanupTags(hash, handle)
            activeTrackerStore.cancel(hash)
            return TrackerStep(
                isTerminal = true,
                newStalledDurationSeconds = updatedStalledSeconds,
                newDownloadedBytes = progress.downloadedBytes
            )
        }

        dispatchProgressUpdate(hash, payload, handle, progress)
        return TrackerStep(
            isTerminal = false,
            newStalledDurationSeconds = updatedStalledSeconds,
            newDownloadedBytes = progress.downloadedBytes
        )
    }

    private suspend fun dispatchProgressUpdate(
        hash: String,
        payload: MediaPayload.ArrGrab,
        handle: NotificationHandle,
        progress: TorrentProgress
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
        val currentProgress = torrentClient.getTorrentProgress(hash)
        val currentTags =
            when (currentProgress) {
                is Either.Right -> currentProgress.value?.tags ?: emptyList()
                is Either.Left -> emptyList()
            }

        val mwnTagsOnTorrent = currentTags.filter { it.startsWith("mwn_") }
        val tagsToRemove =
            buildSet {
                addAll(mwnTagsOnTorrent)
                if (handle.messageReferenceId.isNotBlank()) {
                    add("mwn_msg:${handle.messageReferenceId}")
                }
                add("mwn_photo:0")
                add("mwn_photo:1")
                if (handle.channelOrChatId.isNotBlank()) {
                    add("mwn_chat:${handle.channelOrChatId}")
                }
            }.toList()

        torrentClient.removeTorrentTags(hash, tagsToRemove)

        val tagsToDelete =
            buildSet {
                if (handle.messageReferenceId.isNotBlank()) {
                    add("mwn_msg:${handle.messageReferenceId}")
                }
                addAll(mwnTagsOnTorrent.filter { it.startsWith("mwn_msg:") })
            }.toList()

        if (tagsToDelete.isNotEmpty()) {
            torrentClient.deleteTags(tagsToDelete)
        }
    }

    fun stopAll() {
        logger.info("Stopping all active download trackers (count: {})", activeTrackerStore.activeCount())
        activeTrackerStore.stopAll()
    }

    fun activeTrackerCount(): Int = activeTrackerStore.activeCount()

    fun isTracking(hash: String): Boolean = activeTrackerStore.isTracking(hash)

    fun getAllSnapshots(): List<TrackerSnapshot> = activeTrackerStore.getAllSnapshots()
}
