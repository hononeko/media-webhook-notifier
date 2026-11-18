package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.domain.service.DownloadReconciliationService
import app.hononeko.notifier.domain.service.DownloadTrackerEngine

private const val MILLIS_PER_SECOND = 1000.0

internal data class PrometheusMetricsInput(
    val startTimeMillis: Long,
    val downloadTracker: DownloadTrackerEngine?,
    val eventRail: EventRail?,
    val reconciliationService: DownloadReconciliationService?,
    val clusterActiveTrackers: Int?,
    val stateHealthy: Boolean?
)

internal fun renderPrometheusMetrics(input: PrometheusMetricsInput): String =
    buildString {
        jvmGauges(input.startTimeMillis).forEach { (name, help, value) -> promMetric(name, help, "gauge", value) }
        appGauges(input).forEach { (name, help, value) -> promMetric(name, help, "gauge", value) }
        promMetric(
            "media_webhook_reconciliation_runs_total",
            "Total reconciliation sweeps executed",
            "counter",
            input.reconciliationService?.runCount ?: 0L
        )
        promMetric(
            "media_webhook_reconciliation_resumed_total",
            "Total downloads resumed by reconciliation",
            "counter",
            input.reconciliationService?.resumedCount ?: 0L
        )
    }

private fun jvmGauges(startTimeMillis: Long): List<Triple<String, String, Number>> {
    val runtime = Runtime.getRuntime()
    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
    val uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / MILLIS_PER_SECOND
    return listOf(
        Triple("process_uptime_seconds", "Process uptime in seconds", uptimeSeconds),
        Triple("jvm_memory_used_bytes", "Used JVM memory in bytes", usedMemory),
        Triple("jvm_memory_free_bytes", "Free JVM memory in bytes", runtime.freeMemory()),
        Triple("jvm_memory_total_bytes", "Total allocated JVM memory in bytes", runtime.totalMemory()),
        Triple("jvm_memory_max_bytes", "Maximum JVM memory limit in bytes", runtime.maxMemory())
    )
}

private fun appGauges(input: PrometheusMetricsInput): List<Triple<String, String, Number>> =
    listOfNotNull(
        Triple(
            "media_webhook_active_tracking_jobs",
            "Number of active torrent tracking jobs",
            input.downloadTracker?.activeTrackerCount() ?: 0
        ),
        input.clusterActiveTrackers?.let {
            Triple("media_webhook_cluster_active_tracking_jobs", "Active tracking jobs across all replicas", it)
        },
        Triple(
            "media_webhook_event_rail_running",
            "Event rail running state (1 = running, 0 = stopped)",
            if (input.eventRail?.isRunning == true) 1 else 0
        ),
        Triple(
            "media_webhook_event_rail_closed",
            "Event rail closed state (1 = closed, 0 = open)",
            if (input.eventRail?.isClosed == true) 1 else 0
        ),
        Triple(
            "media_webhook_event_rail_workers",
            "Number of active EventRail worker coroutines",
            input.eventRail?.activeWorkersCount ?: 0
        ),
        Triple(
            "media_webhook_dead_letter_total",
            "Number of items currently in the Dead Letter Buffer",
            input.eventRail?.deadLetterBuffer?.size() ?: 0
        ),
        input.stateHealthy?.let {
            Triple(
                "media_webhook_state_store_healthy",
                "State store health (1 = healthy, 0 = unhealthy)",
                if (it) 1 else 0
            )
        }
    )

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
