package app.hononeko.notifier

import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationBootstrapTest {
    @Test
    fun `should build dependencies and start application module cleanly`() =
        testApplication {
            val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val config = AppConfig(server = ServerConfig(authToken = ""))
            val dependencies = buildDependencies(config, scope = testScope)

            assertNotNull(dependencies.torrentClient)
            assertNotNull(dependencies.notificationPublisher)
            assertNotNull(dependencies.mediaServerPort)
            assertNotNull(dependencies.downloadTracker)
            assertNotNull(dependencies.seasonDebouncer)
            assertNotNull(dependencies.mediaImportedService)
            assertNotNull(dependencies.mediaAvailableService)
            assertNotNull(dependencies.ingestWebhookService)
            assertNotNull(dependencies.eventRail)
            assertNotNull(dependencies.rateLimiter)
            assertNotNull(dependencies.providerRegistry)
            assertNotNull(dependencies.healthController)

            application {
                module(dependencies)
                routing {
                    get("/test-error") {
                        throw IllegalStateException("Intentional test failure")
                    }
                }
            }

            val healthRes = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthRes.status)
            assertTrue(healthRes.bodyAsText().contains("UP"))

            val metricsRes = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metricsRes.status)

            val sonarrTestRes =
                client.post("/api/v1/webhook/sonarr") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType": "Test", "instanceName": "Sonarr-Test"}""")
                }
            assertEquals(HttpStatusCode.OK, sonarrTestRes.status)
            assertTrue(sonarrTestRes.bodyAsText().contains("Test webhook received successfully"))

            val unknownRes =
                client.post("/api/v1/webhook/unknown-service") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"test": true}""")
                }
            assertEquals(HttpStatusCode.NotFound, unknownRes.status)

            // Test StatusPages error handling
            val errorRes = client.get("/test-error")
            assertEquals(HttpStatusCode.InternalServerError, errorRes.status)
            assertTrue(errorRes.bodyAsText().contains("An unexpected error occurred"))

            dependencies.close()
        }

    @Test
    fun `should build dependencies with default scope and start embedded Netty server`() =
        runBlocking {
            val freePort = ServerSocket(0).use { it.localPort }
            val config = AppConfig(server = ServerConfig(port = freePort, authToken = ""))
            val dependencies = buildDependencies(config)

            val server = startServer(config, dependencies, wait = false)
            delay(500) // Allow Netty to bind

            val httpClient = HttpClient(CIO)
            try {
                val response = httpClient.get("http://127.0.0.1:$freePort/health")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("UP"))
            } finally {
                httpClient.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
                dependencies.close()
            }
        }

    @Test
    fun `should route debounced events through dependencies debouncer callbacks`() =
        testApplication {
            val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val config =
                AppConfig(
                    server = ServerConfig(authToken = ""),
                    qbittorrent =
                        app.hononeko.notifier.config
                            .QBittorrentConfig(debounceSeconds = 1)
                )
            val dependencies = buildDependencies(config, scope = testScope)

            val grab =
                app.hononeko.notifier.domain.model.MediaPayload.ArrGrab(
                    source = app.hononeko.notifier.domain.model.AppSource.SONARR,
                    downloadId = "hashBootstrap",
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1)
                )

            val download =
                app.hononeko.notifier.domain.model.MediaPayload.ArrDownload(
                    source = app.hononeko.notifier.domain.model.AppSource.SONARR,
                    downloadId = "hashBootstrapDl",
                    title = "Futurama - S01E01",
                    seriesOrMovieTitle = "Futurama",
                    seasonNumber = 1,
                    episodeNumbers = listOf(1)
                )

            dependencies.seasonDebouncer.submit(grab)
            dependencies.seasonDebouncer.submit(download)
            dependencies.seasonDebouncer.flushAll()

            dependencies.close()
        }
}
