package app.hononeko.notifier.adapter.outbound.downloadclient

import app.hononeko.notifier.adapter.outbound.qbittorrent.QBittorrentClientAdapter
import app.hononeko.notifier.adapter.outbound.transmission.TransmissionClientAdapter
import app.hononeko.notifier.config.ConfigLoader
import app.hononeko.notifier.config.DownloadClientConfig
import app.hononeko.notifier.config.DownloadClientType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadClientFactoryTest {
    private val infoHash = "c".repeat(40)

    private fun recordingEngine(
        requests: MutableList<HttpRequestData>,
        body: String
    ) = MockEngine { request ->
        requests.add(request)
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun `should create and route to qbittorrent by default`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val config = ConfigLoader.load(mapOf("DOWNLOAD_CLIENT_URL" to "qbittorrents://qbit.example.com/qbit"))

            val client = DownloadClientFactory.create(config, recordingEngine(requests, "[]"))

            assertIs<QBittorrentClientAdapter>(client)
            assertTrue(client.getProgress(infoHash).isRight())
            val request = requests.single()
            assertEquals("https", request.url.protocol.name)
            assertEquals("qbit.example.com", request.url.host)
            assertEquals("/qbit/api/v2/torrents/info", request.url.encodedPath)
        }

    @Test
    fun `should create and route to transmission when selected`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val config =
                ConfigLoader.load(
                    mapOf(
                        "DOWNLOAD_CLIENT_TYPE" to "transmission",
                        "TRANSMISSION_URL" to "http://nas:9091",
                        "TRANSMISSION_USERNAME" to "rpc",
                        "TRANSMISSION_PASSWORD" to "secret"
                    )
                )

            val client =
                DownloadClientFactory.create(
                    config,
                    recordingEngine(requests, """{"result": "success", "arguments": {"torrents": []}}""")
                )

            assertIs<TransmissionClientAdapter>(client)
            assertTrue(client.getProgress(infoHash).isRight())
            val request = requests.single()
            assertEquals("nas", request.url.host)
            assertEquals(9091, request.url.port)
            assertEquals("/transmission/rpc", request.url.encodedPath)
            assertNotNull(request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `should fail fast for clients without an adapter yet`() {
        val config =
            app.hononeko.notifier.config.AppConfig(
                downloadClient = DownloadClientConfig(type = DownloadClientType.SABNZBD)
            )

        val error = assertFailsWith<IllegalStateException> { DownloadClientFactory.create(config) }

        assertTrue(error.message!!.contains("sabnzbd"))
    }
}
