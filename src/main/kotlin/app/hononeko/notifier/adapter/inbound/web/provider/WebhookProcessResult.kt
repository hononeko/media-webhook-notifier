package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.MediaPayload

sealed interface WebhookProcessResult {
    data class Queued(
        val payload: MediaPayload,
        val eventType: String? = null
    ) : WebhookProcessResult

    data class Ignored(
        val reason: String,
        val eventType: String? = null
    ) : WebhookProcessResult

    data class TestOk(
        val instanceName: String? = null
    ) : WebhookProcessResult

    data class InvalidPayload(
        val errorMessage: String
    ) : WebhookProcessResult
}
