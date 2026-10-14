package app.hononeko.notifier.adapter.outbound.seerr

import app.hononeko.notifier.config.SeerrConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.port.outbound.MediaRequestManagerPort
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Seerr (Overseerr / Jellyseerr) REST client for the admin buttons. Authenticates with the instance API key, which
 * acts as Seerr's admin user, so permission checks happen on our side (`TELEGRAM_ADMIN_USERS`), not in Seerr.
 */
class SeerrClientAdapter(
    private val config: SeerrConfig,
    engine: HttpClientEngine? = null,
    private val timeoutMillis: Long = 5000L
) : MediaRequestManagerPort,
    AutoCloseable {
    companion object {
        private const val MAX_DETAILS = 200
    }

    private val logger = LoggerFactory.getLogger(SeerrClientAdapter::class.java)

    private val httpClient =
        if (engine != null) HttpClient(engine) { failFast() } else HttpClient(CIO) { failFast() }

    private val baseUrl = config.url.trimEnd('/')

    override suspend fun approveRequest(requestId: Long) = post("request", requestId, "approve")

    override suspend fun declineRequest(requestId: Long) = post("request", requestId, "decline")

    override suspend fun resolveIssue(issueId: Long) = post("issue", issueId, "resolved")

    @Suppress("TooGenericExceptionCaught")
    private suspend fun post(
        resource: String,
        id: Long,
        status: String
    ): Either<DomainError.RequestManagerError, Unit> {
        val endpoint = "$baseUrl/api/v1/$resource/$id/$status"
        val response =
            try {
                httpClient.post(endpoint) { header("X-Api-Key", config.apiKey) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not just IOException: CIO reports an unknown host as UnresolvedAddressException.
                logger.warn("Seerr {} {} {} failed: {}", resource, id, status, e.message)
                return Either.Left(DomainError.RequestManagerError.ConnectionFailed(baseUrl, e))
            }
        return when {
            response.status.isSuccess() -> {
                logger.info("Seerr {} {} set to {}", resource, id, status)
                Either.Right(Unit)
            }
            response.status == HttpStatusCode.NotFound ->
                Either.Left(DomainError.RequestManagerError.NotFound(resource, id))
            else -> {
                val details = runCatching { response.bodyAsText().take(MAX_DETAILS) }.getOrDefault("")
                Either.Left(DomainError.RequestManagerError.Rejected(response.status.value, details))
            }
        }
    }

    override fun close() = httpClient.close()

    private fun HttpClientConfig<*>.failFast() {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }
}
