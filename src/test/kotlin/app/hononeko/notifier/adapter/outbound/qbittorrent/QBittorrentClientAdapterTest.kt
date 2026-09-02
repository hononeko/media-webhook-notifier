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
import kotlin.test.assertFalse
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
    fun `should aggregate multiple torrents when querying multiple hashes`() =
        runTest {
            val combinedHash = "hash1|hash2"
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/torrents/info" -> {
                            val jsonResponse =
                                """
                                [
                                  {
                                    "hash": "hash1",
                                    "name": "Love.Is.Blind.UK.S03E01",
                                    "progress": 1.0,
                                    "dlspeed": 0,
                                    "upspeed": 500000,
                                    "eta": 0,
                                    "total_size": 2640000000,
                                    "completed": 2640000000,
                                    "state": "uploading",
                                    "num_seeds": 10,
                                    "num_complete": 20,
                                    "num_leechs": 2,
                                    "num_incomplete": 5
                                  },
                                  {
                                    "hash": "hash2",
                                    "name": "Love.Is.Blind.UK.S03E02",
                                    "progress": 0.5,
                                    "dlspeed": 10000000,
                                    "upspeed": 100000,
                                    "eta": 132,
                                    "total_size": 2640000000,
                                    "completed": 1320000000,
                                    "state": "downloading",
                                    "num_seeds": 8,
                                    "num_complete": 15,
                                    "num_leechs": 3,
                                    "num_incomplete": 4
                                  }
                                ]
                                """.trimIndent()
                            respond(
                                content = jsonResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config = QBittorrentConfig(url = "http://localhost:8080")
            val adapter = QBittorrentClientAdapter(config, mockEngine)

            val result = adapter.getTorrentProgress(combinedHash)
            assertTrue(result.isRight())

            val progress = (result as Either.Right).value
            assertNotNull(progress)
            assertEquals(combinedHash, progress.hash)
            assertEquals(5280000000L, progress.totalSizeBytes)
            assertEquals(3960000000L, progress.downloadedBytes)
            assertEquals(75.0, progress.progressPercent, 0.001)
            assertEquals(10000000L, progress.downloadSpeedBytesPerSec)
            assertEquals(600000L, progress.uploadSpeedBytesPerSec)
            assertEquals(132L, progress.etaSeconds)
            assertEquals(TorrentState.DOWNLOADING, progress.state)
            assertEquals(2, progress.items.size)
            assertEquals("hash1", progress.items[0].hash)
            assertEquals(100.0, progress.items[0].progressPercent, 0.001)
            assertEquals(TorrentState.COMPLETED, progress.items[0].state)
            assertEquals("hash2", progress.items[1].hash)
            assertEquals(50.0, progress.items[1].progressPercent, 0.001)
            assertEquals(TorrentState.DOWNLOADING, progress.items[1].state)
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

    @Test
    fun `should return AuthenticationFailed when login returns Fails`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v2/auth/login") {
                        respond("Fails.", HttpStatusCode.OK)
                    } else {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                QBittorrentConfig(
                    url = "http://localhost:8080",
                    username = "admin",
                    password = "wrongpassword"
                )
            val adapter = QBittorrentClientAdapter(config, mockEngine)
            val result = adapter.getTorrentProgress("hash123")
            assertTrue(result.isLeft())
        }

    @Test
    fun `should return ConnectionFailed on network exception`() =
        runTest {
            val mockEngine =
                MockEngine {
                    throw java.io.IOException("Network down")
                }

            val config = QBittorrentConfig(url = "http://localhost:8080")
            val adapter = QBittorrentClientAdapter(config, mockEngine)
            val result = adapter.getTorrentProgress("hash123")
            assertTrue(result.isLeft())
        }

    @Test
    fun `should handle zero total size and various aggregate states in multi-torrent queries`() =
        runTest {
            val statePairs =
                listOf(
                    listOf("metaDL", "downloading") to TorrentState.DOWNLOADING,
                    listOf("metaDL", "metaDL") to TorrentState.ALLOCATING_METADATA,
                    listOf("checkingDL", "checkingDL") to TorrentState.CHECKING,
                    listOf("stalledDL", "stalledDL") to TorrentState.STALLED,
                    listOf("pausedDL", "pausedDL") to TorrentState.PAUSED,
                    listOf("queuedDL", "queuedDL") to TorrentState.QUEUED,
                    listOf("unknown1", "unknown2") to TorrentState.DOWNLOADING
                )

            for ((mockStates, expectedAggState) in statePairs) {
                val mockEngine =
                    MockEngine {
                        val items =
                            mockStates
                                .mapIndexed { idx, st ->
                                    """{"hash":"h$idx","name":"Item $idx","progress":0.5,"total_size":0,"completed":0,"state":"$st","dlspeed":0,"upspeed":0,"eta":0,"num_seeds":1,"num_complete":1,"num_leechs":1,"num_incomplete":1}"""
                                }.joinToString(",", "[", "]")
                        respond(
                            content = items,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }

                val adapter = QBittorrentClientAdapter(QBittorrentConfig(), mockEngine)
                val result = adapter.getTorrentProgress("h0|h1")
                assertTrue(result.isRight())
                val progress = (result as Either.Right).value
                assertNotNull(progress)
                assertEquals(expectedAggState, progress.state)
                assertEquals(50.0, progress.progressPercent, 0.001)
            }
        }

    @Test
    fun `should fetch active torrents list with tags`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v2/torrents/info" &&
                        request.url.parameters["filter"] == "downloading"
                    ) {
                        val json =
                            """
                            [
                              {
                                "hash": "hash_dl_1",
                                "name": "Show.1",
                                "progress": 0.3,
                                "dlspeed": 5000,
                                "upspeed": 0,
                                "eta": 100,
                                "total_size": 1000,
                                "completed": 300,
                                "state": "downloading",
                                "tags": "tv-sonarr, mwn_msg:123, mwn_photo:1"
                              }
                            ]
                            """.trimIndent()
                        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), mockEngine)
            val result = adapter.getActiveTorrents("downloading")
            assertTrue(result.isRight())
            val list = (result as Either.Right).value
            assertEquals(1, list.size)
            assertEquals("hash_dl_1", list.first().hash)
            assertEquals(listOf("tv-sonarr", "mwn_msg:123", "mwn_photo:1"), list.first().tags)
        }

    @Test
    fun `should add and remove and delete torrent tags successfully`() =
        runTest {
            val addedParams = mutableListOf<String>()
            val removedParams = mutableListOf<String>()
            val deletedParams = mutableListOf<String>()

            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/torrents/addTags" -> {
                            addedParams.add(request.body.toString())
                            respond("Ok.", HttpStatusCode.OK)
                        }
                        "/api/v2/torrents/removeTags" -> {
                            removedParams.add(request.body.toString())
                            respond("Ok.", HttpStatusCode.OK)
                        }
                        "/api/v2/torrents/deleteTags" -> {
                            deletedParams.add(request.body.toString())
                            respond("Ok.", HttpStatusCode.OK)
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), mockEngine)

            val addResult = adapter.addTorrentTags("hash1", listOf("mwn_msg:100", "mwn_photo:1"))
            assertTrue(addResult.isRight())

            val removeResult = adapter.removeTorrentTags("hash1", listOf("mwn_msg:100", "mwn_photo:1"))
            assertTrue(removeResult.isRight())

            val deleteResult = adapter.deleteTags(listOf("mwn_msg:100"))
            assertTrue(deleteResult.isRight())

            // Blank hash / empty tags fast return
            val blankAdd = adapter.addTorrentTags("   ", listOf("tag"))
            assertTrue(blankAdd.isRight())
            val emptyTagsAdd = adapter.addTorrentTags("hash1", emptyList())
            assertTrue(emptyTagsAdd.isRight())
            val emptyTagsDelete = adapter.deleteTags(emptyList())
            assertTrue(emptyTagsDelete.isRight())
            val blankTagsDelete = adapter.deleteTags(listOf("   "))
            assertTrue(blankTagsDelete.isRight())
        }

    @Test
    fun `should handle error responses in active torrents and tag mutations`() =
        runTest {
            val failingEngine =
                MockEngine { _ ->
                    respond("Server Error", HttpStatusCode.InternalServerError)
                }
            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), failingEngine)

            val activeResult = adapter.getActiveTorrents("downloading")
            assertTrue(activeResult.isLeft())

            val invalidJsonEngine =
                MockEngine { _ ->
                    respond(
                        "Invalid json content",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            val invalidJsonAdapter = QBittorrentClientAdapter(QBittorrentConfig(), invalidJsonEngine)
            val invalidResult = invalidJsonAdapter.getActiveTorrents("downloading")
            assertTrue(invalidResult.isLeft())

            val exceptionEngine =
                MockEngine { _ ->
                    throw RuntimeException("Network crash")
                }
            val exceptionAdapter = QBittorrentClientAdapter(QBittorrentConfig(), exceptionEngine)
            val tagResult = exceptionAdapter.addTorrentTags("hash1", listOf("tag1"))
            assertTrue(tagResult.isLeft())
        }

    @Test
    fun `should validate torrent hashes and reject invalid formats`() {
        assertTrue(QBittorrentClientAdapter.isValidHash("4a12b3c4d5e6f7a8b9c01234567890abcdef1234"))
        assertTrue(QBittorrentClientAdapter.isValidHash("hash123"))
        assertTrue(QBittorrentClientAdapter.isValidHash("test_hash-1"))
        assertTrue(QBittorrentClientAdapter.isValidHash("hash1|hash2"))
        assertTrue(QBittorrentClientAdapter.isValidHash("h1|h2|h3"))

        assertFalse(QBittorrentClientAdapter.isValidHash(""))
        assertFalse(QBittorrentClientAdapter.isValidHash("   "))
        assertFalse(QBittorrentClientAdapter.isValidHash("hash with space"))
        assertFalse(QBittorrentClientAdapter.isValidHash("&filter=all"))
        assertFalse(QBittorrentClientAdapter.isValidHash("hash?query=1"))
        assertFalse(QBittorrentClientAdapter.isValidHash("|hash"))
        assertFalse(QBittorrentClientAdapter.isValidHash("hash|"))
        assertFalse(QBittorrentClientAdapter.isValidHash("../etc/passwd"))
    }

    @Test
    fun `should return null without network request when torrent hash format is invalid`() =
        runTest {
            val failingEngine =
                MockEngine { _ ->
                    respond("Should not be called", HttpStatusCode.InternalServerError)
                }
            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), failingEngine)
            val result = adapter.getTorrentProgress("&filter=all")
            assertTrue(result.isRight())
            assertNull((result as Either.Right).value)
        }

    @Test
    fun `should skip tag mutation when torrent hash format is invalid`() =
        runTest {
            val failingEngine =
                MockEngine { _ ->
                    respond("Should not be called", HttpStatusCode.InternalServerError)
                }
            val adapter = QBittorrentClientAdapter(QBittorrentConfig(), failingEngine)
            val addResult = adapter.addTorrentTags("invalid hash with space", listOf("mwn_test"))
            assertTrue(addResult.isRight())

            val removeResult = adapter.removeTorrentTags("&hashes=all", listOf("mwn_test"))
            assertTrue(removeResult.isRight())
        }
}
