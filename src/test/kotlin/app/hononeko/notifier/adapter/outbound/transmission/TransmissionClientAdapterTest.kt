package app.hononeko.notifier.adapter.outbound.transmission

import app.hononeko.notifier.config.TransmissionConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.DownloadState
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransmissionClientAdapterTest {
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)

    private fun HttpRequestData.rpcBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    private fun JsonObject.arguments(): JsonObject = getValue("arguments").jsonObject

    private fun JsonObject.stringList(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun MockRequestHandleScope.respondRpc(
        arguments: String = "{}",
        result: String = "success"
    ): HttpResponseData =
        respond(
            content = """{"result": "$result", "arguments": $arguments}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    private fun torrentsJson(vararg torrents: String): String = """{"torrents": [${torrents.joinToString(",")}]}"""

    private fun torrentJson(
        hash: String,
        name: String = "Severance.S02E01.1080p.WEB-DL",
        percentDone: Double = 0.452,
        status: Int = 4,
        sizeWhenDone: Long = 1_500_000_000,
        leftUntilDone: Long = 822_000_000,
        labels: List<String> = emptyList(),
        extra: String = ""
    ): String =
        """
        {
          "id": 7,
          "hashString": "$hash",
          "name": "$name",
          "percentDone": $percentDone,
          "rateDownload": 15728640,
          "rateUpload": 10240,
          "eta": 120,
          "status": $status,
          "peersConnected": 50,
          "peersSendingToUs": 45,
          "sizeWhenDone": $sizeWhenDone,
          "leftUntilDone": $leftUntilDone,
          "labels": [${labels.joinToString(",") { "\"$it\"" }}]
          $extra
        }
        """.trimIndent()

    @Test
    fun `should negotiate session id on 409 and reuse it across requests`() =
        runTest {
            val sessionHeaders = mutableListOf<String?>()
            val mockEngine =
                MockEngine { request ->
                    sessionHeaders.add(request.headers[TransmissionRpcClient.SESSION_ID_HEADER])
                    if (request.headers[TransmissionRpcClient.SESSION_ID_HEADER] != "session-abc") {
                        respond(
                            content = "<h1>409: Conflict</h1>",
                            status = HttpStatusCode.Conflict,
                            headers = headersOf(TransmissionRpcClient.SESSION_ID_HEADER, "session-abc")
                        )
                    } else {
                        respondRpc(torrentsJson(torrentJson(hashA)))
                    }
                }

            val adapter = TransmissionClientAdapter(TransmissionConfig(url = "http://localhost:9091"), mockEngine)

            assertTrue(adapter.getProgress(hashA).isRight())
            assertTrue(adapter.getProgress(hashA).isRight())

            assertEquals(listOf(null, "session-abc", "session-abc"), sessionHeaders)
        }

    @Test
    fun `should renegotiate when cached session id expires`() =
        runTest {
            var currentSession = "session-1"
            val sessionHeaders = mutableListOf<String?>()
            val mockEngine =
                MockEngine { request ->
                    val sent = request.headers[TransmissionRpcClient.SESSION_ID_HEADER]
                    sessionHeaders.add(sent)
                    if (sent != currentSession) {
                        respond(
                            content = "",
                            status = HttpStatusCode.Conflict,
                            headers = headersOf(TransmissionRpcClient.SESSION_ID_HEADER, currentSession)
                        )
                    } else {
                        respondRpc(torrentsJson())
                    }
                }

            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)
            adapter.getProgress(hashA)
            currentSession = "session-2"
            adapter.getProgress(hashA)

            assertEquals(listOf(null, "session-1", "session-1", "session-2"), sessionHeaders)
        }

    @Test
    fun `should return InvalidResponse when 409 carries no session id header`() =
        runTest {
            var requests = 0
            val mockEngine =
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.Conflict)
                }

            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)
            val result = adapter.getProgress(hashA)

            assertIs<DomainError.DownloadClientError.InvalidResponse>(result.leftOrNull())
            assertEquals(1, requests)
        }

    @Test
    fun `should generate torrent-get request with basic auth and requested fields`() =
        runTest {
            var captured: HttpRequestData? = null
            val mockEngine =
                MockEngine { request ->
                    captured = request
                    respondRpc(torrentsJson())
                }

            val config = TransmissionConfig(url = "http://seedbox:9091/", username = "admin", password = "secret")
            val adapter = TransmissionClientAdapter(config, mockEngine)
            adapter.getProgress(hashA.uppercase())

            val request = assertNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/transmission/rpc", request.url.encodedPath)
            val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("admin:secret".toByteArray())
            assertEquals(expectedAuth, request.headers[HttpHeaders.Authorization])

            val body = request.rpcBody()
            assertEquals("torrent-get", body.method())
            assertEquals(listOf(hashA), body.arguments().stringList("ids"))
            val fields = body.arguments().stringList("fields")
            listOf(
                "id",
                "hashString",
                "name",
                "percentDone",
                "rateDownload",
                "eta",
                "status",
                "peersConnected",
                "labels",
                "trackerStats"
            ).forEach { assertTrue(it in fields, "Expected field '$it' in $fields") }
        }

    @Test
    fun `should omit authorization header when no credentials are configured`() =
        runTest {
            var captured: HttpRequestData? = null
            val mockEngine =
                MockEngine { request ->
                    captured = request
                    respondRpc(torrentsJson())
                }

            TransmissionClientAdapter(TransmissionConfig(), mockEngine).getProgress(hashA)

            assertNull(assertNotNull(captured).headers[HttpHeaders.Authorization])
        }

    @Test
    fun `should resolve rpc endpoint from base url or explicit rpc path`() {
        assertEquals("http://nas:9091/transmission/rpc", TransmissionRpcClient.resolveEndpoint("http://nas:9091"))
        assertEquals(
            "http://nas:9091/transmission/rpc",
            TransmissionRpcClient.resolveEndpoint("http://nas:9091/transmission")
        )
        assertEquals(
            "http://nas:9091/transmission/rpc",
            TransmissionRpcClient.resolveEndpoint("http://nas:9091/transmission/web/")
        )
        assertEquals("http://nas:9091/transmission/rpc", TransmissionRpcClient.resolveEndpoint(" http://nas:9091/ "))
        assertEquals(
            "https://box.example.com/custom/rpc",
            TransmissionRpcClient.resolveEndpoint("https://box.example.com/custom/rpc/")
        )
    }

    @Test
    fun `should parse torrent-get response into torrent progress`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respondRpc(
                        torrentsJson(
                            torrentJson(
                                hash = hashA.uppercase(),
                                labels = listOf("tv-sonarr", "mwn_msg:123"),
                                extra =
                                    """
                                    , "trackerStats": [
                                        {"seederCount": 120, "leecherCount": 12},
                                        {"seederCount": -1, "leecherCount": -1}
                                    ]
                                    """.trimIndent()
                            )
                        )
                    )
                }

            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)
            val progress = assertNotNull(adapter.getProgress(hashA).getOrNull())

            assertEquals(hashA, progress.id)
            assertEquals("Severance.S02E01.1080p.WEB-DL", progress.name)
            assertEquals(45.2, progress.progressPercent, 0.001)
            assertEquals(0.452, progress.progressRatio, 0.001)
            assertEquals(15728640L, progress.downloadSpeedBytesPerSec)
            assertEquals(10240L, progress.uploadSpeedBytesPerSec)
            assertEquals(120L, progress.etaSeconds)
            assertEquals(1_500_000_000L, progress.totalSizeBytes)
            assertEquals(678_000_000L, progress.downloadedBytes)
            assertEquals(45, progress.swarm?.seedsCount)
            assertEquals(120, progress.swarm?.seedsTotal)
            assertEquals(5, progress.swarm?.peersCount)
            assertEquals(12, progress.swarm?.peersTotal)
            assertEquals(DownloadState.DOWNLOADING, progress.state)
            assertEquals(listOf("tv-sonarr", "mwn_msg:123"), progress.tags)
            assertTrue(progress.items.isEmpty())
        }

    @Test
    fun `should map transmission status codes to torrent states`() =
        runTest {
            data class Case(
                val status: Int,
                val percentDone: Double,
                val extra: String,
                val expected: DownloadState
            )

            val cases =
                listOf(
                    Case(0, 0.5, "", DownloadState.PAUSED),
                    Case(0, 1.0, "", DownloadState.COMPLETED),
                    Case(1, 0.5, "", DownloadState.CHECKING),
                    Case(2, 0.5, "", DownloadState.CHECKING),
                    Case(3, 0.0, "", DownloadState.QUEUED),
                    Case(4, 0.5, "", DownloadState.DOWNLOADING),
                    Case(4, 0.5, """, "isStalled": true""", DownloadState.STALLED),
                    Case(4, 0.0, """, "metadataPercentComplete": 0.3""", DownloadState.ALLOCATING_METADATA),
                    Case(5, 1.0, "", DownloadState.COMPLETED),
                    Case(6, 1.0, "", DownloadState.COMPLETED),
                    Case(42, 0.5, "", DownloadState.UNKNOWN)
                )

            for (case in cases) {
                val mockEngine =
                    MockEngine {
                        respondRpc(
                            torrentsJson(
                                torrentJson(
                                    hashA,
                                    status = case.status,
                                    percentDone = case.percentDone,
                                    extra = case.extra
                                )
                            )
                        )
                    }
                val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)
                val progress = assertNotNull(adapter.getProgress(hashA).getOrNull())
                assertEquals(case.expected, progress.state, "status=${case.status} extra='${case.extra}'")
            }
        }

    @Test
    fun `should aggregate multi-hash downloads sorted by episode`() =
        runTest {
            var requestedIds: List<String> = emptyList()
            val mockEngine =
                MockEngine { request ->
                    requestedIds = request.rpcBody().arguments().stringList("ids")
                    respondRpc(
                        torrentsJson(
                            torrentJson(
                                hashB,
                                name = "Show.S01E02.1080p",
                                percentDone = 0.5,
                                sizeWhenDone = 1000,
                                leftUntilDone = 500,
                                labels = listOf("mwn_msg:1")
                            ),
                            torrentJson(
                                hashA,
                                name = "Show.S01E01.1080p",
                                percentDone = 1.0,
                                status = 6,
                                sizeWhenDone = 1000,
                                leftUntilDone = 0,
                                labels = listOf("mwn_msg:1", "tv")
                            )
                        )
                    )
                }

            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)
            val progress = assertNotNull(adapter.getProgress("$hashA|$hashB").getOrNull())

            assertEquals(listOf(hashA, hashB), requestedIds)
            assertEquals("$hashA|$hashB", progress.id)
            assertEquals(75.0, progress.progressPercent, 0.001)
            assertEquals(2000L, progress.totalSizeBytes)
            assertEquals(1500L, progress.downloadedBytes)
            assertEquals(DownloadState.DOWNLOADING, progress.state)
            assertEquals(listOf("Show.S01E01.1080p", "Show.S01E02.1080p"), progress.items.map { it.name })
            assertEquals(listOf("mwn_msg:1", "tv"), progress.tags)
        }

    @Test
    fun `should return null when torrent is not found`() =
        runTest {
            val mockEngine = MockEngine { respondRpc(torrentsJson()) }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            val result = adapter.getProgress(hashA)
            assertTrue(result.isRight())
            assertNull(result.getOrNull())
        }

    @Test
    fun `should validate info hashes before issuing requests`() =
        runTest {
            var requests = 0
            val mockEngine =
                MockEngine {
                    requests++
                    respondRpc(torrentsJson())
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            listOf("", "   ", "|", "hash123", "a".repeat(39), "g".repeat(40), "$hashA|bogus").forEach { invalid ->
                val result = adapter.getProgress(invalid)
                assertEquals(Either.Right(null), result, "Expected no lookup for '$invalid'")
            }
            assertEquals(0, requests)

            assertEquals(listOf(hashA), TransmissionClientAdapter.parseInfoHashes(" ${hashA.uppercase()} "))
            assertEquals(listOf(hashA, hashB), TransmissionClientAdapter.parseInfoHashes("$hashA|$hashB|$hashA"))
            assertNull(TransmissionClientAdapter.parseInfoHashes(""))
        }

    @Test
    fun `should map http and rpc failures to typed errors`() =
        runTest {
            suspend fun errorFor(handler: MockRequestHandleScope.() -> HttpResponseData): DomainError? =
                TransmissionClientAdapter(TransmissionConfig(), MockEngine { handler() })
                    .getProgress(hashA)
                    .leftOrNull()

            assertIs<DomainError.DownloadClientError.AuthenticationFailed>(
                errorFor { respond("Unauthorized", HttpStatusCode.Unauthorized) }
            )
            assertIs<DomainError.DownloadClientError.AuthenticationFailed>(
                errorFor { respond("Forbidden", HttpStatusCode.Forbidden) }
            )
            assertIs<DomainError.DownloadClientError.InvalidResponse>(
                errorFor { respond("boom", HttpStatusCode.InternalServerError) }
            )
            assertIs<DomainError.DownloadClientError.InvalidResponse>(
                errorFor { respond("{ not json", HttpStatusCode.OK) }
            )
            assertIs<DomainError.DownloadClientError.InvalidResponse>(
                errorFor { respondRpc(arguments = """{"torrents": "nope"}""") }
            )

            val rpcFailure = errorFor { respondRpc(result = "invalid or corrupt torrent file") }
            assertIs<DomainError.DownloadClientError.InvalidResponse>(rpcFailure)
            assertTrue(rpcFailure.details.contains("invalid or corrupt torrent file"))
        }

    @Test
    fun `should return ConnectionFailed on network exception`() =
        runTest {
            val mockEngine = MockEngine { throw java.io.IOException("Network down") }
            val adapter = TransmissionClientAdapter(TransmissionConfig(url = "http://nas:9091"), mockEngine)

            val error = adapter.getProgress(hashA).leftOrNull()
            assertIs<DomainError.DownloadClientError.ConnectionFailed>(error)
            assertEquals("http://nas:9091", error.url)
            assertTrue(adapter.getActiveDownloads().isLeft())
            assertTrue(adapter.addTags(hashA, listOf("tag")).isLeft())
            assertTrue(adapter.stopTorrents(hashA).isLeft())
        }

    @Test
    fun `should list all torrents without ids and filter incomplete ones for downloading`() =
        runTest {
            var arguments: JsonObject? = null
            val mockEngine =
                MockEngine { request ->
                    arguments = request.rpcBody().arguments()
                    respondRpc(
                        torrentsJson(
                            torrentJson(hashA, percentDone = 0.4, labels = listOf("mwn_msg:5")),
                            torrentJson(hashB, percentDone = 1.0, status = 6, leftUntilDone = 0)
                        )
                    )
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            val downloading = assertNotNull(adapter.getActiveDownloads("downloading").getOrNull())
            assertEquals(listOf(hashA), downloading.map { it.id })
            assertEquals(listOf("mwn_msg:5"), downloading.single().tags)

            val captured = assertNotNull(arguments)
            assertFalse(captured.containsKey("ids"))
            assertFalse("trackerStats" in captured.stringList("fields"))

            assertEquals(listOf(hashB), adapter.getActiveDownloads("completed").getOrNull()?.map { it.id })
            assertEquals(2, adapter.getActiveDownloads("all").getOrNull()?.size)
        }

    @Test
    fun `should merge and subtract labels through torrent-set`() =
        runTest {
            var labels = listOf("tv-sonarr")
            val setRequests = mutableListOf<JsonObject>()
            val mockEngine =
                MockEngine { request ->
                    val body = request.rpcBody()
                    when (body.method()) {
                        "torrent-get" -> respondRpc(torrentsJson(torrentJson(hashA, labels = labels)))
                        "torrent-set" -> {
                            setRequests.add(body.arguments())
                            labels = body.arguments().stringList("labels")
                            respondRpc()
                        }
                        else -> respondRpc(result = "method name not recognized")
                    }
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            assertTrue(adapter.addTags(hashA.uppercase(), listOf(" mwn_msg:100 ", "mwn_photo:1", "")).isRight())
            assertEquals(listOf("tv-sonarr", "mwn_msg:100", "mwn_photo:1"), labels)
            assertEquals(listOf(hashA), setRequests.single().stringList("ids"))

            // Re-adding existing labels must not issue another torrent-set
            assertTrue(adapter.addTags(hashA, listOf("mwn_msg:100")).isRight())
            assertEquals(1, setRequests.size)

            assertTrue(adapter.removeTags(hashA, listOf("mwn_msg:100", "mwn_photo:1", "absent")).isRight())
            assertEquals(listOf("tv-sonarr"), labels)
            assertEquals(2, setRequests.size)

            assertTrue(adapter.deleteTags(listOf("mwn_msg:100")).isRight())
            assertEquals(2, setRequests.size)
        }

    @Test
    fun `should update labels per torrent for multi-hash downloads`() =
        runTest {
            val setRequests = mutableListOf<JsonObject>()
            val mockEngine =
                MockEngine { request ->
                    val body = request.rpcBody()
                    when (body.method()) {
                        "torrent-get" ->
                            respondRpc(
                                torrentsJson(
                                    torrentJson(hashA, labels = listOf("tv")),
                                    torrentJson(hashB, labels = listOf("mwn_msg:9"))
                                )
                            )
                        else -> {
                            setRequests.add(body.arguments())
                            respondRpc()
                        }
                    }
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            assertTrue(adapter.addTags("$hashA|$hashB", listOf("mwn_msg:9")).isRight())

            val update = setRequests.single()
            assertEquals(listOf(hashA), update.stringList("ids"))
            assertEquals(listOf("tv", "mwn_msg:9"), update.stringList("labels"))
        }

    @Test
    fun `should skip label mutations without issuing requests for invalid input`() =
        runTest {
            var requests = 0
            val mockEngine =
                MockEngine {
                    requests++
                    respondRpc(torrentsJson())
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            assertTrue(adapter.addTags("   ", listOf("tag")).isRight())
            assertTrue(adapter.addTags("not-a-hash", listOf("tag")).isRight())
            assertTrue(adapter.addTags(hashA, emptyList()).isRight())
            assertTrue(adapter.removeTags(hashA, listOf("  ")).isRight())
            assertEquals(0, requests)
        }

    @Test
    fun `should propagate torrent-set failures`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.rpcBody().method()) {
                        "torrent-get" -> respondRpc(torrentsJson(torrentJson(hashA)))
                        else -> respondRpc(result = "labels cannot contain comma (,) character")
                    }
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            val error = adapter.addTags(hashA, listOf("a,b")).leftOrNull()
            assertIs<DomainError.DownloadClientError.InvalidResponse>(error)
        }

    @Test
    fun `should issue start stop and remove actions for explicit ids`() =
        runTest {
            val bodies = mutableListOf<JsonObject>()
            val mockEngine =
                MockEngine { request ->
                    bodies.add(request.rpcBody())
                    respondRpc()
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            assertTrue(adapter.startTorrents(hashA).isRight())
            assertTrue(adapter.stopTorrents("$hashA|$hashB").isRight())
            assertTrue(adapter.removeTorrents(hashB).isRight())
            assertTrue(adapter.removeTorrents(hashB, deleteLocalData = true).isRight())

            assertEquals(
                listOf("torrent-start", "torrent-stop", "torrent-remove", "torrent-remove"),
                bodies.map { it.method() }
            )
            assertEquals(listOf(hashA), bodies[0].arguments().stringList("ids"))
            assertEquals(listOf(hashA, hashB), bodies[1].arguments().stringList("ids"))
            assertFalse(
                bodies[2]
                    .arguments()
                    .getValue("delete-local-data")
                    .jsonPrimitive.boolean
            )
            assertTrue(
                bodies[3]
                    .arguments()
                    .getValue("delete-local-data")
                    .jsonPrimitive.boolean
            )
        }

    @Test
    fun `should refuse torrent actions without a valid hash`() =
        runTest {
            var requests = 0
            val mockEngine =
                MockEngine {
                    requests++
                    respondRpc()
                }
            val adapter = TransmissionClientAdapter(TransmissionConfig(), mockEngine)

            assertIs<DomainError.DownloadClientError.DownloadNotFound>(adapter.startTorrents("").leftOrNull())
            assertIs<DomainError.DownloadClientError.DownloadNotFound>(adapter.stopTorrents("|").leftOrNull())
            assertIs<DomainError.DownloadClientError.DownloadNotFound>(adapter.removeTorrents("bogus").leftOrNull())
            assertEquals(0, requests)
        }
}
