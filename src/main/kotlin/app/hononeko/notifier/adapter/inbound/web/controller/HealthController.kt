package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

class HealthController(
    private val eventRail: EventRail? = null,
    private val downloadTracker: DownloadTrackerEngine? = null,
    private val startTimeMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val SERVICE_NAME = "media-webhook-notifier"
    }

    @Serializable
    data class HealthStatusDto(
        val status: String,
        val service: String = SERVICE_NAME,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class ProbeStatusDto(
        val status: String,
        val service: String = SERVICE_NAME,
        val probe: String,
        val checks: Map<String, String>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class EventRailMetricsDto(
        val closed: Boolean,
        val running: Boolean
    )

    @Serializable
    data class MemoryMetricsDto(
        val usedBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long,
        val maxBytes: Long
    )

    @Serializable
    data class MetricsDto(
        val service: String = SERVICE_NAME,
        val status: String = "UP",
        val uptimeMillis: Long,
        val activeTrackersCount: Int,
        val eventRail: EventRailMetricsDto,
        val memory: MemoryMetricsDto,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun handleHealth(call: ApplicationCall) {
        call.respond(
            HttpStatusCode.OK,
            HealthStatusDto(
                status = "UP",
                service = SERVICE_NAME,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleLiveness(call: ApplicationCall) {
        call.respond(
            HttpStatusCode.OK,
            ProbeStatusDto(
                status = "UP",
                service = SERVICE_NAME,
                probe = "liveness",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleReadiness(call: ApplicationCall) {
        val isRailClosed = eventRail?.isClosed == true
        val isReady = !isRailClosed

        val status = if (isReady) "UP" else "OUT_OF_SERVICE"
        val httpStatus = if (isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        val checks =
            mapOf(
                "eventRail" to if (isRailClosed) "DOWN" else "UP",
                "server" to if (isReady) "READY" else "DRAINING"
            )

        call.respond(
            httpStatus,
            ProbeStatusDto(
                status = status,
                service = SERVICE_NAME,
                probe = "readiness",
                checks = checks,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleStartup(call: ApplicationCall) {
        call.respond(
            HttpStatusCode.OK,
            ProbeStatusDto(
                status = "UP",
                service = SERVICE_NAME,
                probe = "startup",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleMetrics(call: ApplicationCall) {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        val uptime = System.currentTimeMillis() - startTimeMillis
        val activeTrackers = downloadTracker?.activeTrackerCount() ?: 0

        val metrics =
            MetricsDto(
                service = SERVICE_NAME,
                status = "UP",
                uptimeMillis = uptime,
                activeTrackersCount = activeTrackers,
                eventRail =
                    EventRailMetricsDto(
                        closed = eventRail?.isClosed ?: false,
                        running = eventRail?.isRunning ?: false
                    ),
                memory =
                    MemoryMetricsDto(
                        usedBytes = usedMemory,
                        freeBytes = freeMemory,
                        totalBytes = totalMemory,
                        maxBytes = maxMemory
                    ),
                timestamp = System.currentTimeMillis()
            )

        call.respond(HttpStatusCode.OK, metrics)
    }
}
