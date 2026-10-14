package app.hononeko.notifier.adapter.outbound.seerr

import app.hononeko.notifier.config.SeerrConfig
import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SeerrClientAdapterTest {
    private val config = SeerrConfig(url = "https://seerr.example.com/", apiKey = "k3y")
    private val requests = mutableListOf<HttpRequestData>()

    private fun adapter(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}"
    ) = SeerrClientAdapter(
        config,
        MockEngine { request ->
            requests += request
            respond(body, status)
        }
    )

    @Test
    fun `approve, decline and resolve POST to the Seerr API with the API key`() =
        runTest {
            val seerr = adapter()

            assertEquals(Either.Right(Unit), seerr.approveRequest(12))
            assertEquals(Either.Right(Unit), seerr.declineRequest(13))
            assertEquals(Either.Right(Unit), seerr.resolveIssue(7))

            assertEquals(
                listOf(
                    "https://seerr.example.com/api/v1/request/12/approve",
                    "https://seerr.example.com/api/v1/request/13/decline",
                    "https://seerr.example.com/api/v1/issue/7/resolved"
                ),
                requests.map { it.url.toString() }
            )
            requests.forEach {
                assertEquals(HttpMethod.Post, it.method)
                assertEquals("k3y", it.headers["X-Api-Key"])
            }
        }

    @Test
    fun `404 is NotFound with the resource and id`() =
        runTest {
            assertEquals(
                Either.Left(DomainError.RequestManagerError.NotFound("issue", 7)),
                adapter(HttpStatusCode.NotFound).resolveIssue(7)
            )
        }

    @Test
    fun `other errors are Rejected with status and body`() =
        runTest {
            assertEquals(
                Either.Left(DomainError.RequestManagerError.Rejected(403, """{"message":"forbidden"}""")),
                adapter(HttpStatusCode.Forbidden, """{"message":"forbidden"}""").approveRequest(1)
            )
        }

    @Test
    fun `network failure is ConnectionFailed`() =
        runTest {
            val seerr = SeerrClientAdapter(config, MockEngine { throw IOException("refused") })

            val error = assertIs<Either.Left<DomainError.RequestManagerError>>(seerr.approveRequest(1)).value

            assertIs<DomainError.RequestManagerError.ConnectionFailed>(error)
            assertEquals("https://seerr.example.com", error.url)
        }

    @Test
    fun `a Seerr that never answers fails fast as ConnectionFailed`(): Unit =
        runBlocking {
            val seerr =
                SeerrClientAdapter(
                    config,
                    MockEngine {
                        delay(10_000)
                        respond("{}")
                    },
                    timeoutMillis = 200
                )

            val error = assertIs<Either.Left<DomainError.RequestManagerError>>(seerr.approveRequest(1)).value

            assertIs<DomainError.RequestManagerError.ConnectionFailed>(error)
        }

    /**
     * Seerr with CSRF protection on, the way csurf behaves: GET /api/v1/status sets the `_csrf` secret and a
     * URL-encoded `XSRF-TOKEN`; a POST is only accepted with the matching secret cookie and decoded token header.
     */
    private inner class CsrfSeerr {
        var secret = "s3cr3t"
        val token get() = "tok/$secret" // a '/' so the cookie value is URL-encoded, as Express does
        val seen = mutableListOf<String>()

        val engine =
            MockEngine { request ->
                seen += "${request.method.value} ${request.url.encodedPath}"
                if (request.method == HttpMethod.Get) {
                    respond(
                        "{}",
                        HttpStatusCode.OK,
                        headers {
                            append(HttpHeaders.SetCookie, "_csrf=$secret; Path=/; HttpOnly; Secure; SameSite=Strict")
                            append(
                                HttpHeaders.SetCookie,
                                "XSRF-TOKEN=${token.encodeURLParameter()}; Path=/; Secure; SameSite=Strict"
                            )
                        }
                    )
                } else if (request.headers[HttpHeaders.Cookie] == "_csrf=$secret" &&
                    request.headers["X-XSRF-TOKEN"] == token
                ) {
                    respond("{}", HttpStatusCode.OK)
                } else {
                    respond("""{"message":"invalid csrf token"}""", HttpStatusCode.Forbidden)
                }
            }
    }

    @Test
    fun `with CSRF protection on, fetches the token once and reuses it`() =
        runTest {
            val seerr = CsrfSeerr()
            val client = SeerrClientAdapter(config, seerr.engine)

            assertEquals(Either.Right(Unit), client.approveRequest(12))
            assertEquals(Either.Right(Unit), client.declineRequest(13))

            assertEquals(
                listOf(
                    "POST /api/v1/request/12/approve",
                    "GET /api/v1/status",
                    "POST /api/v1/request/12/approve",
                    "POST /api/v1/request/13/decline"
                ),
                seerr.seen
            )
        }

    @Test
    fun `a token Seerr no longer accepts is refreshed once`() =
        runTest {
            val seerr = CsrfSeerr()
            val client = SeerrClientAdapter(config, seerr.engine)
            client.approveRequest(12)
            seerr.secret = "rotated" // Seerr restarted
            seerr.seen.clear()

            assertEquals(Either.Right(Unit), client.resolveIssue(7))
            assertEquals(
                listOf("POST /api/v1/issue/7/resolved", "GET /api/v1/status", "POST /api/v1/issue/7/resolved"),
                seerr.seen
            )
        }

    @Test
    fun `a CSRF rejection without token cookies fails as Rejected`() =
        runTest {
            val client =
                SeerrClientAdapter(
                    config,
                    MockEngine { request ->
                        requests += request
                        if (request.method == HttpMethod.Get) {
                            respond("{}")
                        } else {
                            respond("""{"message":"invalid csrf token"}""", HttpStatusCode.Forbidden)
                        }
                    }
                )

            assertEquals(
                Either.Left(DomainError.RequestManagerError.Rejected(403, """{"message":"invalid csrf token"}""")),
                client.approveRequest(1)
            )
            assertEquals(2, requests.size, "one POST, one token fetch, no further retries")
        }

    @Test
    fun `a 403 that isn't about CSRF is not retried`() =
        runTest {
            val seerr = adapter(HttpStatusCode.Forbidden, """{"message":"You do not have permission"}""")

            assertIs<Either.Left<DomainError.RequestManagerError.Rejected>>(seerr.approveRequest(1))
            assertEquals(1, requests.size)
        }
}
