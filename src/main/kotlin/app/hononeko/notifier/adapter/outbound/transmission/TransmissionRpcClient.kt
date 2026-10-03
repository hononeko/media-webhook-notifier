package app.hononeko.notifier.adapter.outbound.transmission

import app.hononeko.notifier.config.TransmissionConfig
import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport for the Transmission RPC protocol (`POST <rpc-url>` with `{method, arguments}` envelopes).
 *
 * Uses the legacy (pre-JSON-RPC 2.0) dialect, which every Transmission release from 2.x through 4.1.x
 * understands. The CSRF session id handed out with `409 Conflict` responses is cached and reused across calls.
 */
internal class TransmissionRpcClient(
    private val config: TransmissionConfig,
    engine: HttpClientEngine? = null
) {
    companion object {
        const val SESSION_ID_HEADER = "X-Transmission-Session-Id"
        private const val RPC_PATH = "/transmission/rpc"
        private const val TIMEOUT_MILLIS = 5_000L
        private const val RESULT_SUCCESS = "success"

        /** Accepts either a base URL (`http://host:9091`) or a full RPC endpoint (`.../transmission/rpc`). */
        fun resolveEndpoint(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.endsWith("/rpc")) trimmed else "$trimmed$RPC_PATH"
        }
    }

    private val logger = LoggerFactory.getLogger(TransmissionRpcClient::class.java)
    private val endpoint = resolveEndpoint(config.url)
    private val sessionId = AtomicReference<String?>(null)

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val httpClient =
        if (engine != null) {
            HttpClient(engine) { installTimeouts() }
        } else {
            HttpClient(CIO) { installTimeouts() }
        }

    /** Runs `torrent-get`. A null [ids] list fetches every torrent, so callers must only pass null for reads. */
    suspend fun torrentGet(
        ids: List<String>?,
        fields: List<String>
    ): Either<DomainError.TorrentClientError, List<TransmissionTorrentDto>> =
        either {
            val arguments =
                call(
                    TransmissionMethod.TORRENT_GET,
                    buildJsonObject {
                        if (ids != null) putIds(ids)
                        putJsonArray("fields") { fields.forEach { add(it) } }
                    }
                ).bind()
            try {
                json.decodeFromJsonElement(TorrentGetArguments.serializer(), arguments).torrents
            } catch (e: SerializationException) {
                logger.warn("Failed to parse Transmission torrent-get arguments: {}", e.message)
                raise(DomainError.TorrentClientError.InvalidResponse(e.message ?: "Invalid torrent-get arguments"))
            }
        }

    suspend fun call(
        method: String,
        arguments: JsonObject
    ): Either<DomainError.TorrentClientError, JsonObject> {
        val body = json.encodeToString(RpcRequest.serializer(), RpcRequest(method, arguments))
        return try {
            val response = post(body)
            val finalResponse =
                if (response.status == HttpStatusCode.Conflict && adoptSessionId(response)) {
                    post(body)
                } else {
                    response
                }
            decode(method, finalResponse)
        } catch (e: IOException) {
            logger.debug("Transmission RPC '{}' failed: {}", method, e.message)
            Either.Left(DomainError.TorrentClientError.ConnectionFailed(config.url, e))
        } catch (e: UnresolvedAddressException) {
            logger.debug("Transmission RPC '{}' failed to resolve host: {}", method, e.message)
            Either.Left(DomainError.TorrentClientError.ConnectionFailed(config.url, e))
        }
    }

    private suspend fun post(body: String): HttpResponse =
        httpClient.post(endpoint) {
            sessionId.get()?.let { header(SESSION_ID_HEADER, it) }
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                basicAuth(config.username, config.password)
            }
            setBody(TextContent(body, ContentType.Application.Json))
        }

    private fun adoptSessionId(response: HttpResponse): Boolean {
        val newSessionId = response.headers[SESSION_ID_HEADER]
        if (newSessionId.isNullOrBlank()) {
            logger.warn("Transmission returned 409 Conflict without a {} header", SESSION_ID_HEADER)
            return false
        }
        sessionId.set(newSessionId)
        logger.debug("Negotiated new Transmission RPC session id")
        return true
    }

    private suspend fun decode(
        method: String,
        response: HttpResponse
    ): Either<DomainError.TorrentClientError, JsonObject> =
        when {
            response.status == HttpStatusCode.Unauthorized ->
                Either.Left(
                    DomainError.TorrentClientError.AuthenticationFailed("Transmission rejected the credentials")
                )
            response.status == HttpStatusCode.Forbidden ->
                Either.Left(
                    DomainError.TorrentClientError.AuthenticationFailed(
                        "Transmission refused the request; check rpc-whitelist / rpc-host-whitelist"
                    )
                )
            !response.status.isSuccess() ->
                Either.Left(
                    DomainError.TorrentClientError.InvalidResponse(
                        "HTTP ${response.status.value}: ${response.status.description}"
                    )
                )
            else -> parseEnvelope(method, response.bodyAsText())
        }

    private fun parseEnvelope(
        method: String,
        body: String
    ): Either<DomainError.TorrentClientError, JsonObject> =
        either {
            val envelope =
                try {
                    json.decodeFromString(RpcResponse.serializer(), body)
                } catch (e: SerializationException) {
                    logger.warn("Failed to parse Transmission '{}' response: {}", method, e.message)
                    raise(DomainError.TorrentClientError.InvalidResponse(e.message ?: "Invalid JSON"))
                }
            ensure(envelope.result == RESULT_SUCCESS) {
                DomainError.TorrentClientError.InvalidResponse("Transmission '$method' failed: ${envelope.result}")
            }
            envelope.arguments ?: JsonObject(emptyMap())
        }

    private fun HttpClientConfig<*>.installTimeouts() {
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MILLIS
            connectTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }
    }
}

internal object TransmissionMethod {
    const val TORRENT_GET = "torrent-get"
    const val TORRENT_SET = "torrent-set"
    const val TORRENT_START = "torrent-start"
    const val TORRENT_STOP = "torrent-stop"
    const val TORRENT_REMOVE = "torrent-remove"
}

internal fun JsonObjectBuilder.putIds(ids: List<String>) {
    putJsonArray("ids") { ids.forEach { add(it) } }
}
