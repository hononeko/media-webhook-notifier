package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import app.hononeko.notifier.domain.service.DownloadReconciliationService
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.SharedTrackerRegistry
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
    val running: Boolean,
    val activeWorkers: Int = 0,
    val deadLetterCount: Int = 0
)

@Serializable
data class ReconciliationMetricsDto(
    val enabled: Boolean = false,
    val runCount: Long = 0,
    val resumedCount: Long = 0
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
    val clusterActiveTrackersCount: Int? = null,
    val eventRail: EventRailMetricsDto,
    val reconciliation: ReconciliationMetricsDto = ReconciliationMetricsDto(),
    val memory: MemoryMetricsDto,
    val stateStoreHealthy: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class HealthController(
    private val eventRail: EventRail? = null,
    private val downloadTracker: DownloadTrackerEngine? = null,
    private val reconciliationService: DownloadReconciliationService? = null,
    private val stateStore: StateStorePort? = null,
    private val startTimeMillis: Long = System.currentTimeMillis(),
    private val sharedTrackers: SharedTrackerRegistry? = null
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
        val stateHealthy = stateStore?.healthCheck() ?: true

        val status = if (isReady) "UP" else "OUT_OF_SERVICE"
        val httpStatus = if (isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        val checks =
            buildMap {
                put("eventRail", if (isRailClosed) "DOWN" else "UP")
                put("server", if (isReady) "READY" else "DRAINING")
                if (stateStore != null) {
                    put("stateStore", if (stateHealthy) "UP" else "DEGRADED")
                }
            }

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
        if (wantsPrometheusFormat(call)) {
            call.respondText(
                buildPrometheusMetrics(),
                ContentType.parse("text/plain; version=0.0.4")
            )
            return
        }

        call.respond<MetricsDto>(HttpStatusCode.OK, buildJsonMetrics())
    }

    private fun wantsPrometheusFormat(call: ApplicationCall): Boolean {
        val accept = call.request.header("Accept") ?: ""
        val format = call.request.queryParameters["format"] ?: ""
        val isExplicitText =
            format.equals("prometheus", ignoreCase = true) ||
                format.equals("text", ignoreCase = true)
        val isPlainTextAccept = accept.contains("text/plain") && !accept.contains("application/json")
        return isExplicitText || isPlainTextAccept
    }

    private suspend fun buildJsonMetrics(): MetricsDto {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        val uptime = System.currentTimeMillis() - startTimeMillis
        val activeTrackers = downloadTracker?.activeTrackerCount() ?: 0
        val clusterActiveTrackers = sharedTrackers?.allSnapshots()?.size

        return MetricsDto(
            service = SERVICE_NAME,
            status = "UP",
            uptimeMillis = uptime,
            activeTrackersCount = activeTrackers,
            clusterActiveTrackersCount = clusterActiveTrackers,
            eventRail =
                EventRailMetricsDto(
                    closed = eventRail?.isClosed ?: false,
                    running = eventRail?.isRunning ?: false,
                    activeWorkers = eventRail?.activeWorkersCount ?: 0,
                    deadLetterCount = eventRail?.deadLetterBuffer?.size() ?: 0
                ),
            reconciliation =
                ReconciliationMetricsDto(
                    enabled = reconciliationService?.enabled ?: false,
                    runCount = reconciliationService?.runCount ?: 0L,
                    resumedCount = reconciliationService?.resumedCount ?: 0L
                ),
            memory =
                MemoryMetricsDto(
                    usedBytes = usedMemory,
                    freeBytes = freeMemory,
                    totalBytes = totalMemory,
                    maxBytes = maxMemory
                ),
            stateStoreHealthy = stateStore?.healthCheck(),
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun handlePrometheusMetrics(call: ApplicationCall) {
        logger.debug("Handling prometheus metrics collection")
        call.respondText(
            buildPrometheusMetrics(),
            ContentType.parse("text/plain; version=0.0.4")
        )
    }

    suspend fun buildPrometheusMetrics(): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        val uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000.0
        val activeTrackers = downloadTracker?.activeTrackerCount() ?: 0
        val railRunning = if (eventRail?.isRunning == true) 1 else 0
        val railClosed = if (eventRail?.isClosed == true) 1 else 0
        val workers = eventRail?.activeWorkersCount ?: 0
        val deadLetterCount = eventRail?.deadLetterBuffer?.size() ?: 0
        val reconRuns = reconciliationService?.runCount ?: 0L
        val reconResumed = reconciliationService?.resumedCount ?: 0L
        val stateHealthy = stateStore?.healthCheck()
        val clusterActiveTrackers = sharedTrackers?.allSnapshots()?.size

        return buildString {
            promMetric("process_uptime_seconds", "Process uptime in seconds", "gauge", uptimeSeconds)
            promMetric("jvm_memory_used_bytes", "Used JVM memory in bytes", "gauge", usedMemory)
            promMetric("jvm_memory_free_bytes", "Free JVM memory in bytes", "gauge", freeMemory)
            promMetric("jvm_memory_total_bytes", "Total allocated JVM memory in bytes", "gauge", totalMemory)
            promMetric("jvm_memory_max_bytes", "Maximum JVM memory limit in bytes", "gauge", maxMemory)
            promMetric(
                "media_webhook_active_tracking_jobs",
                "Number of active torrent tracking jobs",
                "gauge",
                activeTrackers
            )
            if (clusterActiveTrackers != null) {
                promMetric(
                    "media_webhook_cluster_active_tracking_jobs",
                    "Active tracking jobs across all replicas",
                    "gauge",
                    clusterActiveTrackers
                )
            }
            promMetric(
                "media_webhook_event_rail_running",
                "Event rail running state (1 = running, 0 = stopped)",
                "gauge",
                railRunning
            )
            promMetric(
                "media_webhook_event_rail_closed",
                "Event rail closed state (1 = closed, 0 = open)",
                "gauge",
                railClosed
            )
            promMetric(
                "media_webhook_event_rail_workers",
                "Number of active EventRail worker coroutines",
                "gauge",
                workers
            )
            promMetric(
                "media_webhook_dead_letter_total",
                "Number of items currently in the Dead Letter Buffer",
                "gauge",
                deadLetterCount
            )
            promMetric(
                "media_webhook_reconciliation_runs_total",
                "Total reconciliation sweeps executed",
                "counter",
                reconRuns
            )
            promMetric(
                "media_webhook_reconciliation_resumed_total",
                "Total downloads resumed by reconciliation",
                "counter",
                reconResumed
            )
            if (stateHealthy != null) {
                promMetric(
                    "media_webhook_state_store_healthy",
                    "State store health (1 = healthy, 0 = unhealthy)",
                    "gauge",
                    if (stateHealthy) 1 else 0
                )
            }
        }
    }
}

private fun StringBuilder.promMetric(
    name: String,
    help: String,
    type: String,
    value: Any
) {
    appendLine("# HELP $name $help")
    appendLine("# TYPE $name $type")
    appendLine("$name $value")
}
