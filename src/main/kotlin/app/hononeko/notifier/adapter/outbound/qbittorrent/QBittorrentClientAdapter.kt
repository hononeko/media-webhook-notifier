package app.hononeko.notifier.adapter.outbound.qbittorrent

import app.hononeko.notifier.config.QBittorrentConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TorrentState
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class QBittorrentClientAdapter(
    private val config: QBittorrentConfig,
    engine: HttpClientEngine? = null
) : TorrentClientPort {
    companion object {
        private val TORRENT_HASH_REGEX = Regex("^[a-zA-Z0-9_-]+(\\|[a-zA-Z0-9_-]+)*$")

        fun isValidHash(hash: String): Boolean = hash.isNotBlank() && TORRENT_HASH_REGEX.matches(hash)
    }

    private val logger = LoggerFactory.getLogger(QBittorrentClientAdapter::class.java)
    private val sidCookie = AtomicReference<String?>(null)
    private val authMutex = Mutex()

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val httpClient =
        if (engine != null) {
            HttpClient(engine) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
                }
            }
        } else {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(jsonConfig) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
                }
            }
        }

    @Serializable
    private data class QBitTorrentDto(
        val hash: String,
        val name: String? = null,
        val progress: Double = 0.0,
        val dlspeed: Long = 0,
        val upspeed: Long = 0,
        val eta: Long = 0,
        @SerialName("total_size")
        val totalSize: Long = 0,
        val completed: Long = 0,
        val state: String = "unknown",
        val tags: String? = null,
        val category: String? = null,
        @SerialName("num_seeds")
        val numSeeds: Int = 0,
        @SerialName("num_complete")
        val numComplete: Int = 0,
        @SerialName("num_leechs")
        val numLeechs: Int = 0,
        @SerialName("num_incomplete")
        val numIncomplete: Int = 0
    )

    override suspend fun getTorrentProgress(hash: String): Either<DomainError.TorrentClientError, TorrentProgress?> {
        val normalizedHash = hash.trim().lowercase()
        if (!isValidHash(normalizedHash)) {
            logger.debug("Skipping getTorrentProgress for invalid or blank torrent hash: '{}'", hash)
            return Either.Right(null)
        }

        return try {
            ensureAuthenticated()

            val baseUrl = config.url.trimEnd('/')
            val endpoint = "$baseUrl/api/v2/torrents/info?hashes=$normalizedHash"

            val response = executeRequestWithAuth(endpoint)

            if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized) {
                sidCookie.set(null)
                ensureAuthenticated(force = true)
                val retryResponse = executeRequestWithAuth(endpoint)
                parseTorrentResponse(retryResponse, normalizedHash)
            } else {
                parseTorrentResponse(response, normalizedHash)
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch torrent progress for hash {}: {}", normalizedHash, e.message)
            Either.Left(DomainError.TorrentClientError.ConnectionFailed(config.url, e))
        }
    }

    override suspend fun getActiveTorrents(
        filter: String
    ): Either<DomainError.TorrentClientError, List<TorrentProgress>> =
        try {
            ensureAuthenticated()

            val baseUrl = config.url.trimEnd('/')
            val endpoint = "$baseUrl/api/v2/torrents/info?filter=$filter"

            val response = executeRequestWithAuth(endpoint)

            val finalResponse =
                if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized) {
                    sidCookie.set(null)
                    ensureAuthenticated(force = true)
                    executeRequestWithAuth(endpoint)
                } else {
                    response
                }

            if (!finalResponse.status.value
                    .toString()
                    .startsWith("2")
            ) {
                Either.Left(
                    DomainError.TorrentClientError.InvalidResponse(
                        "HTTP ${finalResponse.status.value}: ${finalResponse.status.description}"
                    )
                )
            } else {
                val rawBody: String = finalResponse.bodyAsText()
                val torrentList: List<QBitTorrentDto> =
                    try {
                        jsonConfig.decodeFromString(ListSerializer(QBitTorrentDto.serializer()), rawBody)
                    } catch (e: Exception) {
                        logger.error("Failed to parse qBittorrent JSON response: {}", rawBody, e)
                        return Either.Left(DomainError.TorrentClientError.InvalidResponse(e.message ?: "Invalid JSON"))
                    }

                Either.Right(torrentList.map { it.toTorrentProgress() })
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch active torrents: {}", e.message)
            Either.Left(DomainError.TorrentClientError.ConnectionFailed(config.url, e))
        }

    override suspend fun addTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> =
        mutateTorrentTags(endpointPath = "/api/v2/torrents/addTags", hash = hash, tags = tags, action = "add")

    override suspend fun removeTorrentTags(
        hash: String,
        tags: List<String>
    ): Either<DomainError.TorrentClientError, Unit> =
        mutateTorrentTags(endpointPath = "/api/v2/torrents/removeTags", hash = hash, tags = tags, action = "remove")

    override suspend fun deleteTags(tags: List<String>): Either<DomainError.TorrentClientError, Unit> =
        mutateTorrentTags(endpointPath = "/api/v2/torrents/deleteTags", hash = null, tags = tags, action = "delete")

    private suspend fun mutateTorrentTags(
        endpointPath: String,
        hash: String?,
        tags: List<String>,
        action: String
    ): Either<DomainError.TorrentClientError, Unit> {
        val normalizedHash = hash?.trim()?.lowercase() ?: ""
        val tagString =
            tags
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(",")
        if ((hash != null && !isValidHash(normalizedHash)) || tagString.isBlank()) {
            return Either.Right(Unit)
        }

        return try {
            ensureAuthenticated()
            val baseUrl = config.url.trimEnd('/')
            val endpoint = "$baseUrl$endpointPath"
            val params =
                Parameters.build {
                    if (normalizedHash.isNotBlank()) {
                        append("hashes", normalizedHash)
                    }
                    append("tags", tagString)
                }
            val response = executePostWithAuth(endpoint, params)
            if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized) {
                sidCookie.set(null)
                ensureAuthenticated(force = true)
                executePostWithAuth(endpoint, params)
            }
            Either.Right(Unit)
        } catch (e: Exception) {
            logger.debug("Failed to {} tags {}: {}", action, tagString, e.message)
            Either.Left(DomainError.TorrentClientError.ConnectionFailed(config.url, e))
        }
    }

    private suspend fun executeRequestWithAuth(url: String): HttpResponse =
        httpClient.get(url) {
            val cookie = sidCookie.get()
            if (!cookie.isNullOrBlank()) {
                header(HttpHeaders.Cookie, cookie)
            }
        }

    private suspend fun executePostWithAuth(
        url: String,
        formParameters: Parameters
    ): HttpResponse =
        httpClient.submitForm(
            url = url,
            formParameters = formParameters
        ) {
            val cookie = sidCookie.get()
            if (!cookie.isNullOrBlank()) {
                header(HttpHeaders.Cookie, cookie)
            }
        }

    private fun QBitTorrentDto.toTorrentProgress(): TorrentProgress {
        val progressPercent = (progress * 100.0).coerceIn(0.0, 100.0)
        val parsedTags =
            tags
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() } ?: emptyList()
        return TorrentProgress(
            hash = hash,
            name = name ?: "Unknown",
            progressPercent = progressPercent,
            progressRatio = progress,
            downloadSpeedBytesPerSec = dlspeed,
            uploadSpeedBytesPerSec = upspeed,
            etaSeconds = eta,
            totalSizeBytes = totalSize,
            downloadedBytes = completed,
            seedsCount = numSeeds,
            seedsTotal = numComplete,
            peersCount = numLeechs,
            peersTotal = numIncomplete,
            state = mapState(state),
            tags = parsedTags
        )
    }

    private suspend fun parseTorrentResponse(
        response: HttpResponse,
        hash: String
    ): Either<DomainError.TorrentClientError, TorrentProgress?> {
        if (!response.status.value
                .toString()
                .startsWith("2")
        ) {
            return Either.Left(
                DomainError.TorrentClientError.InvalidResponse(
                    "HTTP ${response.status.value}: ${response.status.description}"
                )
            )
        }

        val rawBody: String = response.bodyAsText()
        val torrentList: List<QBitTorrentDto> =
            try {
                jsonConfig.decodeFromString(ListSerializer(QBitTorrentDto.serializer()), rawBody)
            } catch (e: Exception) {
                logger.error("Failed to parse qBittorrent JSON response: {}", rawBody, e)
                return Either.Left(DomainError.TorrentClientError.InvalidResponse(e.message ?: "Invalid JSON"))
            }

        if (torrentList.isEmpty()) {
            return Either.Right(null)
        }

        if (torrentList.size == 1) {
            return Either.Right(torrentList.first().toTorrentProgress())
        }

        val totalSize = torrentList.sumOf { it.totalSize }
        val totalCompleted = torrentList.sumOf { it.completed }
        val totalDlSpeed = torrentList.sumOf { it.dlspeed }
        val totalUpSpeed = torrentList.sumOf { it.upspeed }
        val maxEta = torrentList.maxOfOrNull { it.eta } ?: 0L
        val maxSeeds = torrentList.maxOfOrNull { it.numSeeds } ?: 0
        val maxSeedsTotal = torrentList.maxOfOrNull { it.numComplete } ?: 0
        val maxPeers = torrentList.maxOfOrNull { it.numLeechs } ?: 0
        val maxPeersTotal = torrentList.maxOfOrNull { it.numIncomplete } ?: 0

        val aggregateRatio =
            if (totalSize > 0) {
                (totalCompleted.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
            } else {
                (torrentList.map { it.progress }.average()).coerceIn(0.0, 1.0)
            }
        val aggregatePercent = (aggregateRatio * 100.0).coerceIn(0.0, 100.0)

        val states = torrentList.map { mapState(it.state) }
        val aggregateState =
            when {
                states.all { it == TorrentState.COMPLETED } -> TorrentState.COMPLETED
                states.any { it == TorrentState.DOWNLOADING } -> TorrentState.DOWNLOADING
                states.any { it == TorrentState.ALLOCATING_METADATA } -> TorrentState.ALLOCATING_METADATA
                states.any { it == TorrentState.CHECKING } -> TorrentState.CHECKING
                states.all { it == TorrentState.STALLED } -> TorrentState.STALLED
                states.all { it == TorrentState.PAUSED } -> TorrentState.PAUSED
                states.all { it == TorrentState.QUEUED } -> TorrentState.QUEUED
                else -> TorrentState.DOWNLOADING
            }

        val allTags =
            torrentList
                .flatMap { it.tags?.split(",") ?: emptyList() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        val childItems =
            if (torrentList.size > 1) {
                torrentList.map { it.toTorrentProgress() }
            } else {
                emptyList()
            }

        return Either.Right(
            TorrentProgress(
                hash = hash,
                name = torrentList.firstOrNull()?.name ?: "Multi-torrent Download",
                progressPercent = aggregatePercent,
                progressRatio = aggregateRatio,
                downloadSpeedBytesPerSec = totalDlSpeed,
                uploadSpeedBytesPerSec = totalUpSpeed,
                etaSeconds = maxEta,
                totalSizeBytes = totalSize,
                downloadedBytes = totalCompleted,
                seedsCount = maxSeeds,
                seedsTotal = maxSeedsTotal,
                peersCount = maxPeers,
                peersTotal = maxPeersTotal,
                state = aggregateState,
                items = childItems,
                tags = allTags
            )
        )
    }

    private suspend fun ensureAuthenticated(force: Boolean = false) {
        if (config.username.isNullOrBlank() || config.password.isNullOrBlank()) {
            return
        }

        if (sidCookie.get() != null && !force) {
            return
        }

        authMutex.withLock {
            if (sidCookie.get() != null && !force) {
                return
            }

            val baseUrl = config.url.trimEnd('/')
            val loginUrl = "$baseUrl/api/v2/auth/login"

            try {
                val loginResponse =
                    httpClient.submitForm(
                        url = loginUrl,
                        formParameters =
                            Parameters.build {
                                append("username", config.username)
                                append("password", config.password)
                            }
                    )

                val responseBody: String = loginResponse.bodyAsText()
                if (responseBody.trim() == "Fails." || loginResponse.status == HttpStatusCode.Forbidden) {
                    logger.error("qBittorrent authentication rejected for user {}", config.username)
                    return
                }

                val setCookieHeaders = loginResponse.headers.getAll(HttpHeaders.SetCookie)
                val sid = setCookieHeaders?.mapNotNull { extractSidCookie(it) }?.firstOrNull()
                if (sid != null) {
                    sidCookie.set(sid)
                    logger.debug("Successfully authenticated with qBittorrent")
                }
            } catch (e: Exception) {
                logger.warn("qBittorrent authentication request failed: {}", e.message)
            }
        }
    }

    private fun extractSidCookie(setCookie: String): String? {
        val parts = setCookie.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("SID=")) {
                return trimmed
            }
        }
        return null
    }

    private fun mapState(state: String): TorrentState =
        when (state.lowercase()) {
            "downloading", "forceddl" -> TorrentState.DOWNLOADING
            "metadl", "forcedmetadl" -> TorrentState.ALLOCATING_METADATA
            "stalleddl" -> TorrentState.STALLED
            "uploading", "forcedup", "stalledup", "pausedup", "queuedup", "checkingup" -> TorrentState.COMPLETED
            "pauseddl" -> TorrentState.PAUSED
            "queueddl" -> TorrentState.QUEUED
            "checkingdl", "checkingresumedata" -> TorrentState.CHECKING
            else -> TorrentState.UNKNOWN
        }
}
