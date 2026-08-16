package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.config.ServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
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

    @Test
    fun `should report prometheus text metrics when requested via format param or accept header or direct path`() =
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

            val directPromRes = client.get("/metrics/prometheus")
            assertEquals(HttpStatusCode.OK, directPromRes.status)
            val directBody = directPromRes.bodyAsText()
            assertTrue(directBody.contains("process_uptime_seconds"))
            assertTrue(directBody.contains("jvm_memory_used_bytes"))
            assertTrue(directBody.contains("media_webhook_active_tracking_jobs"))
            assertTrue(directBody.contains("media_webhook_event_rail_running"))

            val paramPromRes = client.get("/metrics?format=prometheus")
            assertEquals(HttpStatusCode.OK, paramPromRes.status)
            val paramBody = paramPromRes.bodyAsText()
            assertTrue(paramBody.contains("process_uptime_seconds"))

            val headerPromRes =
                client.get("/metrics") {
                    header("Accept", "text/plain; version=0.0.4")
                }
            assertEquals(HttpStatusCode.OK, headerPromRes.status)
            val headerBody = headerPromRes.bodyAsText()
            assertTrue(headerBody.contains("process_uptime_seconds"))
        }

    @Test
    fun `should construct and serialize Health and Metrics DTOs correctly`() {
        val healthDto =
            app.hononeko.notifier.adapter.inbound.web.controller
                .HealthStatusDto(status = "UP")
        assertEquals("UP", healthDto.status)
        assertEquals("media-webhook-notifier", healthDto.service)
        assertTrue(healthDto.timestamp > 0)

        val probeDto =
            app.hononeko.notifier.adapter.inbound.web.controller.ProbeStatusDto(
                status = "UP",
                probe = "readiness",
                checks = mapOf("event_rail" to "UP")
            )
        assertEquals("readiness", probeDto.probe)
        assertEquals("UP", probeDto.checks?.get("event_rail"))

        val eventRailMetrics =
            app.hononeko.notifier.adapter.inbound.web.controller.EventRailMetricsDto(
                closed = false,
                running = true
            )
        assertEquals(false, eventRailMetrics.closed)
        assertEquals(true, eventRailMetrics.running)

        val memoryMetrics =
            app.hononeko.notifier.adapter.inbound.web.controller.MemoryMetricsDto(
                usedBytes = 1000L,
                freeBytes = 2000L,
                totalBytes = 3000L,
                maxBytes = 4000L
            )
        assertEquals(1000L, memoryMetrics.usedBytes)

        val metricsDto =
            app.hononeko.notifier.adapter.inbound.web.controller.MetricsDto(
                uptimeMillis = 5000L,
                activeTrackersCount = 2,
                eventRail = eventRailMetrics,
                memory = memoryMetrics
            )
        assertEquals(5000L, metricsDto.uptimeMillis)
        assertEquals(2, metricsDto.activeTrackersCount)
    }
}
