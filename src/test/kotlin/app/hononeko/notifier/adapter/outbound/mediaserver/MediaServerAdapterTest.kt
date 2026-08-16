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
        assertEquals(
            "https://plex.example.com/web/index.html#!/server/server-uuid-99/details?key=%2Flibrary%2Fmetadata%2F12345",
            link
        )
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
        assertEquals(
            "https://app.plex.tv/desktop/#!/server/server-uuid-99/details?key=%2Flibrary%2Fmetadata%2F12345",
            link
        )
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

    @Test
    fun `should handle edge cases in Plex and Jellyfin deep links`() {
        val plexAdapter = MediaServerAdapter(MediaServerConfig(type = "plex"))

        // Null ratingKey
        val noKeyPlex = MediaPayload.PlexLibraryNew(title = "Test", ratingKey = null)
        assertNull(plexAdapter.resolveDeepLink(noKeyPlex))

        // Rating key starting with /library/metadata/
        val fullKeyPlex = MediaPayload.PlexLibraryNew(title = "Test", ratingKey = "/library/metadata/999")
        assertEquals(
            "https://app.plex.tv/desktop/#!/server//details?key=%2Flibrary%2Fmetadata%2F999",
            plexAdapter.resolveDeepLink(fullKeyPlex)
        )

        // Rating key starting with /
        val slashKeyPlex = MediaPayload.PlexLibraryNew(title = "Test", ratingKey = "/other/key/999")
        assertEquals(
            "https://app.plex.tv/desktop/#!/server//details?key=%2Fother%2Fkey%2F999",
            plexAdapter.resolveDeepLink(slashKeyPlex)
        )

        val jfNoUrlAdapter = MediaServerAdapter(MediaServerConfig(type = "jellyfin", url = "", publicUrl = ""))
        val jfPayload = MediaPayload.JellyfinItemAdded(itemId = "item123", title = "Test")
        assertNull(jfNoUrlAdapter.resolveDeepLink(jfPayload))

        val jfBlankItemPayload = MediaPayload.JellyfinItemAdded(itemId = "", title = "Test")
        val jfAdapter = MediaServerAdapter(MediaServerConfig(type = "jellyfin", url = "http://localhost:8096"))
        assertNull(jfAdapter.resolveDeepLink(jfBlankItemPayload))

        // Deep link without serverId
        val linkWithoutServerId = jfAdapter.resolveDeepLink(jfPayload)
        assertEquals("http://localhost:8096/web/index.html#!/details?id=item123", linkWithoutServerId)
    }
}
