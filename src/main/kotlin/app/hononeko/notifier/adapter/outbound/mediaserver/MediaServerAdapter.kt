package app.hononeko.notifier.adapter.outbound.mediaserver

import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.MediaServerPort

class MediaServerAdapter(
    private val config: MediaServerConfig
) : MediaServerPort {
    override fun resolveDeepLink(payload: MediaPayload): String? =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> resolvePlexDeepLink(payload)
            is MediaPayload.JellyfinItemAdded -> resolveJellyfinDeepLink(payload)
            else -> null
        }

    private fun resolvePlexDeepLink(payload: MediaPayload.PlexLibraryNew): String? {
        if (!payload.deepLinkUrl.isNullOrBlank()) {
            return payload.deepLinkUrl
        }

        val ratingKey = payload.ratingKey ?: return null
        val serverId = payload.serverMachineIdentifier ?: ""

        val plexBase =
            config.plexPublicUrl.ifBlank { null }
                ?: config.baseUrl.ifBlank { null }
                ?: "https://app.plex.tv/desktop"

        val normalizedBase = plexBase.trimEnd('/')
        return if (normalizedBase.contains("app.plex.tv")) {
            "$normalizedBase#!/server/$serverId/details?key=$ratingKey"
        } else {
            "$normalizedBase/web/index.html#!/server/$serverId/details?key=$ratingKey"
        }
    }

    private fun resolveJellyfinDeepLink(payload: MediaPayload.JellyfinItemAdded): String? {
        if (!payload.deepLinkUrl.isNullOrBlank()) {
            return payload.deepLinkUrl
        }

        val itemId = payload.itemId
        if (itemId.isBlank()) return null

        val serverBase =
            config.jellyfinPublicUrl.ifBlank { null }
                ?: config.baseUrl.ifBlank { null }
                ?: return null

        val normalizedBase = serverBase.trimEnd('/')
        val serverParam = if (!payload.serverId.isNullOrBlank()) "&serverId=${payload.serverId}" else ""
        return "$normalizedBase/web/index.html#!/details?id=$itemId$serverParam"
    }
}
