package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

class TorrentReconciliationService(
    private val torrentClient: TorrentClientPort,
    private val trackDownloadUseCase: TrackDownloadUseCase,
    private val activeTrackerStore: ActiveTrackerStore,
    private val notificationPublisher: NotificationPublisherPort,
    private val intervalMinutes: Long = 5L,
    val enabled: Boolean = true,
    val tagPrefix: String = "mwn_"
) {
    private val logger = LoggerFactory.getLogger(TorrentReconciliationService::class.java)
    private val totalRuns = AtomicLong(0)
    private val totalResumed = AtomicLong(0)

    val runCount: Long
        get() = totalRuns.get()

    val resumedCount: Long
        get() = totalResumed.get()

    suspend fun reconcile(): Int {
        if (!enabled) {
            logger.debug("Torrent reconciliation is disabled; skipping sweep.")
            return 0
        }

        totalRuns.incrementAndGet()
        logger.debug("Starting torrent reconciliation sweep...")

        val activeTorrents =
            when (val activeResult = torrentClient.getActiveTorrents("downloading")) {
                is Either.Right -> activeResult.value
                is Either.Left -> {
                    logger.debug("Torrent reconciliation failed to query active torrents: {}", activeResult.value)
                    return 0
                }
            }

        var resumedThisRun = 0
        for (torrent in activeTorrents) {
            val normalizedHash = torrent.hash.trim().lowercase()
            if (normalizedHash.isBlank() || activeTrackerStore.isTracking(normalizedHash)) {
                continue
            }

            val msgTag = torrent.tags.firstOrNull { it.startsWith("${tagPrefix}msg:") }
            val photoTag = torrent.tags.firstOrNull { it.startsWith("${tagPrefix}photo:") }
            val chatTag = torrent.tags.firstOrNull { it.startsWith("${tagPrefix}chat:") }

            val synthesizedGrab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = normalizedHash,
                    title = torrent.name,
                    seriesOrMovieTitle = torrent.name,
                    sizeBytes = torrent.totalSizeBytes
                )

            val trackResult =
                if (msgTag != null) {
                    val messageId = msgTag.removePrefix("${tagPrefix}msg:").trim()
                    val isPhoto = photoTag?.removePrefix("${tagPrefix}photo:")?.trim() == "1"
                    val channelOrChatId =
                        chatTag?.removePrefix("${tagPrefix}chat:")?.trim()?.ifBlank { null }
                            ?: notificationPublisher.defaultChannelOrChatId
                    val handle =
                        NotificationHandle(
                            providerId = notificationPublisher.providerId,
                            channelOrChatId = channelOrChatId,
                            messageReferenceId = messageId,
                            isPhoto = isPhoto
                        )

                    logger.info(
                        "Reconciliation found existing tracked torrent: {} (hash: {}, msgId: {}, isPhoto: {}). Resuming progress loop.",
                        torrent.name,
                        normalizedHash,
                        messageId,
                        isPhoto
                    )

                    trackDownloadUseCase.trackExisting(
                        hash = normalizedHash,
                        payload = synthesizedGrab,
                        handle = handle,
                        isPhoto = isPhoto
                    )
                } else {
                    logger.info(
                        "Reconciliation found untracked active download: {} (hash: {}). Starting new tracking loop.",
                        torrent.name,
                        normalizedHash
                    )

                    trackDownloadUseCase.track(normalizedHash, synthesizedGrab)
                }

            if (trackResult is Either.Right) {
                resumedThisRun++
                totalResumed.incrementAndGet()
            }
        }

        if (resumedThisRun > 0) {
            logger.info("Torrent reconciliation sweep completed. Resumed {} torrent tracker(s).", resumedThisRun)
        } else {
            logger.debug("Torrent reconciliation sweep completed. No untracked downloads found.")
        }

        return resumedThisRun
    }

    fun start(scope: CoroutineScope): Job? {
        if (!enabled) {
            logger.info("Torrent reconciliation loop is disabled via configuration.")
            return null
        }

        val intervalMillis = (intervalMinutes.coerceAtLeast(1) * 60 * 1000L)
        logger.info(
            "Starting torrent reconciliation service with interval of {} minute(s)",
            intervalMinutes
        )

        return scope.launch {
            while (isActive) {
                try {
                    reconcile()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error during torrent reconciliation sweep: {}", e.message, e)
                }
                delay(intervalMillis)
            }
        }
    }
}
