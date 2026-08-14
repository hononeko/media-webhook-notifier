package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.provider.JellyfinWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.PlexWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.RadarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.ServarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.SonarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookProviderRegistryTest {
    @Test
    fun `should register and resolve default webhook providers correctly`() {
        val registry = WebhookProviderRegistry()

        val sonarr = registry.get("sonarr")
        assertNotNull(sonarr)
        assertTrue(sonarr is SonarrWebhookProvider)

        val radarr = registry.get("radarr")
        assertNotNull(radarr)
        assertTrue(radarr is RadarrWebhookProvider)

        val servarr = registry.get("servarr")
        assertNotNull(servarr)
        assertTrue(servarr is ServarrWebhookProvider)

        val arr = registry.get("arr")
        assertNotNull(arr)
        assertTrue(arr is ServarrWebhookProvider)

        val plex = registry.get("plex")
        assertNotNull(plex)
        assertTrue(plex is PlexWebhookProvider)

        val jellyfin = registry.get("jellyfin")
        assertNotNull(jellyfin)
        assertTrue(jellyfin is JellyfinWebhookProvider)

        val emby = registry.get("emby")
        assertNotNull(emby)
        assertTrue(emby is JellyfinWebhookProvider)
    }

    @Test
    fun `should handle case insensitivity and whitespace in provider lookup`() {
        val registry = WebhookProviderRegistry()

        assertNotNull(registry.get("  SONARR  "))
        assertNotNull(registry.get("Plex"))
        assertNotNull(registry.get("JelLyFin"))
    }

    @Test
    fun `should return null for unknown provider`() {
        val registry = WebhookProviderRegistry()

        assertNull(registry.get("unknown-app"))
        assertNull(registry.get("netflix"))
    }

    @Test
    fun `should report all supported provider keys`() {
        val registry = WebhookProviderRegistry()
        val supported = registry.supportedProviders()

        assertTrue(supported.contains("sonarr"))
        assertTrue(supported.contains("radarr"))
        assertTrue(supported.contains("servarr"))
        assertTrue(supported.contains("plex"))
        assertTrue(supported.contains("jellyfin"))
        assertTrue(supported.contains("emby"))
    }

    @Test
    fun `should return schema definitions from providers`() {
        val sonarr = SonarrWebhookProvider()
        val sonarrSchema = sonarr.getSchema()
        assertNotNull(sonarrSchema)
        assertEquals("Sonarr", sonarrSchema["service"])

        val radarr = RadarrWebhookProvider()
        val radarrSchema = radarr.getSchema()
        assertNotNull(radarrSchema)
        assertEquals("Radarr", radarrSchema["service"])

        val servarr = ServarrWebhookProvider()
        val servarrSchema = servarr.getSchema()
        assertNotNull(servarrSchema)
        assertEquals("Servarr", servarrSchema["service"])

        val plex = PlexWebhookProvider()
        val plexSchema = plex.getSchema()
        assertNotNull(plexSchema)
        assertEquals("Plex Media Server", plexSchema["service"])

        val jellyfin = JellyfinWebhookProvider()
        val jellyfinSchema = jellyfin.getSchema()
        assertNotNull(jellyfinSchema)
        assertEquals("Jellyfin / Emby", jellyfinSchema["service"])
    }
}
