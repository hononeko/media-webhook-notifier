package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.CrossSeedExtraDto
import app.hononeko.notifier.adapter.inbound.web.dto.CrossSeedWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** cross-seed v7 webhooks; registered only with `CROSS_SEED_ENABLED=true`. */
class CrossSeedWebhookProvider : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(CrossSeedWebhookProvider::class.java)

    // Lenient: a custom `payload` template in cross-seed's webhook entry can turn booleans and numbers into strings.
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    override val providerKeys: Set<String> = setOf("cross-seed", "crossseed", "cross_seed")

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val dto =
            try {
                json.decodeFromString(CrossSeedWebhookDto.serializer(), call.receiveText())
            } catch (e: SerializationException) {
                logger.warn("Failed to parse cross-seed webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: ${e.message}")
            }

        val extra = dto.extra
        val event =
            extra
                ?.event
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val instance = callerName?.ifBlank { null } ?: AppSource.CROSS_SEED.displayName
        return when {
            extra == null || event.isEmpty() ->
                WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: missing extra.event")
            event == "TEST" -> {
                logger.info("Handling cross-seed test webhook (instance: {})", instance)
                WebhookProcessResult.TestOk(instanceName = instance)
            }
            event == "RESULTS" -> processResults(extra, instance)
            else -> {
                logger.debug("Ignoring unsupported cross-seed event: {}", event)
                WebhookProcessResult.Ignored("cross-seed event '$event' is not supported", event)
            }
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/cross-seed.json")

    private fun processResults(
        extra: CrossSeedExtraDto,
        instance: String
    ): WebhookProcessResult {
        val name = extra.name?.trim()
        if (name.isNullOrEmpty()) {
            return WebhookProcessResult.InvalidPayload("Invalid cross-seed payload: RESULTS without extra.name")
        }
        val result =
            extra.result
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val eventType =
            RESULT_EVENTS[result]
                ?: return WebhookProcessResult.Ignored("cross-seed result '$result' is not announced", result)

        logger.info("Ingesting cross-seed {} for '{}' (instance: {})", result, name, instance)
        val searchee = extra.searchee
        val payload =
            MediaPayload.CrossSeedResult(
                eventType = eventType,
                name = name,
                trackers = extra.trackers.orEmpty().mapNotNull { it.trim().ifEmpty { null } },
                decisions = extra.decisions.orEmpty(),
                trigger = extra.source?.trim()?.ifEmpty { null },
                paused = extra.paused ?: (eventType == EventType.CROSS_SEED_SAVED),
                category = searchee?.category?.trim()?.ifEmpty { null },
                sizeBytes = searchee?.length?.takeIf { it > 0 },
                infoHashes = extra.infoHashes.orEmpty(),
                searcheeSource = searchee?.source,
                clientHost = searchee?.clientHost?.trim()?.ifEmpty { null },
                instanceName = instance
            )
        return WebhookProcessResult.Queued(payload, result)
    }

    private companion object {
        val RESULT_EVENTS =
            mapOf(
                "INJECTED" to EventType.CROSS_SEED_INJECTED,
                "SAVED" to EventType.CROSS_SEED_SAVED,
                "FAILURE" to EventType.CROSS_SEED_FAILED
            )
    }
}
