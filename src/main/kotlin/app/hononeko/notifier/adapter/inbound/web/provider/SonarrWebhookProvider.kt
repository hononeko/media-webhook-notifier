package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class SonarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.SONARR) {
    override val providerKeys: Set<String> = setOf("sonarr")

    override fun getSchema(): Map<String, Any> =
        mapOf(
            "service" to "Sonarr",
            "supportedEvents" to listOf("Grab", "Download", "Test"),
            "targetEndpoint" to "/api/v1/webhook/sonarr"
        )
}
