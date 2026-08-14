package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class RadarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.RADARR) {
    override val providerKeys: Set<String> = setOf("radarr")

    override fun getSchema(): Map<String, Any> =
        mapOf(
            "service" to "Radarr",
            "supportedEvents" to listOf("Grab", "Download", "Test"),
            "targetEndpoint" to "/api/v1/webhook/radarr"
        )
}
