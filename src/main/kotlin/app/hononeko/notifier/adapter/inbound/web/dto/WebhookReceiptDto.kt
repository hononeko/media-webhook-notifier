package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class WebhookReceiptDto(
    val status: String = "accepted",
    val message: String,
    val eventType: String? = null,
    val timestamp: String? = null
)
