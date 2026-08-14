package app.hononeko.notifier.adapter.inbound.web.provider

import io.ktor.server.application.ApplicationCall

interface WebhookProviderStrategy {
    /**
     * Unique provider identifier(s), e.g. ["sonarr"], ["radarr"], ["plex"], ["jellyfin"].
     */
    val providerKeys: Set<String>

    /**
     * Processes an incoming HTTP call, parsing and mapping provider-specific payloads into a domain result.
     */
    suspend fun process(
        call: ApplicationCall,
        callerName: String?
    ): WebhookProcessResult

    /**
     * Returns schema or documentation metadata for this webhook provider, if available.
     */
    fun getSchema(): Map<String, Any>? = null
}
