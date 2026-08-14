package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthControllerTest {
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Test
    fun `should respond 200 OK for health and healthz endpoints`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            val healthController = HealthController(eventRail = eventRail)
            application {
                install(ContentNegotiation) { json(testJson) }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(),
                    healthController = healthController
                )
            }

            val healthRes = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthRes.status)
            assertTrue(healthRes.bodyAsText().contains("UP"))

            val healthzRes = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, healthzRes.status)
            assertTrue(healthzRes.bodyAsText().contains("UP"))
        }

    @Test
    fun `should respond 200 OK for liveness probe endpoints`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            val healthController = HealthController(eventRail = eventRail)
            application {
                install(ContentNegotiation) { json(testJson) }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(),
                    healthController = healthController
                )
            }

            val livezRes = client.get("/livez")
            assertEquals(HttpStatusCode.OK, livezRes.status)
            val livezBody = livezRes.bodyAsText()
            assertTrue(livezBody.contains("UP"))
            assertTrue(livezBody.contains("liveness"))

            val healthLiveRes = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, healthLiveRes.status)
            assertTrue(healthLiveRes.bodyAsText().contains("liveness"))
        }

    @Test
    fun `should respond 200 OK for readiness probe when healthy and 503 when rail is closed`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            val healthController = HealthController(eventRail = eventRail)
            application {
                install(ContentNegotiation) { json(testJson) }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(),
                    healthController = healthController
                )
            }

            // Initially ready
            val readyzRes = client.get("/readyz")
            assertEquals(HttpStatusCode.OK, readyzRes.status)
            val readyzBody = readyzRes.bodyAsText()
            assertTrue(readyzBody.contains("UP"))
            assertTrue(readyzBody.contains("readiness"))

            val healthReadyRes = client.get("/health/ready")
            assertEquals(HttpStatusCode.OK, healthReadyRes.status)

            // Close EventRail (simulating graceful shutdown draining)
            eventRail.close()

            val drainingReadyz = client.get("/readyz")
            assertEquals(HttpStatusCode.ServiceUnavailable, drainingReadyz.status)
            val drainingBody = drainingReadyz.bodyAsText()
            assertTrue(drainingBody.contains("OUT_OF_SERVICE"))
            assertTrue(drainingBody.contains("DRAINING"))
        }

    @Test
    fun `should respond 200 OK for startup probe endpoints`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            val healthController = HealthController(eventRail = eventRail)
            application {
                install(ContentNegotiation) { json(testJson) }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(),
                    healthController = healthController
                )
            }

            val startupzRes = client.get("/startupz")
            assertEquals(HttpStatusCode.OK, startupzRes.status)
            val startupzBody = startupzRes.bodyAsText()
            assertTrue(startupzBody.contains("UP"))
            assertTrue(startupzBody.contains("startup"))

            val healthStartupRes = client.get("/health/startup")
            assertEquals(HttpStatusCode.OK, healthStartupRes.status)
        }

    @Test
    fun `should report runtime memory, uptime, and tracker telemetry via metrics endpoint`() =
        testApplication {
            val eventRail = EventRail(capacity = 10)
            val healthController = HealthController(eventRail = eventRail)
            application {
                install(ContentNegotiation) { json(testJson) }
                configureWebhookRouting(
                    eventRail = eventRail,
                    serverConfig = ServerConfig(),
                    healthController = healthController
                )
            }

            val metricsRes = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metricsRes.status)
            val body = metricsRes.bodyAsText()
            assertTrue(body.contains("uptimeMillis"))
            assertTrue(body.contains("memory"))
            assertTrue(body.contains("activeTrackersCount"))
            assertTrue(body.contains("eventRail"))
        }
}
