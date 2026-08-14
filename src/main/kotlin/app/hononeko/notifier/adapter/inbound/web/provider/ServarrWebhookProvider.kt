package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class ServarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.SONARR) {
    override val providerKeys: Set<String> = setOf("servarr", "arr")

    override fun getSchema(): Map<String, Any> =
        mapOf(
            "service" to "Servarr",
            "supportedEvents" to listOf("Grab", "Download", "Test"),
            "targetEndpoint" to "/api/v1/webhook/servarr"
        )
}
