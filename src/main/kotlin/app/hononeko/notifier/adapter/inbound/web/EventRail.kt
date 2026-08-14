package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.inbound.IngestWebhookUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

class EventRail(
    capacity: Int = 1000
) {
    private val logger = LoggerFactory.getLogger(EventRail::class.java)
    private val channel = Channel<MediaPayload>(capacity)
    private var consumerJob: Job? = null

    fun publish(payload: MediaPayload): Boolean {
        val result = channel.trySend(payload)
        if (result.isFailure) {
            logger.warn("Event rail buffer full or closed, dropped event: {} ({})", payload.eventType, payload.source)
            return false
        }
        return true
    }

    fun start(
        scope: CoroutineScope,
        ingestService: IngestWebhookUseCase
    ): Job {
        val job =
            scope.launch {
                logger.info("EventRail consumer started")
                for (payload in channel) {
                    try {
                        ingestService.execute(payload)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error processing payload from event rail: ${e.message}", e)
                    }
                }
                logger.info("EventRail consumer stopped")
            }
        consumerJob = job
        return job
    }

    fun close() {
        channel.close()
    }
}
