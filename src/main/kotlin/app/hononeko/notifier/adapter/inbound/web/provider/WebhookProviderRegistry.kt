package app.hononeko.notifier.adapter.inbound.web.provider

class WebhookProviderRegistry(
    providers: List<WebhookProviderStrategy> = defaultProviders()
) {
    private val providerMap: Map<String, WebhookProviderStrategy> =
        buildMap {
            for (provider in providers) {
                for (key in provider.providerKeys) {
                    put(key.lowercase(), provider)
                }
            }
        }

    fun get(key: String): WebhookProviderStrategy? = providerMap[key.lowercase().trim()]

    fun supportedProviders(): Set<String> = providerMap.keys

    companion object {
        fun defaultProviders(): List<WebhookProviderStrategy> =
            listOf(
                SonarrWebhookProvider(),
                RadarrWebhookProvider(),
                ServarrWebhookProvider(),
                PlexWebhookProvider(),
                JellyfinWebhookProvider()
            )
    }
}
