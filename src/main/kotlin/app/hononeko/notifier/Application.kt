package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import app.hononeko.notifier.adapter.inbound.web.configureWebhookRouting
import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.adapter.outbound.mediaserver.MediaServerAdapter
import app.hononeko.notifier.adapter.outbound.qbittorrent.QBittorrentClientAdapter
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.ConfigLoader
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.TorrentClientPort
import app.hononeko.notifier.domain.service.DownloadTrackerEngine
import app.hononeko.notifier.domain.service.IngestWebhookService
import app.hononeko.notifier.domain.service.MediaAvailableService
import app.hononeko.notifier.domain.service.MediaImportedService
import app.hononeko.notifier.domain.service.SeasonDebouncer
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
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("app.hononeko.notifier.Application")

data class AppDependencies(
    val config: AppConfig,
    val scope: CoroutineScope,
    val torrentClient: TorrentClientPort,
    val notificationPublisher: NotificationPublisherPort,
    val mediaServerPort: MediaServerPort,
    val downloadTracker: DownloadTrackerEngine,
    val seasonDebouncer: SeasonDebouncer,
    val mediaImportedService: MediaImportedService,
    val mediaAvailableService: MediaAvailableService,
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
    val torrentClient = QBittorrentClientAdapter(config = config.qbittorrent)
    val notificationPublisher = TelegramPublisherAdapter(config = config.notifications.telegram)
    val mediaServerPort = MediaServerAdapter(config = config.mediaServer)

    val downloadTracker =
        DownloadTrackerEngine(
            torrentClient = torrentClient,
            notificationPublisher = notificationPublisher,
            pollIntervalSeconds = config.qbittorrent.pollIntervalSeconds,
            maxPollingMinutes = config.qbittorrent.maxPollingMinutes,
            stalledTimeoutMinutes = config.qbittorrent.stalledTimeoutMinutes,
            webuiPublicUrl = config.qbittorrent.webuiPublicUrl,
            scope = scope
        )

    val seasonDebouncer =
        SeasonDebouncer(
            debounceMillis = 5000L,
            scope = scope,
            onDebouncedGrab = { grab ->
                downloadTracker.track(grab.downloadId, grab)
            }
        )

    val mediaImportedService =
        MediaImportedService(
            notificationPublisher = notificationPublisher
        )

    val mediaAvailableService =
        MediaAvailableService(
            notificationPublisher = notificationPublisher,
            mediaServerPort = mediaServerPort
        )

    val ingestWebhookService =
        IngestWebhookService(
            seasonDebouncer = seasonDebouncer,
            trackDownloadUseCase = downloadTracker,
            announceMediaImportedUseCase = mediaImportedService,
            announceMediaAvailableUseCase = mediaAvailableService
        )

    val eventRail = EventRail(capacity = 1000)
    eventRail.start(scope, ingestWebhookService)

    val rateLimiter = InboundRateLimiter(limitPerMinute = config.server.rateLimitPerMinute)
    val providerRegistry = WebhookProviderRegistry()
    val healthController =
        HealthController(
            eventRail = eventRail,
            downloadTracker = downloadTracker
        )

    return AppDependencies(
        config = config,
        scope = scope,
        torrentClient = torrentClient,
        notificationPublisher = notificationPublisher,
        mediaServerPort = mediaServerPort,
        downloadTracker = downloadTracker,
        seasonDebouncer = seasonDebouncer,
        mediaImportedService = mediaImportedService,
        mediaAvailableService = mediaAvailableService,
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
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
            }
        )
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception processing request: {}", cause.message, cause)
            call.respond(
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
