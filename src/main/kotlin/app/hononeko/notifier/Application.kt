package app.hononeko.notifier

import app.hononeko.notifier.adapter.inbound.telegram.registerTelegramWebhookRoute
import app.hononeko.notifier.adapter.inbound.web.configureWebhookRouting
import app.hononeko.notifier.adapter.inbound.web.controller.DeadLetterClearDto
import app.hononeko.notifier.adapter.inbound.web.controller.DeadLetterDetailDto
import app.hononeko.notifier.adapter.inbound.web.controller.DeadLetterListDto
import app.hononeko.notifier.adapter.inbound.web.controller.DeadLetterReplayDto
import app.hononeko.notifier.adapter.inbound.web.controller.EventRailMetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.HealthStatusDto
import app.hononeko.notifier.adapter.inbound.web.controller.MemoryMetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.MetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.ProbeStatusDto
import app.hononeko.notifier.adapter.inbound.web.controller.ReconciliationMetricsDto
import app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewRequestDto
import app.hononeko.notifier.adapter.inbound.web.controller.TemplatePreviewResponseDto
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.ConfigLoader
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
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("app.hononeko.notifier.Application")

fun Application.module(dependencies: AppDependencies) {
    install(ForwardedHeaders)
    install(XForwardedHeaders)
    installCallLogging()
    installJson()
    installErrorPage()

    configureWebhookRouting(
        eventRail = dependencies.eventRail,
        serverConfig = dependencies.config.server,
        providerRegistry = dependencies.providerRegistry,
        healthController = dependencies.healthController,
        rateLimiter = dependencies.rateLimiter
    )

    dependencies.telegramInteractions?.controller?.let { controller ->
        routing { registerTelegramWebhookRoute(controller, dependencies.rateLimiter) }
    }
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

/** Logs requests, except the health probes and metrics scrapes that would drown everything else. */
private fun Application.installCallLogging() {
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
}

private fun Application.installJson() {
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
                        contextual(DeadLetterListDto::class, DeadLetterListDto.serializer())
                        contextual(DeadLetterDetailDto::class, DeadLetterDetailDto.serializer())
                        contextual(DeadLetterReplayDto::class, DeadLetterReplayDto.serializer())
                        contextual(DeadLetterClearDto::class, DeadLetterClearDto.serializer())
                        contextual(
                            TemplatePreviewRequestDto::class,
                            TemplatePreviewRequestDto
                                .serializer()
                        )
                        contextual(
                            TemplatePreviewResponseDto::class,
                            TemplatePreviewResponseDto
                                .serializer()
                        )
                    }
            }
        )
    }
}

/** Any unhandled exception becomes a generic 500, logged with its stack trace. */
private fun Application.installErrorPage() {
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
}
