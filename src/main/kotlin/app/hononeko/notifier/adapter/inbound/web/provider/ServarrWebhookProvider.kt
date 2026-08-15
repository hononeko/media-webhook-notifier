package app.hononeko.notifier.adapter.inbound.web.provider

import app.hononeko.notifier.domain.model.AppSource

class ServarrWebhookProvider : AbstractServarrWebhookProvider(defaultSource = AppSource.SONARR) {
    override val providerKeys: Set<String> =
        setOf("servarr", "arr", "lidarr", "readarr", "bazarr", "prowlarr", "whisparr")

    override fun getSchemaJson(): String? = SchemaLoader.loadSchema("schemas/servarr.json")
}
