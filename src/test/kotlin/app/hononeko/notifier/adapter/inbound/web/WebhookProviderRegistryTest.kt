package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.inbound.web.provider.JellyfinWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.PlexWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.RadarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.SchemaLoader
import app.hononeko.notifier.adapter.inbound.web.provider.ServarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.SonarrWebhookProvider
import app.hononeko.notifier.adapter.inbound.web.provider.WebhookProviderRegistry
import kotlin.test.Test
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

        val lidarr = registry.get("lidarr")
        assertNotNull(lidarr)
        assertTrue(lidarr is ServarrWebhookProvider)

        val readarr = registry.get("readarr")
        assertNotNull(readarr)
        assertTrue(readarr is ServarrWebhookProvider)

        val prowlarr = registry.get("prowlarr")
        assertNotNull(prowlarr)
        assertTrue(prowlarr is ServarrWebhookProvider)

        val bazarr = registry.get("bazarr")
        assertNotNull(bazarr)
        assertTrue(bazarr is ServarrWebhookProvider)

        val whisparr = registry.get("whisparr")
        assertNotNull(whisparr)
        assertTrue(whisparr is ServarrWebhookProvider)

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
    fun `should return externalized JSON schema definitions from providers`() {
        val sonarr = SonarrWebhookProvider()
        val sonarrSchema = sonarr.getSchemaJson()
        assertNotNull(sonarrSchema)
        assertTrue(sonarrSchema.contains("Sonarr Webhook Payload"))

        val radarr = RadarrWebhookProvider()
        val radarrSchema = radarr.getSchemaJson()
        assertNotNull(radarrSchema)
        assertTrue(radarrSchema.contains("Radarr Webhook Payload"))

        val servarr = ServarrWebhookProvider()
        val servarrSchema = servarr.getSchemaJson()
        assertNotNull(servarrSchema)
        assertTrue(servarrSchema.contains("Servarr Generic Webhook Payload"))

        val plex = PlexWebhookProvider()
        val plexSchema = plex.getSchemaJson()
        assertNotNull(plexSchema)
        assertTrue(plexSchema.contains("Plex Webhook Payload"))

        val jellyfin = JellyfinWebhookProvider()
        val jellyfinSchema = jellyfin.getSchemaJson()
        assertNotNull(jellyfinSchema)
        assertTrue(jellyfinSchema.contains("Jellyfin Webhook Payload"))
    }

    @Test
    fun `should handle missing schema gracefully in SchemaLoader`() {
        val missing = SchemaLoader.loadSchema("schemas/non-existent.json")
        assertNull(missing)
    }
}
