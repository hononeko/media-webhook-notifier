package app.hononeko.notifier.adapter.outbound.mediaserver

import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.port.outbound.MediaServerHealthPort
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Checks that the media server answers, using endpoints that need no token: Plex's `/identity` and Jellyfin's
 * `/System/Info/Public`. Any other server type is checked at its base URL.
 */
class MediaServerHealthAdapter(
    config: MediaServerConfig,
    engine: HttpClientEngine? = null,
    private val timeoutMillis: Long = 5000L
) : MediaServerHealthPort,
    AutoCloseable {
    private val httpClient =
        if (engine != null) HttpClient(engine) { failFast() } else HttpClient(CIO) { failFast() }

    val healthUrl: String =
        config.url.trimEnd('/') +
            when (config.type.trim().lowercase()) {
                "plex" -> "/identity"
                "jellyfin" -> "/System/Info/Public"
                else -> "/"
            }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun checkHealth(): Either<DomainError.MediaServerError, Unit> =
        try {
            val status = httpClient.get(healthUrl).status
            if (status.isSuccess()) {
                Either.Right(Unit)
            } else {
                Either.Left(DomainError.MediaServerError.Unhealthy(healthUrl, status.value))
            }
        } catch (e: CancellationException) {
            // A timeout may surface as a CancellationException; only a cancelled caller ends the check.
            currentCoroutineContext().ensureActive()
            unreachable(e)
        } catch (e: Exception) {
            // Connection refused, DNS failure, timeout: the server is unreachable either way.
            unreachable(e)
        }

    private fun unreachable(e: Exception): Either<DomainError.MediaServerError, Unit> {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unreachable"
        return Either.Left(DomainError.MediaServerError.Unreachable(healthUrl, reason))
    }

    override fun close() = httpClient.close()

    private fun HttpClientConfig<*>.failFast() {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }
}
