package app.hononeko.notifier

import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

            dependencies.close()
        }
}
