package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.controller.HealthController
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProcessResult
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import app.hononeko.notifier.config.ServerConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureWebhookRouting(
    eventRail: EventRail,
    serverConfig: ServerConfig,
    providerRegistry: WebhookProviderRegistry = WebhookProviderRegistry(),
    healthController: HealthController = HealthController(),
    rateLimiter: InboundRateLimiter = InboundRateLimiter(serverConfig.rateLimitPerMinute)
) {
    routing {
        // Public health, probe & metrics endpoints
        get("/health") { healthController.handleHealth(call) }
        get("/healthz") { healthController.handleHealth(call) }
        get("/livez") { healthController.handleLiveness(call) }
        get("/health/live") { healthController.handleLiveness(call) }
        get("/readyz") { healthController.handleReadiness(call) }
        get("/health/ready") { healthController.handleReadiness(call) }
        get("/startupz") { healthController.handleStartup(call) }
        get("/health/startup") { healthController.handleStartup(call) }
        get("/metrics") { healthController.handleMetrics(call) }

        // Dynamic JSON schema retrieval: /schema/{provider}
        route("/schema") {
            get("/{provider}") {
                val providerKey = call.parameters["provider"]
                val schemaJson = providerKey?.let { providerRegistry.get(it)?.getSchemaJson() }

                if (schemaJson != null) {
                    call.respondText(schemaJson, ContentType.Application.Json)
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        WebhookReceiptDto(
                            status = "error",
                            message = "Unknown schema for provider '$providerKey'"
                        )
                    )
                }
            }
        }

        // Webhook ingestion endpoints: /api/v1/webhook/{provider} and /api/v1/webhook/{token}/{provider}
        route("/api/v1/webhook") {
            post {
                call.respond(
                    HttpStatusCode.BadRequest,
                    WebhookReceiptDto(
                        status = "error",
                        message =
                            "Missing webhook provider in URL path. " +
                                "Usage: /api/v1/webhook/{provider} or /api/v1/webhook/{token}/{provider}. " +
                                "Supported providers: ${providerRegistry.supportedProviders().sorted()}"
                    )
                )
            }

            registerDynamicWebhookEndpoint(
                serverConfig = serverConfig,
                rateLimiter = rateLimiter,
                eventRail = eventRail,
                providerRegistry = providerRegistry
            )

            route("/{token}") {
                registerDynamicWebhookEndpoint(
                    serverConfig = serverConfig,
                    rateLimiter = rateLimiter,
                    eventRail = eventRail,
                    providerRegistry = providerRegistry
                )
            }
        }
    }
}

private fun Route.registerDynamicWebhookEndpoint(
    serverConfig: ServerConfig,
    rateLimiter: InboundRateLimiter,
    eventRail: EventRail,
    providerRegistry: WebhookProviderRegistry
) {
    post("/{provider}") {
        val providerKey = call.parameters["provider"]
        val provider = providerKey?.let { providerRegistry.get(it) }

        if (provider == null) {
            call.respond(
                HttpStatusCode.NotFound,
                WebhookReceiptDto(
                    status = "error",
                    message =
                        "Unsupported webhook provider '$providerKey'. " +
                            "Supported providers: ${providerRegistry.supportedProviders().sorted()}"
                )
            )
            return@post
        }

        withAuthAndRateLimit(call, serverConfig.authToken, rateLimiter) {
            val callerName = AuthGuard.extractCallerName(call)
            when (val result = provider.process(call, callerName)) {
                is WebhookProcessResult.Queued -> {
                    val published = eventRail.publish(result.payload)
                    if (published) {
                        call.respond(
                            HttpStatusCode.Accepted,
                            WebhookReceiptDto(
                                status = "accepted",
                                message = "Webhook received and queued for processing",
                                eventType = result.eventType
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            WebhookReceiptDto(
                                status = "error",
                                message = "Event rail queue buffer full"
                            )
                        )
                    }
                }

                is WebhookProcessResult.TestOk -> {
                    call.respond(
                        HttpStatusCode.OK,
                        WebhookReceiptDto(
                            status = "ok",
                            message = "Test webhook received successfully",
                            eventType = "Test"
                        )
                    )
                }

                is WebhookProcessResult.Ignored -> {
                    call.respond(
                        HttpStatusCode.OK,
                        WebhookReceiptDto(
                            status = "ignored",
                            message = result.reason,
                            eventType = result.eventType
                        )
                    )
                }

                is WebhookProcessResult.InvalidPayload -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        WebhookReceiptDto(
                            status = "error",
                            message = result.errorMessage
                        )
                    )
                }
            }
        }
    }
}

private suspend inline fun withAuthAndRateLimit(
    call: ApplicationCall,
    expectedToken: String?,
    rateLimiter: InboundRateLimiter,
    crossinline block: suspend () -> Unit
) {
    if (!rateLimiter.tryAcquire(call)) {
        call.response.header(HttpHeaders.RetryAfter, "60")
        call.respond(
            HttpStatusCode.TooManyRequests,
            WebhookReceiptDto(
                status = "rate_limited",
                message = "Rate limit exceeded. Please try again later."
            )
        )
        return
    }

    if (!AuthGuard.isAuthorized(call, expectedToken)) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WebhookReceiptDto(
                status = "unauthorized",
                message = "Invalid or missing authentication token"
            )
        )
        return
    }

    block()
}
