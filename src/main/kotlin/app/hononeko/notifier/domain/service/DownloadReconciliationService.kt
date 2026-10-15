package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.TrackingStart
import app.hononeko.notifier.domain.port.inbound.TrackDownloadUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import arrow.core.getOrElse
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
    val tagPrefix: String = "mwn_",
    /** Delay before the next sweep after one hit another instance's tracking lease; null without leases. */
    val leaseRetrySeconds: Long? = null
)

class DownloadReconciliationService(
    private val downloadClient: DownloadClientPort,
    private val trackDownloadUseCase: TrackDownloadUseCase,
    private val activeTrackerStore: ActiveTrackerStore,
    private val notificationPublisher: NotificationPublisherPort,
    private val config: ReconciliationConfig = ReconciliationConfig()
) {
    val enabled: Boolean get() = config.enabled
    val tagPrefix: String get() = config.tagPrefix
    private val intervalMinutes: Long get() = config.intervalMinutes
    private val logger = LoggerFactory.getLogger(DownloadReconciliationService::class.java)
    private val totalRuns = AtomicLong(0)
    private val totalResumed = AtomicLong(0)

    val runCount: Long
        get() = totalRuns.get()

    val resumedCount: Long
        get() = totalResumed.get()

    val leaseRetrySeconds: Long? get() = config.leaseRetrySeconds

    /** Downloads the last sweep left alone because another instance holds their lease. */
    @Volatile
    var heldElsewhereLastSweep: Int = 0
        private set

    suspend fun reconcile(): Int {
        heldElsewhereLastSweep = 0
        if (!enabled) {
            logger.debug("Torrent reconciliation is disabled; skipping sweep.")
            return 0
        }

        totalRuns.incrementAndGet()
        logger.debug("Starting torrent reconciliation sweep...")
        val candidates = sweepCandidates() ?: return 0
        val untracked =
            candidates.filter { torrent ->
                val hash = torrent.normalizedHash()
                hash.isNotBlank() && !activeTrackerStore.isTracking(hash)
            }
        val (tagged, untagged) = untracked.partition { it.tag(tagPrefix, "msg") != null }

        val tally = SweepTally()
        tagged.groupBy { cardOf(it) }.forEach { (card, torrents) -> tally.count(resumeCard(card, torrents)) }
        untagged.forEach { tally.count(trackUntagged(it)) }
        report(tally)
        return tally.resumed
    }

    /**
     * Active downloads plus tagged ones that finished while nothing tracked them (a rollout handoff, downtime):
     * resuming those lets the first tick send the completion card and remove the tags. Untagged finished downloads
     * never had a card. `null` when the active downloads can't be read.
     */
    private suspend fun sweepCandidates(): List<DownloadItemProgress>? {
        val active =
            downloadClient.getActiveDownloads("downloading").getOrElse { error ->
                logger.debug("Torrent reconciliation failed to query active torrents: {}", error)
                return null
            }
        val finishedTagged =
            downloadClient.getActiveDownloads("completed").fold(
                { error ->
                    logger.debug("Torrent reconciliation failed to query finished torrents: {}", error)
                    emptyList()
                },
                { finished -> finished.filter { it.tag(tagPrefix, "msg") != null } }
            )
        return (active + finishedTagged).distinctBy { it.normalizedHash() }
    }

    /** The card a tagged download belongs to: its message, in its own chat or the default one. */
    private data class CardRef(
        val channelOrChatId: String,
        val messageId: String
    )

    private fun cardOf(torrent: DownloadItemProgress): CardRef =
        CardRef(
            channelOrChatId =
                torrent.tag(tagPrefix, "chat")?.ifBlank { null } ?: notificationPublisher.defaultChannelOrChatId,
            messageId = torrent.tag(tagPrefix, "msg").orEmpty()
        )

    /** Resumes the live card that one or more tagged downloads (a season pack, say) belong to. */
    private suspend fun resumeCard(
        card: CardRef,
        torrents: List<DownloadItemProgress>
    ): Either<DomainError, TrackingStart> {
        val distinct = torrents.distinctBy { it.normalizedHash() }
        val combinedHash = distinct.joinToString("|") { it.normalizedHash() }
        val isPhoto = distinct.any { it.tag(tagPrefix, "photo") == "1" }
        val handle =
            NotificationHandle(
                providerId = notificationPublisher.providerId,
                channelOrChatId = card.channelOrChatId,
                messageReferenceId = card.messageId,
                isPhoto = isPhoto
            )
        logger.info(
            "Reconciliation found existing tracked torrent(s): {} (hashes: {}, msgId: {}, isPhoto: {}). " +
                "Resuming progress loop.",
            distinct.map { it.name },
            combinedHash,
            card.messageId,
            isPhoto
        )
        return trackDownloadUseCase.trackExisting(
            hash = combinedHash,
            payload = synthesizedGrab(distinct, combinedHash),
            handle = handle,
            isPhoto = isPhoto
        )
    }

    /** A grab rebuilt from the downloads alone, since the original webhook is long gone. */
    private fun synthesizedGrab(
        torrents: List<DownloadItemProgress>,
        combinedHash: String
    ): MediaPayload.ArrGrab {
        val primary = torrents.first()
        return MediaPayload.ArrGrab(
            source = AppSource.SONARR,
            downloadId = combinedHash,
            downloadIds = torrents.map { it.normalizedHash() },
            title = primary.name,
            seriesOrMovieTitle = primary.name,
            seasonNumber = torrents.firstNotNullOfOrNull { EpisodeFormat.extractSeasonNumber(it.name) },
            episodeNumbers = torrents.mapNotNull { EpisodeFormat.extractEpisodeNumber(it.name) }.distinct().sorted(),
            sizeBytes = torrents.sumOf { it.totalSizeBytes }
        )
    }

    private suspend fun trackUntagged(torrent: DownloadItemProgress): Either<DomainError, TrackingStart> {
        val hash = torrent.normalizedHash()
        val grab =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = hash,
                title = torrent.name,
                seriesOrMovieTitle = torrent.name,
                sizeBytes = torrent.totalSizeBytes
            )
        logger.info(
            "Reconciliation found untracked active download: {} (hash: {}). Starting new tracking loop.",
            torrent.name,
            hash
        )
        return trackDownloadUseCase.track(hash, grab)
    }

    private fun report(tally: SweepTally) {
        heldElsewhereLastSweep = tally.heldElsewhere
        if (tally.heldElsewhere > 0) {
            logger.info("{} download(s) are tracked by another instance; checking again shortly", tally.heldElsewhere)
        }
        if (tally.resumed > 0) {
            logger.info("Torrent reconciliation sweep completed. Resumed {} torrent tracker(s).", tally.resumed)
        } else {
            logger.debug("Torrent reconciliation sweep completed. No untracked downloads found.")
        }
    }

    /** What one sweep did: trackers it started, and downloads another instance already tracks. */
    private inner class SweepTally {
        var resumed = 0
        var heldElsewhere = 0

        fun count(result: Either<DomainError, TrackingStart>) {
            when (result.getOrNull()) {
                TrackingStart.STARTED -> {
                    resumed++
                    totalResumed.incrementAndGet()
                }
                TrackingStart.TRACKED_ELSEWHERE -> heldElsewhere++
                TrackingStart.ALREADY_TRACKED, null -> Unit
            }
        }
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
                // While another instance holds leases (e.g. mid-rollout), look again soon to take over its cards.
                val retrySeconds = leaseRetrySeconds
                delay(
                    if (retrySeconds != null && heldElsewhereLastSweep > 0) retrySeconds * 1000L else intervalMillis
                )
            }
        }
    }
}

private fun DownloadItemProgress.normalizedHash(): String = id.trim().lowercase()

/** The value of the notifier's `<prefix><key>:<value>` tag on this download, if it has one. */
private fun DownloadItemProgress.tag(
    prefix: String,
    key: String
): String? = tags.firstOrNull { it.startsWith("$prefix$key:") }?.removePrefix("$prefix$key:")?.trim()
