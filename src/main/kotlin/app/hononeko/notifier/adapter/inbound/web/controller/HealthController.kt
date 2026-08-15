package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

const val SERVICE_NAME = "media-webhook-notifier"

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

class HealthController(
    private val eventRail: EventRail? = null,
    private val downloadTracker: DownloadTrackerEngine? = null,
    private val startTimeMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val SERVICE_NAME = app.hononeko.notifier.adapter.inbound.web.controller.SERVICE_NAME
    }

    private val logger = LoggerFactory.getLogger(HealthController::class.java)

    suspend fun handleHealth(call: ApplicationCall) {
        logger.debug("Handling health check")
        call.respond<HealthStatusDto>(
            HttpStatusCode.OK,
            HealthStatusDto(
                status = "UP",
                service = SERVICE_NAME,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun handleLiveness(call: ApplicationCall) {
        logger.debug("Handling liveness probe")
        call.respond<ProbeStatusDto>(
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

        logger.debug("Handling readiness probe: status={}, checks={}", status, checks)
        call.respond<ProbeStatusDto>(
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
        logger.debug("Handling startup probe")
        call.respond<ProbeStatusDto>(
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
        logger.debug("Handling metrics collection")
        val accept = call.request.header("Accept") ?: ""
        val format = call.request.queryParameters["format"] ?: ""

        if (format.equals("prometheus", ignoreCase = true) ||
            format.equals("text", ignoreCase = true) ||
            (accept.contains("text/plain") && !accept.contains("application/json"))
        ) {
            call.respondText(
                buildPrometheusMetrics(),
                ContentType.parse("text/plain; version=0.0.4")
            )
            return
        }

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

        call.respond<MetricsDto>(HttpStatusCode.OK, metrics)
    }

    suspend fun handlePrometheusMetrics(call: ApplicationCall) {
        logger.debug("Handling prometheus metrics collection")
        call.respondText(
            buildPrometheusMetrics(),
            ContentType.parse("text/plain; version=0.0.4")
        )
    }

    fun buildPrometheusMetrics(): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        val uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000.0
        val activeTrackers = downloadTracker?.activeTrackerCount() ?: 0
        val railRunning = if (eventRail?.isRunning == true) 1 else 0
        val railClosed = if (eventRail?.isClosed == true) 1 else 0

        return buildString {
            appendLine("# HELP process_uptime_seconds Process uptime in seconds")
            appendLine("# TYPE process_uptime_seconds gauge")
            appendLine("process_uptime_seconds $uptimeSeconds")
            appendLine("# HELP jvm_memory_used_bytes Used JVM memory in bytes")
            appendLine("# TYPE jvm_memory_used_bytes gauge")
            appendLine("jvm_memory_used_bytes $usedMemory")
            appendLine("# HELP jvm_memory_free_bytes Free JVM memory in bytes")
            appendLine("# TYPE jvm_memory_free_bytes gauge")
            appendLine("jvm_memory_free_bytes $freeMemory")
            appendLine("# HELP jvm_memory_total_bytes Total allocated JVM memory in bytes")
            appendLine("# TYPE jvm_memory_total_bytes gauge")
            appendLine("jvm_memory_total_bytes $totalMemory")
            appendLine("# HELP jvm_memory_max_bytes Maximum JVM memory limit in bytes")
            appendLine("# TYPE jvm_memory_max_bytes gauge")
            appendLine("jvm_memory_max_bytes $maxMemory")
            appendLine("# HELP media_webhook_active_tracking_jobs Number of active torrent tracking jobs")
            appendLine("# TYPE media_webhook_active_tracking_jobs gauge")
            appendLine("media_webhook_active_tracking_jobs $activeTrackers")
            appendLine("# HELP media_webhook_event_rail_running Event rail running state (1 = running, 0 = stopped)")
            appendLine("# TYPE media_webhook_event_rail_running gauge")
            appendLine("media_webhook_event_rail_running $railRunning")
            appendLine("# HELP media_webhook_event_rail_closed Event rail closed state (1 = closed, 0 = open)")
            appendLine("# TYPE media_webhook_event_rail_closed gauge")
            appendLine("media_webhook_event_rail_closed $railClosed")
        }
    }
}
