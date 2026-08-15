package app.hononeko.notifier.adapter.outbound.mediaserver

import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaServerAdapterTest {
    @Test
    fun `should resolve Plex deep link correctly`() {
        val config =
            MediaServerConfig(
                type = "plex",
                publicUrl = "https://plex.example.com"
            )
        val adapter = MediaServerAdapter(config)

        val payload =
            MediaPayload.PlexLibraryNew(
                title = "Dune: Part Two",
                ratingKey = "12345",
                serverMachineIdentifier = "server-uuid-99"
            )

        val link = adapter.resolveDeepLink(payload)
        assertEquals("https://plex.example.com/web/index.html#!/server/server-uuid-99/details?key=12345", link)
    }

    @Test
    fun `should resolve Plex default deep link when no custom url set`() {
        val config = MediaServerConfig(type = "plex")
        val adapter = MediaServerAdapter(config)

        val payload =
            MediaPayload.PlexLibraryNew(
                title = "Dune: Part Two",
                ratingKey = "12345",
                serverMachineIdentifier = "server-uuid-99"
            )

        val link = adapter.resolveDeepLink(payload)
        assertEquals("https://app.plex.tv/desktop#!/server/server-uuid-99/details?key=12345", link)
    }

    @Test
    fun `should resolve Jellyfin deep link correctly`() {
        val config =
            MediaServerConfig(
                type = "jellyfin",
                publicUrl = "https://jellyfin.example.com"
            )
        val adapter = MediaServerAdapter(config)

        val payload =
            MediaPayload.JellyfinItemAdded(
                itemId = "item-777",
                title = "Severance",
                serverId = "jf-srv-1"
            )

        val link = adapter.resolveDeepLink(payload)
        assertEquals("https://jellyfin.example.com/web/index.html#!/details?id=item-777&serverId=jf-srv-1", link)
    }

    @Test
    fun `should return existing deep link if payload already has one`() {
        val config = MediaServerConfig(type = "plex")
        val adapter = MediaServerAdapter(config)

        val payload =
            MediaPayload.PlexLibraryNew(
                title = "Test",
                deepLinkUrl = "https://custom.link/watch"
            )

        val link = adapter.resolveDeepLink(payload)
        assertEquals("https://custom.link/watch", link)
    }

    @Test
    fun `should return null for non-mediaserver payloads`() {
        val adapter = MediaServerAdapter(MediaServerConfig())
        val grabPayload =
            MediaPayload.ArrGrab(
                source = AppSource.SONARR,
                downloadId = "hash",
                title = "Test",
                seriesOrMovieTitle = "Test"
            )

        assertNull(adapter.resolveDeepLink(grabPayload))
    }
}
