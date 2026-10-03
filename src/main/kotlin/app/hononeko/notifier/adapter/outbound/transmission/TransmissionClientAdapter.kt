package app.hononeko.notifier.adapter.outbound.transmission

import app.hononeko.notifier.config.TransmissionConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import app.hononeko.notifier.domain.service.TorrentProgressAggregator
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

class TransmissionClientAdapter(
    config: TransmissionConfig,
    engine: HttpClientEngine? = null
) : TorrentClientPort {
    companion object {
        private val INFO_HASH_REGEX = Regex("^[0-9a-f]{40}$")

        private val LIST_FIELDS =
            listOf(
                "id",
                "hashString",
                "name",
                "percentDone",
                "rateDownload",
                "rateUpload",
                "eta",
                "status",
                "peersConnected",
                "peersSendingToUs",
                "sizeWhenDone",
                "leftUntilDone",
                "isStalled",
                "metadataPercentComplete",
                "labels"
            )

        // Tracker stats are comparatively heavy, so only single-download lookups request swarm totals.
        private val DETAIL_FIELDS = LIST_FIELDS + "trackerStats"
        private val LABEL_FIELDS = listOf("id", "hashString", "labels")

        /**
         * Splits a (possibly `|`-joined) download id into normalized info hashes. Returns null unless every
         * part is a 40-char hex info hash, so mutating RPCs can never be sent without an explicit `ids` list
         * (Transmission applies id-less requests to every torrent).
         */
        fun parseInfoHashes(hash: String): List<String>? {
            val hashes =
                hash
                    .split('|')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            return hashes.takeIf { it.isNotEmpty() && it.all(INFO_HASH_REGEX::matches) }
        }
    }

    private val logger = LoggerFactory.getLogger(TransmissionClientAdapter::class.java)
    private val rpc = TransmissionRpcClient(config, engine)
    private val labelMutex = Mutex()

    override suspend fun getTorrentProgress(hash: String): Either<DomainError.TorrentClientError, TorrentProgress?> {
        val hashes = parseInfoHashes(hash)
        if (hashes == null) {
            logger.debug("Skipping getTorrentProgress for invalid or blank torrent hash: '{}'", hash)
            return Either.Right(null)
        }

        return rpc.torrentGet(hashes, DETAIL_FIELDS).map { torrents ->
            val progress = torrents.map { it.toTorrentProgress() }
            when {
                progress.isEmpty() -> null
                hashes.size == 1 && progress.size == 1 -> progress.single()
                else -> TorrentProgressAggregator.aggregate(hashes.joinToString("|"), progress)
            }
        }
    }

    override suspend fun getActiveTorrents(
        filter: String
    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
        rpc.torrentGet(ids = null, fields = LIST_FIELDS).map { torrents ->
            torrents.filter { it.matchesFilter(filter) }.map { it.toTorrentProgress() }
        }

    override suspend fun addTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> =
        mutateLabels(hash, tags) { current, requested -> (current + requested).distinct() }

    override suspend fun removeTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> =
        mutateLabels(hash, tags) { current, requested -> current.filterNot { it in requested } }

    // Transmission labels live only on torrents; there is no global label registry to prune.
    override suspend fun deleteTags(tags: List<String>): Either<DomainError.TorrentClientError, Unit> =
        Either.Right(Unit)

    suspend fun startTorrents(hash: String): Either<DomainError.TorrentClientError, Unit> =
        runTorrentAction(TransmissionMethod.TORRENT_START, hash)

    suspend fun stopTorrents(hash: String): Either<DomainError.TorrentClientError, Unit> =
        runTorrentAction(TransmissionMethod.TORRENT_STOP, hash)

    suspend fun removeTorrents(
        hash: String,
        deleteLocalData: Boolean = false
    ): Either<DomainError.TorrentClientError, Unit> =
        runTorrentAction(TransmissionMethod.TORRENT_REMOVE, hash) { put("delete-local-data", deleteLocalData) }

    private suspend fun runTorrentAction(
        method: String,
        hash: String,
        extraArguments: JsonObjectBuilder.() -> Unit = {}
    ): Either<DomainError.TorrentClientError, Unit> {
        val hashes = parseInfoHashes(hash) ?: return Either.Left(DomainError.TorrentClientError.TorrentNotFound(hash))
        val arguments =
            buildJsonObject {
                putIds(hashes)
                extraArguments()
            }
        return rpc.call(method, arguments).map { }
    }

    /**
     * `torrent-set` replaces a torrent's whole label list, so labels are read, transformed and written back
     * per torrent. The mutex serialises concurrent read-modify-write cycles from parallel trackers.
     */
    private suspend fun mutateLabels(
        hash: String,
        tags: List<String>,
        transform: (current: List<String>, requested: Set<String>) -> List<String>
    ): Either<DomainError.TorrentClientError, Unit> {
        val hashes = parseInfoHashes(hash)
        val requested = tags.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (hashes == null || requested.isEmpty()) {
            return Either.Right(Unit)
        }

        return labelMutex.withLock {
            either {
                val torrents = rpc.torrentGet(hashes, LABEL_FIELDS).bind()
                torrents
                    .filter { INFO_HASH_REGEX.matches(it.hashString.lowercase()) }
                    .forEach { torrent ->
                        val updated = transform(torrent.labels, requested)
                        if (updated != torrent.labels) {
                            val arguments =
                                buildJsonObject {
                                    putIds(listOf(torrent.hashString.lowercase()))
                                    putJsonArray("labels") { updated.forEach { add(it) } }
                                }
                            rpc.call(TransmissionMethod.TORRENT_SET, arguments).bind()
                        }
                    }
            }
        }
    }
}
