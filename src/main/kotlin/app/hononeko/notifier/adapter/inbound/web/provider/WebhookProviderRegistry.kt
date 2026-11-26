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
        /** Every built-in provider; cross-seed only with [crossSeedEnabled] (`CROSS_SEED_ENABLED`), else it's a 404. */
        fun defaultProviders(crossSeedEnabled: Boolean = false): List<WebhookProviderStrategy> =
            listOfNotNull(
                SonarrWebhookProvider(),
                RadarrWebhookProvider(),
                ServarrWebhookProvider(),
                PlexWebhookProvider(),
                JellyfinWebhookProvider(),
                SeerrWebhookProvider(),
                CrossSeedWebhookProvider().takeIf { crossSeedEnabled }
            )
    }
}
