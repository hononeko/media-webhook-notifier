package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class SonarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.SONARR) {
    override val providerKeys: Set<String> = setOf("sonarr")

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/sonarr.json")
}
