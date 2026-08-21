package app.hononeko.notifier.adapter.inbound.web

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

    private val logger = LoggerFactory.getLogger(EventRail::class.java)
    private val standardChannel = Channel<MediaPayload>(standardCapacity)
    private val urgentChannel = Channel<MediaPayload>(urgentCapacity)
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
        val targetChannel = if (isUrgent(payload)) urgentChannel else standardChannel
        val result = targetChannel.trySend(payload)
        if (result.isFailure) {
            val queueType = if (isUrgent(payload)) "urgent" else "standard"
            logger.warn(
                "Event rail {} buffer full or closed, dropped event: {} ({})",
                queueType,
                payload.eventType,
                payload.source
            )
            deadLetterBuffer.record(payload, "Buffer full or closed in $queueType channel")
            return false
        }
        return true
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
                            logger.debug("EventRail worker #{} started", workerId)
                            while (isActive) {
                                val payload =
                                    try {
                                        select<MediaPayload?> {
                                            urgentChannel.onReceiveCatching { it.getOrNull() }
                                            standardChannel.onReceiveCatching { it.getOrNull() }
                                        }
                                    } catch (e: CancellationException) {
                                        break
                                    }

                                if (payload == null) {
                                    if (urgentChannel.isClosedForReceive && standardChannel.isClosedForReceive) {
                                        break
                                    }
                                    continue
                                }

                                try {
                                    val result = ingestService.execute(payload)
                                    if (result is Either.Left) {
                                        logger.warn(
                                            "Ingest returned domain error for {} ({}): {}",
                                            payload.eventType,
                                            payload.source,
                                            result.value
                                        )
                                        deadLetterBuffer.record(payload, result.value.toString())
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.error("Error processing payload from event rail: ${e.message}", e)
                                    deadLetterBuffer.record(payload, e.message ?: "Unexpected exception")
                                }
                            }
                            logger.debug("EventRail worker #{} stopped", workerId)
                        }
                    }
                workers.joinAll()
            }

        consumerJobs.add(parentJob)
        return parentJob
    }

    fun close() {
        urgentChannel.close()
        standardChannel.close()
    }
}
