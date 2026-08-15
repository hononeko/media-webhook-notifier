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
        if (normalizedHash.isBlank()) {
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

    private suspend fun executeRequestWithAuth(url: String): HttpResponse =
        httpClient.get(url) {
            val cookie = sidCookie.get()
            if (!cookie.isNullOrBlank()) {
                header(HttpHeaders.Cookie, cookie)
            }
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

        val torrentDto =
            torrentList.firstOrNull { it.hash.equals(hash, ignoreCase = true) }
                ?: return Either.Right(null)

        val progressPercent = (torrentDto.progress * 100.0).coerceIn(0.0, 100.0)
        val state = mapState(torrentDto.state)

        return Either.Right(
            TorrentProgress(
                hash = torrentDto.hash,
                name = torrentDto.name ?: "Unknown",
                progressPercent = progressPercent,
                progressRatio = torrentDto.progress,
                downloadSpeedBytesPerSec = torrentDto.dlspeed,
                uploadSpeedBytesPerSec = torrentDto.upspeed,
                etaSeconds = torrentDto.eta,
                totalSizeBytes = torrentDto.totalSize,
                downloadedBytes = torrentDto.completed,
                seedsCount = torrentDto.numSeeds,
                seedsTotal = torrentDto.numComplete,
                peersCount = torrentDto.numLeechs,
                peersTotal = torrentDto.numIncomplete,
                state = state
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
