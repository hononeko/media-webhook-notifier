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
        val maxStalledSeconds = stalledTimeoutMinutes * 60

        try {
            while (elapsedSeconds < maxPollingSeconds) {
                delay(pollIntervalSeconds * 1000)
                elapsedSeconds += pollIntervalSeconds

                when (val progressResult = torrentClient.getTorrentProgress(hash)) {
                    is Either.Left -> {
                        missingCount++
                        logger.debug(
                            "Fetch error ({}/{}): {}",
                            missingCount,
                            missingGraceAttempts,
                            progressResult.value
                        )
                        if (missingCount >= missingGraceAttempts) {
                            logger.warn("Torrent {} not found after {} attempts. Halting.", hash, missingCount)
                            val stalledCard =
                                CardFormatterService.buildStalledCard(
                                    payload,
                                    lastKnownProgress,
                                    webuiPublicUrl
                                )
                            notificationPublisher.cancelProgress(handle, stalledCard)
                            break
                        }
                    }
                    is Either.Right -> {
                        val progress = progressResult.value
                        if (progress == null) {
                            missingCount++
                            if (missingCount >= missingGraceAttempts) {
                                logger.warn("Torrent {} returned null after {} attempts.", hash, missingCount)
                                val stalledCard =
                                    CardFormatterService.buildStalledCard(
                                        payload,
                                        lastKnownProgress,
                                        webuiPublicUrl
                                    )
                                notificationPublisher.cancelProgress(handle, stalledCard)
                                break
                            }
                        } else {
                            missingCount = 0
                            lastKnownProgress = progress

                            if (progress.progressPercent >= 100 || progress.state.isComplete) {
                                logger.info("Torrent {} reached 100%. Dispatching completion card.", hash)
                                val completionCard =
                                    CardFormatterService.buildCompletionCard(
                                        payload,
                                        progress,
                                        webuiPublicUrl
                                    )
                                notificationPublisher.completeProgress(handle, completionCard)
                                break
                            }

                            val isStalled =
                                progress.state.isStalled ||
                                    (
                                        progress.downloadSpeedBytesPerSec == 0L &&
                                            progress.downloadedBytes == lastDownloadedBytes
                                    )

                            if (isStalled) {
                                stalledDurationSeconds += pollIntervalSeconds
                                if (stalledDurationSeconds >= maxStalledSeconds) {
                                    logger.warn("Torrent {} stalled for {}s. Halting.", hash, stalledDurationSeconds)
                                    val stalledCard =
                                        CardFormatterService.buildStalledCard(
                                            payload,
                                            progress,
                                            webuiPublicUrl
                                        )
                                    notificationPublisher.cancelProgress(handle, stalledCard)
                                    break
                                }
                            } else {
                                stalledDurationSeconds = 0L
                            }

                            lastDownloadedBytes = progress.downloadedBytes

                            val update = CardFormatterService.buildProgressUpdate(payload, progress, webuiPublicUrl)
                            val updateResult = notificationPublisher.updateProgress(handle, update)
                            if (updateResult is Either.Left) {
                                logger.debug("Dropped progress tick for {}: {}", hash, updateResult.value)
                            }
                        }
                    }
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

    fun stopAll() {
        logger.info("Stopping all active download trackers (count: {})", activeTrackers.size)
        activeTrackers.values.forEach { it.cancel() }
        activeTrackers.clear()
    }

    fun activeTrackerCount(): Int = activeTrackers.size

    fun isTracking(hash: String): Boolean = activeTrackers.containsKey(hash.trim().lowercase())
}
