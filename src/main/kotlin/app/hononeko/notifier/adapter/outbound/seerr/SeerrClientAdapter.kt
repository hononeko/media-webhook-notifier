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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.isSuccess
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Seerr (Overseerr / Jellyseerr) REST client for the admin buttons. Authenticates with the instance API key, which
 * acts as Seerr's admin user, so permission checks happen on our side (`TELEGRAM_ADMIN_USERS`), not in Seerr.
 *
 * With *Settings → Network → CSRF Protection* on, Seerr refuses every POST without a CSRF token, API key or not.
 * The client then fetches the token pair once, caches it, and refreshes it when Seerr rejects it.
 */
class SeerrClientAdapter(
    private val config: SeerrConfig,
    engine: HttpClientEngine? = null,
    private val timeoutMillis: Long = 5000L
) : MediaRequestManagerPort,
    AutoCloseable {
    companion object {
        private const val MAX_DETAILS = 200
        private const val CSRF_COOKIE = "_csrf"
        private const val TOKEN_COOKIE = "XSRF-TOKEN"
        private const val CSRF_HEADER = "X-XSRF-TOKEN"
    }

    private val logger = LoggerFactory.getLogger(SeerrClientAdapter::class.java)

    private val httpClient =
        if (engine != null) HttpClient(engine) { failFast() } else HttpClient(CIO) { failFast() }

    private val baseUrl = config.url.trimEnd('/')

    // Only set once Seerr has asked for it, so instances without CSRF protection never make the extra request.
    private val csrf = AtomicReference<CsrfToken?>(null)

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
        val reply =
            try {
                val first = send(endpoint, csrf.get())
                if (first.isCsrfRejection()) {
                    // No token yet, or Seerr restarted and rotated its secret: fetch a fresh one and retry once.
                    val token = fetchCsrfToken().also { csrf.set(it) }
                    if (token != null) send(endpoint, token) else first
                } else {
                    first
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not just IOException: CIO reports an unknown host as UnresolvedAddressException.
                logger.warn("Seerr {} {} {} failed: {}", resource, id, status, e.message)
                return Either.Left(DomainError.RequestManagerError.ConnectionFailed(baseUrl, e))
            }
        return when {
            reply.status.isSuccess() -> {
                logger.info("Seerr {} {} set to {}", resource, id, status)
                Either.Right(Unit)
            }
            reply.status == HttpStatusCode.NotFound ->
                Either.Left(DomainError.RequestManagerError.NotFound(resource, id))
            else ->
                Either.Left(
                    DomainError.RequestManagerError.Rejected(reply.status.value, reply.body.take(MAX_DETAILS))
                )
        }
    }

    private class Reply(
        val status: HttpStatusCode,
        val body: String
    ) {
        fun isCsrfRejection(): Boolean = status == HttpStatusCode.Forbidden && body.contains("csrf", ignoreCase = true)
    }

    /** Seerr's double-submit pair: the `_csrf` secret cookie and the token derived from it. */
    private class CsrfToken(
        val secret: String,
        val token: String
    )

    private suspend fun send(
        endpoint: String,
        csrfToken: CsrfToken?
    ): Reply {
        val response =
            httpClient.post(endpoint) {
                header("X-Api-Key", config.apiKey)
                if (csrfToken != null) {
                    // Sent by hand: Seerr marks the cookies Secure, and a cookie jar won't send those over http.
                    header(HttpHeaders.Cookie, "$CSRF_COOKIE=${csrfToken.secret}")
                    header(CSRF_HEADER, csrfToken.token)
                }
            }
        return Reply(response.status, runCatching { response.bodyAsText() }.getOrDefault(""))
    }

    /** Any Seerr response sets both cookies when CSRF protection is on; `/api/v1/status` is public and cheap. */
    private suspend fun fetchCsrfToken(): CsrfToken? {
        val cookies =
            httpClient
                .get("$baseUrl/api/v1/status")
                .headers
                .getAll(HttpHeaders.SetCookie)
                .orEmpty()
                .map { parseServerSetCookieHeader(it) }
        val secret = cookies.firstOrNull { it.name == CSRF_COOKIE }?.value
        // Express URL-encodes cookie values; the header must carry the decoded token.
        val token = cookies.firstOrNull { it.name == TOKEN_COOKIE }?.value?.decodeURLPart()
        if (secret.isNullOrBlank() || token.isNullOrBlank()) {
            logger.warn("Seerr rejected a request for its CSRF token, but /api/v1/status set no token cookies")
            return null
        }
        logger.info("Fetched a Seerr CSRF token (CSRF protection is on in Seerr)")
        return CsrfToken(secret, token)
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
