package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.telegram.TelegramInteractionModule
import app.hononeko.notifier.adapter.inbound.web.DeadLetterPersistence
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.downloadclient.DownloadClientFactory
import app.hononeko.notifier.adapter.outbound.mediaserver.MediaServerAdapter
import app.hononeko.notifier.adapter.outbound.seerr.SeerrClientAdapter
import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.state.ValkeyStateStore
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import app.hononeko.notifier.domain.service.AlertUseCases
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import app.hononeko.notifier.domain.service.CardTemplates
import app.hononeko.notifier.domain.service.DownloadReconciliationService
import app.hononeko.notifier.domain.service.DownloadTrackerConfig
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.HealthSnoozes
import app.hononeko.notifier.domain.service.IngestWebhookService
import app.hononeko.notifier.domain.service.ManualInteractionService
import app.hononeko.notifier.domain.service.MediaAvailableDeduplicator
import app.hononeko.notifier.domain.service.MediaAvailableService
import app.hononeko.notifier.domain.service.MediaImportedService
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.ReconciliationConfig
import app.hononeko.notifier.domain.service.SeasonDebouncer
import app.hononeko.notifier.domain.service.SystemHealthService
import app.hononeko.notifier.domain.service.TemplateEngine
import app.hononeko.notifier.domain.service.TrackingLeases
import app.hononeko.notifier.domain.service.trackingLeaseTtlSeconds
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.hononeko.notifier.Application")

data class AppDependencies(
    val config: AppConfig,
    val scope: CoroutineScope,
    val downloadClient: DownloadClientPort,
    val notificationPublisher: NotificationPublisherPort,
    val mediaServerPort: MediaServerPort,
    val activeTrackerStore: ActiveTrackerStore = InMemoryActiveTrackerStore(),
    val downloadTracker: DownloadTrackerEngine,
    val reconciliationService: DownloadReconciliationService? = null,
    val seasonDebouncer: SeasonDebouncer,
    val mediaImportedService: MediaImportedService,
    val mediaAvailableService: MediaAvailableService,
    val systemHealthService: SystemHealthService,
    val manualInteractionService: ManualInteractionService,
    val mediaRequestService: MediaRequestService,
    val ingestWebhookService: IngestWebhookUseCase,
    val eventRail: EventRail,
    val rateLimiter: InboundRateLimiter,
    val providerRegistry: WebhookProviderRegistry,
    val healthController: HealthController,
    val stateStore: StateStorePort = InMemoryStateStore(),
    val deadLetterPersistence: DeadLetterPersistence? = null,
    val telegramInteractions: TelegramInteractionModule? = null,
    val seerrClient: SeerrClientAdapter? = null
) {
    fun close() {
        logger.info("Closing application dependencies and draining queues...")
        eventRail.close()
        runBlocking {
            seasonDebouncer.flushAll()
            deadLetterPersistence?.flush()
            // Before the state store closes: stopping loops release their tracking leases.
            downloadTracker.stopAll()
        }
        telegramInteractions?.close()
        seerrClient?.close()
        stateStore.close()
        scope.cancel()
        logger.info("Application dependencies closed")
    }
}

private const val LEASE_RETRY_MARGIN_SECONDS = 5L

/** The pod name for readable logs, plus a suffix so a restarted container never assumes it still owns old leases. */
private fun trackingOwnerId(): String {
    val suffix = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    return "${System.getenv("HOSTNAME") ?: "mwn"}-$suffix"
}

/**
 * The Seerr client behind the admin-only Approve / Decline / Resolve buttons, or `null` when those buttons stay off:
 * interactions disabled, Seerr not configured, or nobody in `TELEGRAM_ADMIN_USERS` to press them.
 */
private fun seerrClientFor(
    config: AppConfig,
    interactive: Boolean
): SeerrClientAdapter? {
    if (!config.seerr.isConfigured) return null
    if (!interactive) {
        logger.info("SEERR_URL is set but Telegram interactions are off; Seerr cards carry no approval buttons")
        return null
    }
    if (config.telegramInteraction.adminUserIds.isEmpty()) {
        logger.warn(
            "SEERR_URL is set but TELEGRAM_ADMIN_USERS is empty; Approve, Decline and Resolve buttons stay off " +
                "until at least one admin is listed"
        )
        return null
    }
    logger.info("Seerr request buttons enabled for {}", config.seerr.url)
    return SeerrClientAdapter(config.seerr)
}

fun buildDependencies(
    config: AppConfig,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    telegramApiEngine: HttpClientEngine? = null
): AppDependencies {
    CardTemplates.templateEngine = TemplateEngine(config.templates)

    val infra = buildInfrastructure(config, telegramApiEngine)
    val tracking = buildTracking(config, infra, scope)
    val media = buildMediaAnnouncements(config, infra, tracking, scope)
    val alerts = buildAlerts(config, infra)

    val ingestWebhookService =
        IngestWebhookService(
            trackDownloadUseCase = tracking.downloadTracker,
            announceMediaImportedUseCase = media.imported,
            announceMediaAvailableUseCase = media.available,
            seasonDebouncer = media.seasonDebouncer,
            alertUseCases =
                AlertUseCases(
                    systemHealth = alerts.systemHealth,
                    manualInteraction = alerts.manualInteraction,
                    mediaRequest = alerts.mediaRequest
                )
        )

    val eventRail = EventRail(standardCapacity = 1000, urgentCapacity = 200)
    val deadLetterPersistence =
        (infra.stateStore as? ValkeyStateStore)?.let { valkey ->
            DeadLetterPersistence(buffer = eventRail.deadLetterBuffer, stateStore = valkey).also { it.start(scope) }
        }
    eventRail.start(scope, ingestWebhookService, workerCount = config.server.eventRailWorkers)

    return AppDependencies(
        config = config,
        scope = scope,
        downloadClient = infra.downloadClient,
        notificationPublisher = infra.publisher,
        mediaServerPort = infra.mediaServerPort,
        activeTrackerStore = infra.activeTrackerStore,
        downloadTracker = tracking.downloadTracker,
        reconciliationService = tracking.reconciliation,
        seasonDebouncer = media.seasonDebouncer,
        mediaImportedService = media.imported,
        mediaAvailableService = media.available,
        systemHealthService = alerts.systemHealth,
        manualInteractionService = alerts.manualInteraction,
        mediaRequestService = alerts.mediaRequest,
        ingestWebhookService = ingestWebhookService,
        eventRail = eventRail,
        rateLimiter = InboundRateLimiter(limitPerMinute = config.server.rateLimitPerMinute),
        providerRegistry = WebhookProviderRegistry(),
        healthController =
            HealthController(
                eventRail = eventRail,
                downloadTracker = tracking.downloadTracker,
                reconciliationService = tracking.reconciliation,
                stateStore = infra.stateStore
            ),
        stateStore = infra.stateStore,
        deadLetterPersistence = deadLetterPersistence,
        telegramInteractions = buildTelegramInteractions(config, infra, alerts, scope, telegramApiEngine),
        seerrClient = alerts.seerrClient
    )
}

/** The outbound side every feature shares: state store, download client, media server, and the Telegram publisher. */
private class Infrastructure(
    val stateStore: StateStorePort,
    /** Shared by the publisher (registers button refs) and the listener (resolves them); null without interactions. */
    val callbackRefs: CallbackRefRegistry?,
    val publisher: TelegramPublisherAdapter,
    val downloadClient: DownloadClientPort,
    val mediaServerPort: MediaServerAdapter,
    val activeTrackerStore: ActiveTrackerStore
)

private fun buildInfrastructure(
    config: AppConfig,
    telegramApiEngine: HttpClientEngine?
): Infrastructure {
    val stateStore: StateStorePort =
        when (config.state.type.lowercase()) {
            "valkey", "redis" -> ValkeyStateStore(config = config.state)
            else -> InMemoryStateStore()
        }
    val callbackRefs = if (config.features.telegramInteractive) CallbackRefRegistry(stateStore) else null
    return Infrastructure(
        stateStore = stateStore,
        callbackRefs = callbackRefs,
        publisher =
            TelegramPublisherAdapter(
                config = config.notifications,
                engine = telegramApiEngine,
                callbackRefs = callbackRefs
            ),
        downloadClient = DownloadClientFactory.create(config),
        mediaServerPort = MediaServerAdapter(config = config.mediaServer),
        activeTrackerStore = InMemoryActiveTrackerStore()
    )
}

private class Tracking(
    val downloadTracker: DownloadTrackerEngine,
    val reconciliation: DownloadReconciliationService
)

/** Live download tracking, plus the reconciliation sweep (started here) that resumes or adopts downloads. */
private fun buildTracking(
    config: AppConfig,
    infra: Infrastructure,
    scope: CoroutineScope
): Tracking {
    // Only a shared store can coordinate instances; with the in-memory store each instance would just lock itself.
    val trackingLeases =
        (infra.stateStore as? ValkeyStateStore)?.let { valkey ->
            TrackingLeases(
                valkey,
                ownerId = trackingOwnerId(),
                ttlSeconds = trackingLeaseTtlSeconds(config.downloadClient.pollIntervalSeconds)
            ).also { logger.info("Tracking leases enabled (owner {}, ttl {}s)", it.ownerId, it.ttlSeconds) }
        }
    val downloadTracker =
        DownloadTrackerEngine(
            downloadClient = infra.downloadClient,
            notificationPublisher = infra.publisher,
            activeTrackerStore = infra.activeTrackerStore,
            config =
                DownloadTrackerConfig(
                    pollIntervalSeconds = config.downloadClient.pollIntervalSeconds,
                    maxPollingMinutes = config.downloadClient.maxPollingMinutes,
                    stalledTimeoutMinutes = config.downloadClient.stalledTimeoutMinutes,
                    missingGraceAttempts = config.downloadClient.missingGraceAttempts,
                    webuiPublicUrl = config.downloadClient.webuiPublicUrl,
                    tagPrefix = config.downloadClient.tagPrefix
                ),
            scope = scope,
            leases = trackingLeases
        )
    val reconciliation =
        DownloadReconciliationService(
            downloadClient = infra.downloadClient,
            trackDownloadUseCase = downloadTracker,
            activeTrackerStore = infra.activeTrackerStore,
            notificationPublisher = infra.publisher,
            config =
                ReconciliationConfig(
                    intervalMinutes = config.downloadClient.reconciliationIntervalMinutes,
                    enabled = config.downloadClient.reconciliationEnabled,
                    tagPrefix = config.downloadClient.tagPrefix,
                    leaseRetrySeconds = trackingLeases?.let { it.ttlSeconds + LEASE_RETRY_MARGIN_SECONDS }
                )
        )
    reconciliation.start(scope)
    return Tracking(downloadTracker, reconciliation)
}

private class MediaAnnouncements(
    val imported: MediaImportedService,
    val available: MediaAvailableService,
    val seasonDebouncer: SeasonDebouncer
)

/** Import and "now available" cards, with the debouncer that batches a season pack's grabs, imports and arrivals. */
private fun buildMediaAnnouncements(
    config: AppConfig,
    infra: Infrastructure,
    tracking: Tracking,
    scope: CoroutineScope
): MediaAnnouncements {
    val imported = MediaImportedService(notificationPublisher = infra.publisher)
    val deduplicator =
        MediaAvailableDeduplicator(
            stateStore = infra.stateStore,
            maxAgeSeconds = config.mediaServer.maxAvailableAgeSeconds
        )
    val available =
        MediaAvailableService(
            notificationPublisher = infra.publisher,
            mediaServerPort = infra.mediaServerPort,
            deduplicator = deduplicator
        )
    val seasonDebouncer =
        SeasonDebouncer(
            debounceMillis = config.downloadClient.debounceSeconds * 1000L,
            scope = scope,
            deduplicator = deduplicator,
            onDebouncedGrab = { grab -> tracking.downloadTracker.track(grab.downloadId, grab) },
            onDebouncedDownload = { download -> imported.announce(download) },
            onDebouncedAvailable = { item -> available.announce(item) }
        )
    return MediaAnnouncements(imported, available, seasonDebouncer)
}

private class Alerts(
    val systemHealth: SystemHealthService,
    val manualInteraction: ManualInteractionService,
    val mediaRequest: MediaRequestService,
    val seerrClient: SeerrClientAdapter?,
    val healthSnoozes: HealthSnoozes?
)

/** Health, manual-interaction and Seerr cards; with interactions on, the ones needing action go to the admins. */
private fun buildAlerts(
    config: AppConfig,
    infra: Infrastructure
): Alerts {
    val interactive = infra.callbackRefs != null
    // Cards that need someone to act go privately to the admins, once interactions are on and admins are listed.
    val adminRecipients =
        if (interactive) {
            config.telegramInteraction.adminUserIds.map {
                it.toString()
            }
        } else {
            emptyList()
        }
    // Snoozes only exist with interactions on: they're created by a button press.
    val healthSnoozes = if (interactive) HealthSnoozes(infra.stateStore) else null
    val seerrClient = seerrClientFor(config, interactive)
    return Alerts(
        systemHealth = SystemHealthService(infra.publisher, adminRecipients, healthSnoozes),
        manualInteraction = ManualInteractionService(notificationPublisher = infra.publisher),
        mediaRequest =
            MediaRequestService(
                notificationPublisher = infra.publisher,
                decisionRecipients = if (seerrClient != null) adminRecipients else emptyList()
            ),
        seerrClient = seerrClient,
        healthSnoozes = healthSnoozes
    )
}

/** The Telegram button listener, started here; null with interactions off. */
private fun buildTelegramInteractions(
    config: AppConfig,
    infra: Infrastructure,
    alerts: Alerts,
    scope: CoroutineScope,
    telegramApiEngine: HttpClientEngine?
): TelegramInteractionModule? {
    val callbackRefs = infra.callbackRefs ?: return null
    val handlers =
        TelegramInteractionModule.actionHandlers(
            infra.publisher,
            callbackRefs,
            alerts.seerrClient,
            alerts.healthSnoozes
        )
    return TelegramInteractionModule.create(config, callbackRefs, handlers, scope, telegramApiEngine).also {
        it.start(scope)
    }
}
