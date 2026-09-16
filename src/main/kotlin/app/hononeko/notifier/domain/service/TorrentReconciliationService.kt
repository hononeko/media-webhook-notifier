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

data class ReconciliationConfig(
    val intervalMinutes: Long = 5L,
    val enabled: Boolean = true,
    val tagPrefix: String = "mwn_"
)

class TorrentReconciliationService(
    private val torrentClient: TorrentClientPort,
    private val trackDownloadUseCase: TrackDownloadUseCase,
    private val activeTrackerStore: ActiveTrackerStore,
    private val notificationPublisher: NotificationPublisherPort,
    private val config: ReconciliationConfig = ReconciliationConfig()
) {
    val enabled: Boolean get() = config.enabled
    val tagPrefix: String get() = config.tagPrefix
    private val intervalMinutes: Long get() = config.intervalMinutes
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
        val untrackedTorrents =
            activeTorrents.filter { torrent ->
                val normalizedHash = torrent.hash.trim().lowercase()
                normalizedHash.isNotBlank() && !activeTrackerStore.isTracking(normalizedHash)
            }

        val (taggedTorrents, untaggedTorrents) =
            untrackedTorrents.partition { torrent ->
                torrent.tags.any { it.startsWith("${tagPrefix}msg:") }
            }

        val groupedTaggedTorrents =
            taggedTorrents.groupBy { torrent ->
                val msgTag = torrent.tags.first { it.startsWith("${tagPrefix}msg:") }
                val messageId = msgTag.removePrefix("${tagPrefix}msg:").trim()
                val chatTag = torrent.tags.firstOrNull { it.startsWith("${tagPrefix}chat:") }
                val channelOrChatId =
                    chatTag?.removePrefix("${tagPrefix}chat:")?.trim()?.ifBlank { null }
                        ?: notificationPublisher.defaultChannelOrChatId
                channelOrChatId to messageId
            }

        for ((key, torrentGroup) in groupedTaggedTorrents) {
            val (channelOrChatId, messageId) = key
            val distinctTorrents = torrentGroup.distinctBy { it.hash.trim().lowercase() }
            val combinedHash = distinctTorrents.joinToString("|") { it.hash.trim().lowercase() }
            val downloadIds = distinctTorrents.map { it.hash.trim().lowercase() }
            val isPhoto =
                distinctTorrents.any { torrent ->
                    torrent.tags
                        .firstOrNull { it.startsWith("${tagPrefix}photo:") }
                        ?.removePrefix("${tagPrefix}photo:")
                        ?.trim() == "1"
                }

            val episodeNumbers =
                distinctTorrents
                    .mapNotNull { CardFormatterService.extractEpisodeNumber(it.name) }
                    .distinct()
                    .sorted()
            val seasonNumber =
                distinctTorrents
                    .mapNotNull { CardFormatterService.extractSeasonNumber(it.name) }
                    .firstOrNull()

            val primaryTorrent = distinctTorrents.first()
            val totalSizeBytes = distinctTorrents.sumOf { it.totalSizeBytes }

            val synthesizedGrab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = combinedHash,
                    downloadIds = downloadIds,
                    title = primaryTorrent.name,
                    seriesOrMovieTitle = primaryTorrent.name,
                    seasonNumber = seasonNumber,
                    episodeNumbers = episodeNumbers,
                    sizeBytes = totalSizeBytes
                )

            val handle =
                NotificationHandle(
                    providerId = notificationPublisher.providerId,
                    channelOrChatId = channelOrChatId,
                    messageReferenceId = messageId,
                    isPhoto = isPhoto
                )

            logger.info(
                "Reconciliation found existing tracked torrent(s): {} (hashes: {}, msgId: {}, isPhoto: {}). " +
                    "Resuming progress loop.",
                distinctTorrents.map { it.name },
                combinedHash,
                messageId,
                isPhoto
            )

            val trackResult =
                trackDownloadUseCase.trackExisting(
                    hash = combinedHash,
                    payload = synthesizedGrab,
                    handle = handle,
                    isPhoto = isPhoto
                )

            if (trackResult is Either.Right) {
                resumedThisRun++
                totalResumed.incrementAndGet()
            }
        }

        for (torrent in untaggedTorrents) {
            val normalizedHash = torrent.hash.trim().lowercase()
            val synthesizedGrab =
                MediaPayload.ArrGrab(
                    source = AppSource.SONARR,
                    downloadId = normalizedHash,
                    title = torrent.name,
                    seriesOrMovieTitle = torrent.name,
                    sizeBytes = torrent.totalSizeBytes
                )

            logger.info(
                "Reconciliation found untracked active download: {} (hash: {}). Starting new tracking loop.",
                torrent.name,
                normalizedHash
            )

            val trackResult = trackDownloadUseCase.track(normalizedHash, synthesizedGrab)
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

    @Suppress("TooGenericExceptionCaught")
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
                    throw e
                } catch (e: Exception) {
                    logger.error("Error during torrent reconciliation sweep: {}", e.message, e)
                }
                delay(intervalMillis)
            }
        }
    }
}
