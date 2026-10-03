package app.hononeko.notifier.adapter.inbound.web.controller

import app.hononeko.notifier.adapter.inbound.web.DeadLetterCodec
import app.hononeko.notifier.adapter.inbound.web.DeadLetterEntry
import app.hononeko.notifier.adapter.inbound.web.DeadLetterReplayResult
import app.hononeko.notifier.adapter.inbound.web.DeadLetterStatus
import app.hononeko.notifier.adapter.inbound.web.EventRail
import app.hononeko.notifier.adapter.inbound.web.dto.WebhookReceiptDto
import app.hononeko.notifier.domain.model.MediaPayload
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

@Serializable
data class DeadLetterSummaryDto(
    val id: String,
    val timestamp: String,
    val provider: String,
    val instanceName: String? = null,
    val eventType: String,
    val errorMessage: String,
    val attemptCount: Int,
    val status: String,
    val resolvedAt: String? = null,
    val payloadPreview: String
)

@Serializable
data class DeadLetterListDto(
    val total: Int,
    val offset: Int,
    val limit: Int,
    val entries: List<DeadLetterSummaryDto>
)

@Serializable
data class DeadLetterDetailDto(
    val entry: DeadLetterSummaryDto,
    val payload: JsonElement,
    val stackTrace: String? = null
)

@Serializable
data class DeadLetterReplayDto(
    val status: String,
    val message: String,
    val entry: DeadLetterSummaryDto
)

@Serializable
data class DeadLetterClearDto(
    val status: String = "ok",
    val message: String,
    val cleared: Int
)

/** Admin endpoints for inspecting, replaying and clearing the event rail's dead letter queue. */
class DeadLetterController(
    private val eventRail: EventRail
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
        private const val PREVIEW_LENGTH = 160
    }

    private data class ListQuery(
        val limit: Int,
        val offset: Int,
        val status: DeadLetterStatus?
    )

    private val buffer get() = eventRail.deadLetterBuffer

    suspend fun handleList(call: ApplicationCall) {
        when (val query = parseListQuery(call.request.queryParameters)) {
            is Either.Left -> call.respondError(HttpStatusCode.BadRequest, query.value)
            is Either.Right -> {
                val (limit, offset, status) = query.value
                val matching =
                    buffer
                        .getEntries()
                        .asReversed()
                        .filter { status == null || it.status == status }
                call.respond<DeadLetterListDto>(
                    HttpStatusCode.OK,
                    DeadLetterListDto(
                        total = matching.size,
                        offset = offset,
                        limit = limit,
                        entries = matching.drop(offset).take(limit).map { it.toSummary() }
                    )
                )
            }
        }
    }

    suspend fun handleGet(call: ApplicationCall) {
        val id = call.parameters["id"].orEmpty()
        val entry = buffer.get(id)
        if (entry == null) {
            call.respondError(HttpStatusCode.NotFound, "Dead letter '$id' not found")
        } else {
            call.respond<DeadLetterDetailDto>(
                HttpStatusCode.OK,
                DeadLetterDetailDto(
                    entry = entry.toSummary(),
                    payload = DeadLetterCodec.json.encodeToJsonElement(MediaPayload.serializer(), entry.payload),
                    stackTrace = entry.stackTrace
                )
            )
        }
    }

    suspend fun handleReplay(call: ApplicationCall) {
        val id = call.parameters["id"].orEmpty()
        when (val result = eventRail.replayDeadLetter(id)) {
            is DeadLetterReplayResult.Replayed ->
                call.respond<DeadLetterReplayDto>(
                    HttpStatusCode.Accepted,
                    DeadLetterReplayDto(
                        status = "accepted",
                        message = "Dead letter re-queued for processing",
                        entry = result.entry.toSummary()
                    )
                )
            is DeadLetterReplayResult.AlreadyResolved ->
                call.respondError(HttpStatusCode.Conflict, "Dead letter '$id' has already been replayed")
            is DeadLetterReplayResult.Rejected ->
                call.respondError(HttpStatusCode.ServiceUnavailable, "Event rail queue is full or shutting down")
            DeadLetterReplayResult.NotFound ->
                call.respondError(HttpStatusCode.NotFound, "Dead letter '$id' not found")
        }
    }

    suspend fun handleClear(call: ApplicationCall) {
        val cleared = buffer.clear()
        call.respond<DeadLetterClearDto>(
            HttpStatusCode.OK,
            DeadLetterClearDto(message = "Cleared $cleared dead letter entries", cleared = cleared)
        )
    }

    private fun parseListQuery(parameters: Parameters): Either<String, ListQuery> =
        either {
            val limit = parameters["limit"]?.let { it.toIntOrNull() ?: raise("limit must be an integer") }
            val offset = parameters["offset"]?.let { it.toIntOrNull() ?: raise("offset must be an integer") }
            val rawStatus = parameters["status"]
            val status = rawStatus?.let { raw -> DeadLetterStatus.entries.firstOrNull { it.name.equals(raw, true) } }
            ensure(limit == null || limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
            ensure(offset == null || offset >= 0) { "offset must not be negative" }
            if (rawStatus != null) {
                ensureNotNull(status) { "status must be one of: pending, resolved" }
            }
            ListQuery(limit = limit ?: DEFAULT_LIMIT, offset = offset ?: 0, status = status)
        }

    private fun DeadLetterEntry.toSummary(): DeadLetterSummaryDto {
        val payloadJson = DeadLetterCodec.json.encodeToString(MediaPayload.serializer(), payload)
        return DeadLetterSummaryDto(
            id = id,
            timestamp = Instant.ofEpochMilli(timestamp).toString(),
            provider = payload.source.name.lowercase(),
            instanceName = payload.instanceName,
            eventType = payload.eventType.name,
            errorMessage = errorMessage,
            attemptCount = attemptCount,
            status = status.name.lowercase(),
            resolvedAt = resolvedAt?.let { Instant.ofEpochMilli(it).toString() },
            payloadPreview =
                if (payloadJson.length > PREVIEW_LENGTH) payloadJson.take(PREVIEW_LENGTH) + "…" else payloadJson
        )
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        message: String
    ) {
        respond<WebhookReceiptDto>(status, WebhookReceiptDto(status = "error", message = message))
    }
}
