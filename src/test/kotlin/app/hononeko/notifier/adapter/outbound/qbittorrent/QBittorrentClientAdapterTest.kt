package app.hononeko.notifier.adapter.outbound.qbittorrent

import app.hononeko.notifier.config.QBittorrentConfig
import app.hononeko.notifier.domain.model.TorrentState
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QBittorrentClientAdapterTest {
    @Test
    fun `should authenticate and fetch torrent progress successfully`() =
        runTest {
            val hash = "b2f0a1b2c3d4e5f6"
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/auth/login" -> {
                            respond(
                                content = "Ok.",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.SetCookie, "SID=test_session_12345; HttpOnly; path=/")
                            )
                        }
                        "/api/v2/torrents/info" -> {
                            val cookie = request.headers[HttpHeaders.Cookie]
                            if (cookie != "SID=test_session_12345") {
                                respond("Forbidden", HttpStatusCode.Forbidden)
                            } else {
                                val jsonResponse =
                                    """
                                    [
                                      {
                                        "hash": "$hash",
                                        "name": "Severance.S02E01.1080p.WEB-DL",
                                        "progress": 0.452,
                                        "dlspeed": 15728640,
                                        "upspeed": 10240,
                                        "eta": 120,
                                        "total_size": 1500000000,
                                        "completed": 678000000,
                                        "state": "downloading",
                                        "num_seeds": 45,
                                        "num_complete": 120,
                                        "num_leechs": 5,
                                        "num_incomplete": 12
                                      }
                                    ]
                                    """.trimIndent()
                                respond(
                                    content = jsonResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                QBittorrentConfig(
                    url = "http://localhost:8080",
                    username = "admin",
                    password = "password"
                )
            val adapter = QBittorrentClientAdapter(config, mockEngine)

            val result = adapter.getTorrentProgress(hash)
            assertTrue(result.isRight())

            val progress = (result as Either.Right).value
            assertNotNull(progress)
            assertEquals(hash, progress.hash)
            assertEquals("Severance.S02E01.1080p.WEB-DL", progress.name)
            assertEquals(45.2, progress.progressPercent, 0.001)
            assertEquals(15728640L, progress.downloadSpeedBytesPerSec)
            assertEquals(120L, progress.etaSeconds)
            assertEquals(TorrentState.DOWNLOADING, progress.state)
            assertEquals(45, progress.seedsCount)
            assertEquals(120, progress.seedsTotal)
        }

    @Test
    fun `should re-authenticate when receiving 403 Forbidden on expired session`() =
        runTest {
            val hash = "hash_reauth"
            val loginAttempts = AtomicInteger(0)
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/auth/login" -> {
                            val attempt = loginAttempts.incrementAndGet()
                            respond(
                                content = "Ok.",
                                status = HttpStatusCode.OK,
                                headers =
                                    headersOf(
                                        HttpHeaders.SetCookie,
                                        "SID=reauth_cookie_$attempt; HttpOnly; path=/"
                                    )
                            )
                        }
                        "/api/v2/torrents/info" -> {
                            val cookie = request.headers[HttpHeaders.Cookie]
                            if (cookie == "SID=reauth_cookie_2") {
                                respond(
                                    content = """[{"hash":"$hash","name":"Test","progress":1.0,"state":"uploading"}]""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            } else {
                                respond("Forbidden", HttpStatusCode.Forbidden)
                            }
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                QBittorrentConfig(
                    url = "http://localhost:8080",
                    username = "admin",
                    password = "password"
                )
            val adapter = QBittorrentClientAdapter(config, mockEngine)

            val result = adapter.getTorrentProgress(hash)
            assertTrue(result.isRight())
            val progress = (result as Either.Right).value
            assertNotNull(progress)
            assertEquals(TorrentState.COMPLETED, progress.state)
        }

    @Test
    fun `should map various torrent states correctly`() =
        runTest {
            val states =
                mapOf(
                    "stalledDL" to TorrentState.STALLED,
                    "metaDL" to TorrentState.ALLOCATING_METADATA,
                    "pausedDL" to TorrentState.PAUSED,
                    "queuedDL" to TorrentState.QUEUED,
                    "checkingDL" to TorrentState.CHECKING,
                    "someOther" to TorrentState.UNKNOWN
                )

            for ((stateStr, expectedState) in states) {
                val mockEngine =
                    MockEngine {
                        respond(
                            content =
                                """
                                [{"hash":"test_hash","name":"StateTest","progress":0.2,"state":"$stateStr"}]
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }

                val adapter = QBittorrentClientAdapter(QBittorrentConfig(), mockEngine)
                val result = adapter.getTorrentProgress("test_hash")
                assertTrue(result.isRight())
                assertEquals(expectedState, (result as Either.Right).value?.state)
            }
        }

    @Test
    fun `should return null when torrent hash is blank`() =
        runTest {
            val adapter = QBittorrentClientAdapter(QBittorrentConfig())
            val result = adapter.getTorrentProgress("   ")
            assertTrue(result.isRight())
            assertNull((result as Either.Right).value)
        }

    @Test
    fun `should return null when torrent not found in client`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v2/torrents/info") {
                        respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config = QBittorrentConfig(url = "http://localhost:8080")
            val adapter = QBittorrentClientAdapter(config, mockEngine)

            val result = adapter.getTorrentProgress("nonexistent")
            assertTrue(result.isRight())
            assertNull((result as Either.Right).value)
        }

    @Test
    fun `should return InvalidResponse on malformed json`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), mockEngine)
            val result = adapter.getTorrentProgress("hash123")
            assertTrue(result.isLeft())
        }

    @Test
    fun `should return connection error on server 500 error`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond("Internal Server Error", HttpStatusCode.InternalServerError)
                }

            val config = QBittorrentConfig(url = "http://localhost:8080")
            val adapter = QBittorrentClientAdapter(config, mockEngine)

            val result = adapter.getTorrentProgress("hash123")
            assertTrue(result.isLeft())
        }
}
