package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.telegram.TelegramInteractionModule
import app.hononeko.notifier.adapter.inbound.web.DeadLetterPersistence
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.downloadclient.DownloadClientFactory
import app.hononeko.notifier.adapter.outbound.mediaserver.MediaServerAdapter
import app.hononeko.notifier.adapter.outbound.mediaserver.MediaServerHealthAdapter
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
import app.hononeko.notifier.domain.service.CrossSeedDebouncer
import app.hononeko.notifier.domain.service.CrossSeedService
import app.hononeko.notifier.domain.service.DownloadReconciliationService
import app.hononeko.notifier.domain.service.DownloadTrackerConfig
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.EventDeduplicator
import app.hononeko.notifier.domain.service.HealthSnoozes
import app.hononeko.notifier.domain.service.IngestWebhookService
import app.hononeko.notifier.domain.service.ManualInteractionService
import app.hononeko.notifier.domain.service.MediaAvailableDeduplicator
import app.hononeko.notifier.domain.service.MediaAvailableService
import app.hononeko.notifier.domain.service.MediaImportedService
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.MediaServerStatusConfig
import app.hononeko.notifier.domain.service.MediaServerStatusMonitor
import app.hononeko.notifier.domain.service.PodHeartbeat
import app.hononeko.notifier.domain.service.ReconciliationConfig
import app.hononeko.notifier.domain.service.SeasonDebouncer
import app.hononeko.notifier.domain.service.SharedTrackerRegistry
import app.hononeko.notifier.domain.service.SystemHealthService
import app.hononeko.notifier.domain.service.TemplateEngine
import app.hononeko.notifier.domain.service.TrackingLeases
import app.hononeko.notifier.domain.service.startDistributedSweeper
import app.hononeko.notifier.domain.service.trackingLeaseTtlSeconds
import app.hononeko.notifier.domain.service.withDistributedStore
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
    val seerrClient: SeerrClientAdapter? = null,
    val mediaServerStatus: MediaServerStatus? = null,
    val podHeartbeat: PodHeartbeat? = null,
    val sharedTrackers: SharedTrackerRegistry? = null,
    val crossSeedDebouncer: CrossSeedDebouncer? = null
) {
    fun close() {
        logger.info("Closing application dependencies and draining queues...")
        eventRail.close()
        runBlocking {
            seasonDebouncer.flushAll()
            crossSeedDebouncer?.flushAll()
            deadLetterPersistence?.flush()
            // Before the state store closes: stopping loops release their tracking leases.
            downloadTracker.stopAll()
            mediaServerStatus?.monitor?.stop()
            podHeartbeat?.stop()
        }
        telegramInteractions?.close()
        seerrClient?.close()
        mediaServerStatus?.health?.close()
        stateStore.close()
        scope.cancel()
        logger.info("Application dependencies closed")
    }
}

private const val LEASE_RETRY_MARGIN_SECONDS = 5L

/** The pinned media server status message and the health checks behind it (`MEDIA_SERVER_STATUS_MONITOR_ENABLED`). */
class MediaServerStatus(
    val monitor: MediaServerStatusMonitor,
    val health: MediaServerHealthAdapter
)

/** The status monitor, started here, or `null` when it's off or there is no `MEDIA_SERVER_URL` to check. */
private fun buildMediaServerStatus(
    config: AppConfig,
    infra: Infrastructure,
    scope: CoroutineScope
): MediaServerStatus? {
    val mediaServer = config.mediaServer
    val settings = mediaServer.status
    if (!settings.enabled) return null
    if (mediaServer.url.isBlank()) {
        logger.warn("MEDIA_SERVER_STATUS_MONITOR_ENABLED is on but MEDIA_SERVER_URL is empty; the monitor stays off")
        return null
    }
    val health = MediaServerHealthAdapter(mediaServer)
    val heartbeat = infra.podHeartbeat
    val monitor =
        MediaServerStatusMonitor(
            healthCheck = health,
            publisher = infra.publisher,
            stateStore = infra.stateStore,
            config =
                MediaServerStatusConfig(
                    serverName = mediaServer.displayName,
                    sourceName = mediaServer.typeName,
                    ownerId = infra.ownerId,
                    pollIntervalSeconds = settings.pollIntervalSeconds,
                    graceSeconds = settings.graceSeconds,
                    refreshSeconds = settings.refreshSeconds,
                    recoverySeconds = settings.recoverySeconds,
                    pinEnabled = settings.pinEnabled,
                    isOwnerAlive = heartbeat?.let { hb -> { owner -> hb.isAlive(owner) } }
                )
        )
    monitor.start(scope)
    return MediaServerStatus(monitor, health)
}

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

    val infra = buildInfrastructure(config, telegramApiEngine, scope)
    val tracking = buildTracking(config, infra, scope)
    val media = buildMediaAnnouncements(config, infra, tracking, scope)
    val alerts = buildAlerts(config, infra, scope)

    val ingestWebhookService =
        IngestWebhookService(
            trackDownloadUseCase = tracking.downloadTracker,
            announceMediaImportedUseCase = media.imported,
            announceMediaAvailableUseCase = media.available,
            seasonDebouncer = media.seasonDebouncer,
            alertUseCases = alerts.useCases,
            crossSeedDebouncer = alerts.crossSeedDebouncer
        ).withDeduplicator(EventDeduplicator(infra.stateStore))

    val eventRail = EventRail(standardCapacity = 1000, urgentCapacity = 200)
    val deadLetterPersistence =
        (infra.stateStore as? ValkeyStateStore)?.let { valkey ->
            DeadLetterPersistence(buffer = eventRail.deadLetterBuffer, stateStore = valkey).also { it.start(scope) }
        }
    eventRail.start(scope, ingestWebhookService, workerCount = config.server.eventRailWorkers)
    val healthController =
        HealthController(
            eventRail,
            tracking.downloadTracker,
            tracking.reconciliation,
            infra.stateStore,
            sharedTrackers = tracking.sharedTrackers
        )

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
        providerRegistry = webhookProviderRegistry(config),
        healthController = healthController,
        stateStore = infra.stateStore,
        deadLetterPersistence = deadLetterPersistence,
        telegramInteractions = buildTelegramInteractions(config, infra, alerts, scope, telegramApiEngine),
        seerrClient = alerts.seerrClient,
        mediaServerStatus = buildMediaServerStatus(config, infra, scope),
        podHeartbeat = infra.podHeartbeat,
        sharedTrackers = tracking.sharedTrackers,
        crossSeedDebouncer = alerts.crossSeedDebouncer
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
    val podHeartbeat: PodHeartbeat?
) {
    val activeTrackerStore: ActiveTrackerStore = InMemoryActiveTrackerStore()
    val ownerId: String = podHeartbeat?.ownerId ?: trackingOwnerId()
}

private fun buildInfrastructure(
    config: AppConfig,
    telegramApiEngine: HttpClientEngine?,
    scope: CoroutineScope
): Infrastructure {
    val ownerId = trackingOwnerId()
    val stateStore: StateStorePort =
        when (config.state.type.lowercase()) {
            "valkey", "redis" -> ValkeyStateStore(config = config.state)
            else -> InMemoryStateStore()
        }
    val podHeartbeat =
        (stateStore as? ValkeyStateStore)?.let { valkey ->
            PodHeartbeat(valkey, ownerId).also { hb ->
                runBlocking { hb.beat() }
                hb.start(scope)
            }
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
        podHeartbeat = podHeartbeat
    )
}

private class Tracking(
    val downloadTracker: DownloadTrackerEngine,
    val reconciliation: DownloadReconciliationService,
    val sharedTrackers: SharedTrackerRegistry?
) {
    companion object {
        fun leasesFor(
            config: AppConfig,
            infra: Infrastructure
        ): TrackingLeases? =
            (infra.stateStore as? ValkeyStateStore)?.let { store ->
                val liveness: (suspend (String) -> Boolean)? =
                    infra.podHeartbeat?.let { hb -> { owner -> hb.isAlive(owner) } }
                TrackingLeases(
                    store,
                    ownerId = infra.ownerId,
                    ttlSeconds = trackingLeaseTtlSeconds(config.downloadClient.pollIntervalSeconds),
                    isOwnerAlive = liveness
                ).also { logger.info("Tracking leases enabled (owner {}, ttl {}s)", it.ownerId, it.ttlSeconds) }
            }

        fun sharedFor(
            config: AppConfig,
            infra: Infrastructure
        ): SharedTrackerRegistry? =
            (infra.stateStore as? ValkeyStateStore)?.let { store ->
                val liveness: suspend (String) -> Boolean =
                    infra.podHeartbeat?.let { hb -> { owner: String -> hb.isAlive(owner) } } ?: { true }
                SharedTrackerRegistry(
                    stateStore = store,
                    localStore = infra.activeTrackerStore,
                    ownerId = infra.ownerId,
                    isOwnerAlive = liveness,
                    maxPollingMinutes = config.downloadClient.maxPollingMinutes
                )
            }
    }
}

/** Live download tracking, plus the reconciliation sweep (started here) that resumes or adopts downloads. */
private fun buildTracking(
    config: AppConfig,
    infra: Infrastructure,
    scope: CoroutineScope
): Tracking {
    // Only a shared store can coordinate instances; with the in-memory store each instance would just lock itself.
    val trackingLeases = Tracking.leasesFor(config, infra)
    val trackingSharedTrackers = Tracking.sharedFor(config, infra)
    val dc = config.downloadClient
    val downloadTracker =
        DownloadTrackerEngine(
            downloadClient = infra.downloadClient,
            notificationPublisher = infra.publisher,
            activeTrackerStore = infra.activeTrackerStore,
            config =
                DownloadTrackerConfig(
                    dc.pollIntervalSeconds,
                    dc.maxPollingMinutes,
                    dc.stalledTimeoutMinutes,
                    dc.missingGraceAttempts,
                    dc.webuiPublicUrl,
                    dc.tagPrefix
                ),
            scope = scope,
            leases = trackingLeases
        ).apply { sharedTrackers = trackingSharedTrackers }
    trackingSharedTrackers?.startWatcher(scope, downloadTracker)
    val reconciliation =
        DownloadReconciliationService(
            downloadClient = infra.downloadClient,
            trackDownloadUseCase = downloadTracker,
            activeTrackerStore = infra.activeTrackerStore,
            notificationPublisher = infra.publisher,
            config =
                ReconciliationConfig(
                    dc.reconciliationIntervalMinutes,
                    dc.reconciliationEnabled,
                    dc.tagPrefix,
                    trackingLeases?.let { it.ttlSeconds + LEASE_RETRY_MARGIN_SECONDS }
                )
        )
    reconciliation.start(scope)
    return Tracking(downloadTracker, reconciliation, trackingSharedTrackers)
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
    val baseDebouncer =
        SeasonDebouncer(
            debounceMillis = config.downloadClient.debounceSeconds * 1000L,
            scope = scope,
            deduplicator = deduplicator,
            onDebouncedGrab = { grab -> tracking.downloadTracker.track(grab.downloadId, grab) },
            onDebouncedDownload = { download -> imported.announce(download) },
            onDebouncedAvailable = { item -> available.announce(item) }
        )
    val seasonDebouncer =
        if (infra.stateStore is ValkeyStateStore) {
            baseDebouncer.withDistributedStore(infra.stateStore).also { it.startDistributedSweeper(scope) }
        } else {
            baseDebouncer
        }
    return MediaAnnouncements(imported, available, seasonDebouncer)
}

private class Alerts(
    val systemHealth: SystemHealthService,
    val manualInteraction: ManualInteractionService,
    val mediaRequest: MediaRequestService,
    val crossSeed: CrossSeedService,
    val crossSeedDebouncer: CrossSeedDebouncer,
    val seerrClient: SeerrClientAdapter?,
    val healthSnoozes: HealthSnoozes?
) {
    val useCases: AlertUseCases get() = AlertUseCases(systemHealth, manualInteraction, mediaRequest, crossSeed)
}

/** Health, manual-interaction, Seerr and cross-seed cards; with interactions on, admins get the actionable ones. */
private fun buildAlerts(
    config: AppConfig,
    infra: Infrastructure,
    scope: CoroutineScope
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
    val crossSeed = CrossSeedService(notificationPublisher = infra.publisher)
    val baseCrossSeedDebouncer =
        CrossSeedDebouncer(
            debounceMillis = config.crossSeed.debounceSeconds * 1000L,
            scope = scope,
            onDebouncedResult = { result -> crossSeed.announce(result) }
        )
    val crossSeedDebouncer =
        if (infra.stateStore is ValkeyStateStore) {
            baseCrossSeedDebouncer.withDistributedStore(infra.stateStore).also {
                if (config.features.crossSeed) it.startDistributedSweeper(scope)
            }
        } else {
            baseCrossSeedDebouncer
        }
    return Alerts(
        systemHealth = SystemHealthService(infra.publisher, adminRecipients, healthSnoozes),
        manualInteraction = ManualInteractionService(notificationPublisher = infra.publisher),
        mediaRequest =
            MediaRequestService(
                notificationPublisher = infra.publisher,
                decisionRecipients = if (seerrClient != null) adminRecipients else emptyList()
            ),
        crossSeed = crossSeed,
        crossSeedDebouncer = crossSeedDebouncer,
        seerrClient = seerrClient,
        healthSnoozes = healthSnoozes
    )
}

/** The webhook providers; cross-seed's only with `CROSS_SEED_ENABLED=true`. */
private fun webhookProviderRegistry(config: AppConfig): WebhookProviderRegistry {
    val crossSeed = config.features.crossSeed
    if (crossSeed) logger.info("cross-seed webhooks enabled at /api/v1/webhook/cross-seed")
    return WebhookProviderRegistry(WebhookProviderRegistry.defaultProviders(crossSeedEnabled = crossSeed))
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
