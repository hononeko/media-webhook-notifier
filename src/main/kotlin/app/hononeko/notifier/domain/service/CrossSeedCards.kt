package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.MediaFormat.formatBytes

/** cross-seed cards: matches injected or saved (`cross_seed.match`) and failed injections (`cross_seed.failure`). */
object CrossSeedCards {
    const val MATCH_EVENT = "cross_seed.match"
    const val FAILURE_EVENT = "cross_seed.failure"

    fun templateEventName(payload: MediaPayload.CrossSeedResult): String =
        if (payload.eventType == EventType.CROSS_SEED_FAILED) FAILURE_EVENT else MATCH_EVENT

    fun buildCard(
        payload: MediaPayload.CrossSeedResult,
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val style = styleOf(payload)
        val instanceLabel = payload.instanceName ?: payload.source.displayName
        val trigger = triggerLabel(payload.trigger)
        val eventName = templateEventName(payload)
        val failed = eventName == FAILURE_EVENT

        val resolved =
            engine.resolveCard(
                eventName = eventName,
                defaults =
                    DefaultCardSpec(
                        title = "${style.icon} ${style.action}: ${payload.name}",
                        subtitle = "$instanceLabel • $trigger",
                        artworkUrl = null,
                        // Matches can arrive by the dozen during a search run; only failures make a sound.
                        silent = !failed
                    ),
                context = buildContext(payload, style, instanceLabel, trigger)
            )

        return resolved.toCard(style.level, eventName, artworkUrl = null) {
            copy(fields = fields(payload, style, failed))
        }
    }

    private fun styleOf(payload: MediaPayload.CrossSeedResult): CrossSeedStyle =
        when {
            payload.eventType == EventType.CROSS_SEED_FAILED ->
                CrossSeedStyle("❌", "Cross-Seed Failed", "Failed to inject", NotificationLevel.ERROR)
            payload.eventType == EventType.CROSS_SEED_SAVED ->
                CrossSeedStyle("💾", "Cross-Seed Saved", "Saved", NotificationLevel.INFO)
            payload.paused ->
                CrossSeedStyle("⏸️", "Cross-Seed Injected", "Injected (paused)", NotificationLevel.INFO)
            else -> CrossSeedStyle("🌱", "Cross-Seed Injected", "Injected", NotificationLevel.SUCCESS)
        }

    private fun fields(
        payload: MediaPayload.CrossSeedResult,
        style: CrossSeedStyle,
        failed: Boolean
    ): List<CardField> =
        listOfNotNull(
            payload.trackers.takeIf { it.isNotEmpty() }?.let {
                CardField("Trackers", it.joinToString(", "), inline = false)
            },
            decisionsLabel(payload.decisions)?.let { CardField("Match", it, inline = true) },
            CardField("Status", style.status, inline = true).takeUnless { failed },
            payload.sizeBytes?.let { CardField("Size", formatBytes(it), inline = true) },
            payload.category?.takeIf { it.isNotBlank() }?.let { CardField("Category", it, inline = true) },
            payload.clientHost?.takeIf { failed && it.isNotBlank() }?.let { CardField("Client", it, inline = true) }
        )

    private fun buildContext(
        payload: MediaPayload.CrossSeedResult,
        style: CrossSeedStyle,
        instanceLabel: String,
        trigger: String
    ): Map<String, Any?> =
        mapOf(
            "title" to payload.name,
            "name" to payload.name,
            "release_name" to payload.name,
            "result_icon" to style.icon,
            "result_action" to style.action,
            "status" to style.status,
            "paused" to if (payload.paused) "Yes" else "No",
            "trackers" to payload.trackers.joinToString(", "),
            "tracker_count" to payload.trackers.size.toString(),
            "decisions" to decisionsLabel(payload.decisions),
            "trigger" to trigger,
            "size" to payload.sizeBytes?.let { formatBytes(it) },
            "total_size" to payload.sizeBytes?.let { formatBytes(it) },
            "category" to payload.category,
            "info_hash" to payload.infoHashes.firstOrNull(),
            "info_hashes" to payload.infoHashes.joinToString(", "),
            "searchee_source" to payload.searcheeSource,
            "client_host" to payload.clientHost,
            "instance_name" to instanceLabel,
            "source_name" to payload.source.displayName
        )
}

/** How a cross-seed result is announced: title icon and action, the status line and the card level. */
private data class CrossSeedStyle(
    val icon: String,
    val action: String,
    val status: String,
    val level: NotificationLevel
)

private val TRIGGER_LABELS =
    mapOf(
        "search" to "Search",
        "rss" to "RSS",
        "announce" to "Announce",
        "webhook" to "Webhook",
        "inject" to "Inject"
    )

private val DECISION_LABELS =
    mapOf(
        "MATCH" to "Exact",
        "MATCH_SIZE_ONLY" to "Size only",
        "MATCH_PARTIAL" to "Partial"
    )

private fun triggerLabel(trigger: String?): String {
    val raw = trigger?.trim().orEmpty()
    if (raw.isEmpty()) return "Match"
    return TRIGGER_LABELS[raw.lowercase()] ?: raw.replaceFirstChar { it.uppercase() }
}

/** The distinct decisions in a readable form, e.g. `Exact, Partial`; null without any. */
private fun decisionsLabel(decisions: List<String>): String? =
    decisions
        .mapNotNull { raw -> raw.trim().ifEmpty { null }?.let { DECISION_LABELS[it.uppercase()] ?: it } }
        .distinct()
        .joinToString(", ")
        .ifEmpty { null }
