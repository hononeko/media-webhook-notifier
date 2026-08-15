package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.adapter.inbound.web.dto.SeerrWebhookDto
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory

class SeerrWebhookProvider : WebhookProviderStrategy {
    private val logger = LoggerFactory.getLogger(SeerrWebhookProvider::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override val providerKeys: Set<String> = setOf("seerr", "overseerr", "jellyseerr")

    override suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult {
        val rawText =
            try {
                call.receiveText()
            } catch (e: Exception) {
                logger.warn("Failed to read Seerr webhook request body: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid request body: ${e.message}")
            }

        val dto =
            try {
                json.decodeFromString(SeerrWebhookDto.serializer(), rawText)
            } catch (e: Exception) {
                logger.warn("Failed to parse Seerr webhook payload: ${e.message}")
                return WebhookProcessResult.InvalidPayload("Invalid Seerr payload: ${e.message}")
            }

        val rawType = (dto.notificationType ?: dto.event)?.trim().orEmpty()
        val effectiveInstance = callerName?.ifBlank { null } ?: "Seerr"

        logger.info(
            "Ingesting Seerr webhook event: {} (instance: {})",
            rawType,
            effectiveInstance
        )

        val eventType = mapEventType(rawType)
        return when (eventType) {
            EventType.TEST -> {
                logger.info("Handling Seerr test webhook (instance: {})", effectiveInstance)
                WebhookProcessResult.TestOk(instanceName = effectiveInstance)
            }
            EventType.UNKNOWN -> {
                logger.debug("Ignoring unsupported Seerr notification type: {}", rawType)
                WebhookProcessResult.Ignored("Seerr notification type '$rawType' is not supported", rawType)
            }
            else -> {
                val payload = mapToSeerrEvent(dto, eventType, rawType, effectiveInstance)
                WebhookProcessResult.Queued(payload, rawType)
            }
        }
    }

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/seerr.json")

    private fun mapEventType(type: String): EventType =
        when (type.uppercase()) {
            "MEDIA_PENDING" -> EventType.REQUEST_PENDING
            "MEDIA_APPROVED" -> EventType.REQUEST_APPROVED
            "MEDIA_AUTO_APPROVED" -> EventType.REQUEST_AUTO_APPROVED
            "MEDIA_AVAILABLE" -> EventType.REQUEST_AVAILABLE
            "MEDIA_DECLINED" -> EventType.REQUEST_DECLINED
            "MEDIA_FAILED" -> EventType.REQUEST_FAILED
            "ISSUE_CREATED" -> EventType.ISSUE_CREATED
            "ISSUE_COMMENT" -> EventType.ISSUE_COMMENT
            "ISSUE_RESOLVED" -> EventType.ISSUE_RESOLVED
            "ISSUE_REOPENED" -> EventType.ISSUE_REOPENED
            "TEST_NOTIFICATION", "TEST" -> EventType.TEST
            else -> EventType.UNKNOWN
        }

    private fun mapToSeerrEvent(
        dto: SeerrWebhookDto,
        eventType: EventType,
        rawType: String,
        instanceName: String
    ): MediaPayload.SeerrEvent {
        val extraMap =
            dto.extra
                ?.mapNotNull { item ->
                    val name = item.name?.trim() ?: return@mapNotNull null
                    val value = item.value?.trim() ?: return@mapNotNull null
                    name to value
                }?.toMap() ?: emptyMap()

        val webUrl = dto.url?.ifBlank { null } ?: dto.applicationUrl?.ifBlank { null }

        val is4k =
            when (val el = dto.request?.is4k) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    el.booleanOrNull ?: el.content.equals("true", ignoreCase = true)
                }
                else -> false
            } ||
                (dto.media?.status4k != null && dto.media.status4k != "UNKNOWN" && dto.media.status4k.isNotBlank())

        return MediaPayload.SeerrEvent(
            source = AppSource.SEERR,
            eventType = eventType,
            notificationType = rawType,
            subject = dto.subject?.ifBlank { null } ?: "Media Request",
            message = dto.message?.ifBlank { null },
            image = dto.image?.ifBlank { null },
            mediaType = dto.media?.mediaType,
            imdbId = dto.media?.imdbId,
            tmdbId = dto.media?.tmdbId,
            tvdbId = dto.media?.tvdbId,
            jellyfinMediaId = dto.media?.jellyfinMediaId,
            requestedByUsername = dto.request?.requestedByUsername ?: extraMap["Requested By"],
            requestedByEmail = dto.request?.requestedByEmail,
            requestedByAvatar = dto.request?.requestedByAvatar,
            is4k = is4k,
            issueType = dto.issue?.issueType,
            issueStatus = dto.issue?.issueStatus,
            commentMessage = dto.comment?.commentMessage,
            extra = extraMap,
            webUrl = webUrl,
            instanceName = instanceName
        )
    }
}
