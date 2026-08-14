package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class RadarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.RADARR) {
    override val providerKeys: Set<String> = setOf("radarr")

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/radarr.json")
}
