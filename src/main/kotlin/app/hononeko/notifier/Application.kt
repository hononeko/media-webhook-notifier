package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.configureWebhookRouting
import app.hononeko.notifier.adapter.inbound.web.controller.EventRailMetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.controller.HealthStatusDto
import app.hononeko.notifier.adapter.inbound.web.controller.MemoryMetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.MetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.ProbeStatusDto
import app.hononeko.notifier.adapter.inbound.web.controller.ReconciliationMetricsDto
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.mediaserver.MediaServerAdapter
import app.hononeko.notifier.adapter.outbound.qbittorrent.QBittorrentClientAdapter
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.adapter.outbound.tracker.InMemoryActiveTrackerStore
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.ConfigLoader
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import app.hononeko.notifier.domain.service.CardFormatterService
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.IngestWebhookService
import app.hononeko.notifier.domain.service.ManualInteractionService
import app.hononeko.notifier.domain.service.MediaAvailableService
import app.hononeko.notifier.domain.service.MediaImportedService
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.SeasonDebouncer
import app.hononeko.notifier.domain.service.SystemHealthService
import app.hononeko.notifier.domain.service.TemplateEngine
import app.hononeko.notifier.domain.service.TorrentReconciliationService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("app.hononeko.notifier.Application")

data class AppDependencies(
    val config: AppConfig,
    val scope: CoroutineScope,
    val torrentClient: TorrentClientPort,
    val notificationPublisher: NotificationPublisherPort,
    val mediaServerPort: MediaServerPort,
    val activeTrackerStore: ActiveTrackerStore = InMemoryActiveTrackerStore(),
    val downloadTracker: DownloadTrackerEngine,
    val reconciliationService: TorrentReconciliationService? = null,
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
    val healthController: HealthController
) {
    fun close() {
        logger.info("Closing application dependencies and draining queues...")
        eventRail.close()
        runBlocking {
            seasonDebouncer.flushAll()
        }
        downloadTracker.stopAll()
        scope.cancel()
        logger.info("Application dependencies closed")
    }
}

fun buildDependencies(
    config: AppConfig,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): AppDependencies {
    CardFormatterService.templateEngine = TemplateEngine(config.templates)

    val torrentClient = QBittorrentClientAdapter(config = config.qbittorrent)
    val notificationPublisher = TelegramPublisherAdapter(config = config.notifications)
    val mediaServerPort = MediaServerAdapter(config = config.mediaServer)
    val activeTrackerStore = InMemoryActiveTrackerStore()

    val downloadTracker =
        DownloadTrackerEngine(
            torrentClient = torrentClient,
            notificationPublisher = notificationPublisher,
            activeTrackerStore = activeTrackerStore,
            pollIntervalSeconds = config.qbittorrent.pollIntervalSeconds,
            maxPollingMinutes = config.qbittorrent.maxPollingMinutes,
            stalledTimeoutMinutes = config.qbittorrent.stalledTimeoutMinutes,
            webuiPublicUrl = config.qbittorrent.webuiPublicUrl,
            scope = scope
        )

    val reconciliationService =
        TorrentReconciliationService(
            torrentClient = torrentClient,
            trackDownloadUseCase = downloadTracker,
            activeTrackerStore = activeTrackerStore,
            notificationPublisher = notificationPublisher,
            intervalMinutes = config.qbittorrent.reconciliationIntervalMinutes,
            enabled = config.qbittorrent.reconciliationEnabled
        )
    reconciliationService.start(scope)

    val mediaImportedService =
        MediaImportedService(
            notificationPublisher = notificationPublisher
        )

    val mediaAvailableService =
        MediaAvailableService(
            notificationPublisher = notificationPublisher,
            mediaServerPort = mediaServerPort
        )

    val seasonDebouncer =
        SeasonDebouncer(
            debounceMillis = config.qbittorrent.debounceSeconds * 1000L,
            scope = scope,
            onDebouncedGrab = { grab ->
                downloadTracker.track(grab.downloadId, grab)
            },
            onDebouncedDownload = { download ->
                mediaImportedService.announce(download)
            },
            onDebouncedAvailable = { available ->
                mediaAvailableService.announce(available)
            }
        )

    val systemHealthService =
        SystemHealthService(
            notificationPublisher = notificationPublisher
        )

    val manualInteractionService =
        ManualInteractionService(
            notificationPublisher = notificationPublisher
        )

    val mediaRequestService =
        MediaRequestService(
            notificationPublisher = notificationPublisher
        )

    val ingestWebhookService =
        IngestWebhookService(
            seasonDebouncer = seasonDebouncer,
            trackDownloadUseCase = downloadTracker,
            announceMediaImportedUseCase = mediaImportedService,
            announceMediaAvailableUseCase = mediaAvailableService,
            announceSystemHealthUseCase = systemHealthService,
            announceManualInteractionUseCase = manualInteractionService,
            announceMediaRequestUseCase = mediaRequestService
        )

    val eventRail = EventRail(standardCapacity = 1000, urgentCapacity = 200)
    eventRail.start(scope, ingestWebhookService, workerCount = config.server.eventRailWorkers)

    val rateLimiter = InboundRateLimiter(limitPerMinute = config.server.rateLimitPerMinute)
    val providerRegistry = WebhookProviderRegistry()
    val healthController =
        HealthController(
            eventRail = eventRail,
            downloadTracker = downloadTracker,
            reconciliationService = reconciliationService
        )

    return AppDependencies(
        config = config,
        scope = scope,
        torrentClient = torrentClient,
        notificationPublisher = notificationPublisher,
        mediaServerPort = mediaServerPort,
        activeTrackerStore = activeTrackerStore,
        downloadTracker = downloadTracker,
        reconciliationService = reconciliationService,
        seasonDebouncer = seasonDebouncer,
        mediaImportedService = mediaImportedService,
        mediaAvailableService = mediaAvailableService,
        systemHealthService = systemHealthService,
        manualInteractionService = manualInteractionService,
        mediaRequestService = mediaRequestService,
        ingestWebhookService = ingestWebhookService,
        eventRail = eventRail,
        rateLimiter = rateLimiter,
        providerRegistry = providerRegistry,
        healthController = healthController
    )
}

fun Application.module(dependencies: AppDependencies) {
    install(ForwardedHeaders)
    install(XForwardedHeaders)

    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            !path.startsWith("/livez") &&
                !path.startsWith("/readyz") &&
                !path.startsWith("/startupz") &&
                !path.startsWith("/health") &&
                !path.startsWith("/metrics")
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
                serializersModule =
                    SerializersModule {
                        contextual(WebhookReceiptDto::class, WebhookReceiptDto.serializer())
                        contextual(HealthStatusDto::class, HealthStatusDto.serializer())
                        contextual(ProbeStatusDto::class, ProbeStatusDto.serializer())
                        contextual(MetricsDto::class, MetricsDto.serializer())
                        contextual(EventRailMetricsDto::class, EventRailMetricsDto.serializer())
                        contextual(ReconciliationMetricsDto::class, ReconciliationMetricsDto.serializer())
                        contextual(MemoryMetricsDto::class, MemoryMetricsDto.serializer())
                        contextual(
                            app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewRequestDto::class,
                            app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewRequestDto
                                .serializer()
                        )
                        contextual(
                            app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewResponseDto::class,
                            app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewResponseDto
                                .serializer()
                        )
                    }
            }
        )
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception processing request: {}", cause.message, cause)
            call.respond<WebhookReceiptDto>(
                HttpStatusCode.InternalServerError,
                WebhookReceiptDto(
                    status = "error",
                    message = "An unexpected error occurred while processing the request"
                )
            )
        }
    }

    configureWebhookRouting(
        eventRail = dependencies.eventRail,
        serverConfig = dependencies.config.server,
        providerRegistry = dependencies.providerRegistry,
        healthController = dependencies.healthController,
        rateLimiter = dependencies.rateLimiter
    )
}

fun startServer(
    config: AppConfig,
    dependencies: AppDependencies = buildDependencies(config),
    wait: Boolean = true
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    if (config.server.authToken.isBlank()) {
        logger.warn(
            "⚠️ SERVER_AUTH_TOKEN is not configured! Inbound webhook endpoints are operating in unauthenticated mode."
        )
    }

    val server =
        embeddedServer(Netty, port = config.server.port, host = "0.0.0.0") {
            module(dependencies)
        }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("JVM shutdown initiated, stopping server gracefully...")
            server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            dependencies.close()
            logger.info("Server shutdown complete.")
        }
    )

    logger.info("Starting Media Webhook Notifier server on port {}", config.server.port)
    server.start(wait = wait)
    return server
}

fun main() {
    logger.info("Initializing Media Webhook Notifier...")
    val config = ConfigLoader.load()
    val dependencies = buildDependencies(config)
    startServer(config, dependencies, wait = true)
}
