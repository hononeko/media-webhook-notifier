package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class DownloadTrackerEngine(
    private val torrentClient: TorrentClientPort,
    private val notificationPublisher: NotificationPublisherPort,
    private val pollIntervalSeconds: Long = 5,
    private val maxPollingMinutes: Long = 30,
    private val stalledTimeoutMinutes: Long = 15,
    private val missingGraceAttempts: Int = 6,
    private val webuiPublicUrl: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TrackDownloadUseCase {
    private val logger = LoggerFactory.getLogger(DownloadTrackerEngine::class.java)
    private val activeTrackers = ConcurrentHashMap<String, Job>()

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

        if (activeTrackers.containsKey(normalizedHash)) {
            logger.info("Download tracker already running for hash: {}", normalizedHash)
            return Either.Right(Unit)
        }

        val initialCard = CardFormatterService.buildGrabInitialCard(initialPayload, webuiPublicUrl)
        val handleResult = notificationPublisher.startLiveProgress(initialCard)

        val handle: NotificationHandle =
            when (handleResult) {
                is Either.Right -> handleResult.value
                is Either.Left -> {
                    logger.warn("Failed to send initial card for {}: {}", normalizedHash, handleResult.value)
                    return Either.Left(handleResult.value)
                }
            }

        val trackingJob =
            scope.launch {
                runTrackingLoop(normalizedHash, initialPayload, handle)
            }

        activeTrackers[normalizedHash] = trackingJob
        trackingJob.invokeOnCompletion {
            activeTrackers.remove(normalizedHash)
        }

        return Either.Right(Unit)
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

                    if (step.isTerminal) {
                        break
                    }

                    stalledDurationSeconds = step.newStalledDurationSeconds
                    lastDownloadedBytes = step.newDownloadedBytes
                }
            }

            if (elapsedSeconds >= maxPollingSeconds) {
                logger.warn("Tracking for {} reached max limit of {}m. Halting.", hash, maxPollingMinutes)
                val stalledCard = CardFormatterService.buildStalledCard(payload, lastKnownProgress, webuiPublicUrl)
                notificationPublisher.cancelProgress(handle, stalledCard)
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in tracking loop for {}", hash, e)
        } finally {
            activeTrackers.remove(hash)
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

    fun stopAll() {
        logger.info("Stopping all active download trackers (count: {})", activeTrackers.size)
        activeTrackers.values.forEach { it.cancel() }
        activeTrackers.clear()
    }

    fun activeTrackerCount(): Int = activeTrackers.size

    fun isTracking(hash: String): Boolean = activeTrackers.containsKey(hash.trim().lowercase())
}
