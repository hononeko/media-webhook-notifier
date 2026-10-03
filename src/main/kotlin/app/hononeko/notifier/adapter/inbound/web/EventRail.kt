package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

class EventRail(
    standardCapacity: Int = 1000,
    urgentCapacity: Int = 200,
    deadLetterCapacity: Int = 100
) {
    constructor(capacity: Int) : this(
        standardCapacity = capacity,
        urgentCapacity = (capacity / 5).coerceAtLeast(1),
        deadLetterCapacity = 100
    )

    /** A queued payload plus how many times it has been attempted (replays of dead letters increment it). */
    private data class RailEvent(
        val payload: MediaPayload,
        val attempt: Int
    )

    private val logger = LoggerFactory.getLogger(EventRail::class.java)
    private val standardChannel = Channel<RailEvent>(standardCapacity)
    private val urgentChannel = Channel<RailEvent>(urgentCapacity)
    private val consumerJobs = CopyOnWriteArrayList<Job>()

    val deadLetterBuffer = DeadLetterRingBuffer(capacity = deadLetterCapacity)

    val isClosed: Boolean
        get() = standardChannel.isClosedForSend && urgentChannel.isClosedForSend

    val isRunning: Boolean
        get() = consumerJobs.any { it.isActive }

    val activeWorkersCount: Int
        get() = consumerJobs.count { it.isActive }

    fun isUrgent(payload: MediaPayload): Boolean =
        payload is MediaPayload.ServarrHealth || payload is MediaPayload.ServarrManualInteraction

    fun publish(payload: MediaPayload): Boolean {
        val published = enqueue(RailEvent(payload, attempt = 1))
        if (!published) {
            val queueType = if (isUrgent(payload)) "urgent" else "standard"
            logger.warn(
                "Event rail {} buffer full or closed, dropped event: {} ({})",
                queueType,
                payload.eventType,
                payload.source
            )
            deadLetterBuffer.record(payload, "Buffer full or closed in $queueType channel")
        }
        return published
    }

    /**
     * Re-injects a pending dead letter and marks it resolved. A rail that is full or closed leaves the entry
     * pending instead of recording a duplicate dead letter.
     */
    fun replayDeadLetter(id: String): DeadLetterReplayResult {
        val result =
            deadLetterBuffer.replay(id) { entry ->
                enqueue(RailEvent(entry.payload, attempt = entry.attemptCount + 1))
            }
        when (result) {
            is DeadLetterReplayResult.Replayed ->
                logger.info(
                    "Replayed dead letter {} ({} {}, attempt {})",
                    id,
                    result.entry.payload.source,
                    result.entry.payload.eventType,
                    result.entry.attemptCount + 1
                )
            is DeadLetterReplayResult.Rejected -> logger.warn("Event rail rejected replay of dead letter {}", id)
            else -> Unit
        }
        return result
    }

    private fun enqueue(event: RailEvent): Boolean {
        val targetChannel = if (isUrgent(event.payload)) urgentChannel else standardChannel
        return targetChannel.trySend(event).isSuccess
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        scope: CoroutineScope,
        ingestService: IngestWebhookUseCase,
        workerCount: Int = 4
    ): Job {
        val count = workerCount.coerceAtLeast(1)
        logger.info("Starting EventRail with {} parallel workers and priority multiplexing", count)

        val parentJob =
            scope.launch {
                val workers =
                    (1..count).map { workerId ->
                        launch {
                            runWorker(workerId, ingestService)
                        }
                    }
                workers.joinAll()
            }

        consumerJobs.add(parentJob)
        return parentJob
    }

    private val hasOpenChannels: Boolean
        get() = !urgentChannel.isClosedForReceive || !standardChannel.isClosedForReceive

    private suspend fun CoroutineScope.runWorker(
        workerId: Int,
        ingestService: IngestWebhookUseCase
    ) {
        logger.debug("EventRail worker #{} started", workerId)
        while (isActive && hasOpenChannels) {
            val event = receiveNextEvent()
            if (event != null) {
                processEvent(event, ingestService)
            }
        }
        logger.debug("EventRail worker #{} stopped", workerId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun receiveNextEvent(): RailEvent? {
        val hasUrgent = !urgentChannel.isClosedForReceive
        val hasStandard = !standardChannel.isClosedForReceive
        if (!hasUrgent && !hasStandard) {
            return null
        }
        return try {
            select<RailEvent?> {
                if (hasUrgent) {
                    urgentChannel.onReceiveCatching { it.getOrNull() }
                }
                if (hasStandard) {
                    standardChannel.onReceiveCatching { it.getOrNull() }
                }
            }
        } catch (_: CancellationException) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processEvent(
        event: RailEvent,
        ingestService: IngestWebhookUseCase
    ) {
        val payload = event.payload
        try {
            val result = ingestService.execute(payload)
            if (result is Either.Left) {
                logger.warn(
                    "Ingest returned domain error for {} ({}): {}",
                    payload.eventType,
                    payload.source,
                    result.value
                )
                deadLetterBuffer.record(
                    payload = payload,
                    errorMessage = result.value.toString(),
                    attemptCount = event.attempt,
                    stackTrace = result.value.causeOrNull()?.stackTraceToString()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error processing payload from event rail: ${e.message}", e)
            deadLetterBuffer.record(
                payload = payload,
                errorMessage = e.message ?: "Unexpected exception",
                attemptCount = event.attempt,
                stackTrace = e.stackTraceToString()
            )
        }
    }

    fun close() {
        urgentChannel.close()
        standardChannel.close()
    }

    suspend fun join() {
        consumerJobs.forEach { it.join() }
    }
}

private fun DomainError.causeOrNull(): Throwable? =
    when (this) {
        is DomainError.TorrentClientError.ConnectionFailed -> cause
        is DomainError.NotificationError.DeliveryFailed -> cause
        is DomainError.NotificationError.ImageFetchFailed -> cause
        else -> null
    }
