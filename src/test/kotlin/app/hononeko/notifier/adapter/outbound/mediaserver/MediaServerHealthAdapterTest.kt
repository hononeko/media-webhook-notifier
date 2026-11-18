package app.hononeko.notifier.adapter.outbound.mediaserver

import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.domain.error.DomainError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MediaServerHealthAdapterTest {
    private val requested = mutableListOf<String>()

    private fun adapter(
        type: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = MediaServerHealthAdapter(
        MediaServerConfig(type = type, url = "http://plex-ui:32400/"),
        MockEngine { request ->
            requested += request.url.toString()
            respond("{}", status)
        }
    )

    @Test
    fun `plex is checked at identity`() =
        runTest {
            assertTrue(adapter("plex").checkHealth().isRight())
            assertEquals(listOf("http://plex-ui:32400/identity"), requested)
        }

    @Test
    fun `jellyfin is checked at its public system info`() =
        runTest {
            assertTrue(adapter("Jellyfin").checkHealth().isRight())
            assertEquals(listOf("http://plex-ui:32400/System/Info/Public"), requested)
        }

    @Test
    fun `an error status is unhealthy`() =
        runTest {
            val failure = adapter("plex", HttpStatusCode.ServiceUnavailable).checkHealth().leftOrNull()
            assertIs<DomainError.MediaServerError.Unhealthy>(failure)
            assertEquals(503, failure.httpStatus)
        }

    @Test
    fun `a connection failure is unreachable`() =
        runTest {
            val adapter =
                MediaServerHealthAdapter(
                    MediaServerConfig(type = "plex", url = "http://plex-ui:32400"),
                    MockEngine { throw IOException("Connection refused") }
                )
            val failure = adapter.checkHealth().leftOrNull()
            assertIs<DomainError.MediaServerError.Unreachable>(failure)
            assertEquals("Connection refused", failure.reason)
        }
}
