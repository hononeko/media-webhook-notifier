package app.hononeko.notifier.adapter.outbound.seerr

import app.hononeko.notifier.config.SeerrConfig
import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
    fun `a Seerr that never answers fails fast as ConnectionFailed`() =
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
}
