package app.hononeko.notifier.config

import app.hononeko.notifier.domain.service.MediaServerStatusCards
import app.hononeko.notifier.domain.service.TemplateEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaServerStatusConfigTest {
    @Test
    fun `the monitor is off by default`() {
        val mediaServer = ConfigLoader.load(emptyMap()).mediaServer
        assertFalse(mediaServer.status.enabled)
        assertEquals(30, mediaServer.status.pollIntervalSeconds)
        assertEquals(90, mediaServer.status.graceSeconds)
        assertEquals(300, mediaServer.status.refreshSeconds)
        assertTrue(mediaServer.status.pinEnabled)
        assertEquals("Plex", mediaServer.displayName)
    }

    @Test
    fun `settings come from the environment`() {
        val mediaServer =
            ConfigLoader
                .load(
                    mapOf(
                        "MEDIA_SERVER_TYPE" to "jellyfin",
                        "MEDIA_SERVER_STATUS_MONITOR_ENABLED" to "true",
                        "MEDIA_SERVER_STATUS_POLL_INTERVAL_SECONDS" to "15",
                        "MEDIA_SERVER_HEALTH_GRACE_SECONDS" to "60",
                        "MEDIA_SERVER_STATUS_REFRESH_SECONDS" to "120",
                        "MEDIA_SERVER_STATUS_PIN_ENABLED" to "false"
                    )
                ).mediaServer
        assertEquals(MediaServerStatusSettings(true, 15, 60, 120, false), mediaServer.status)
        assertEquals("Jellyfin", mediaServer.displayName)

        val named = ConfigLoader.load(mapOf("MEDIA_SERVER_NAME" to "Kerrlab Plex")).mediaServer
        assertEquals("Kerrlab Plex", named.displayName)
        assertEquals("Plex", named.typeName)
    }

    @Test
    fun `a non-positive interval fails boot only when the monitor is on`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(
                mapOf(
                    "MEDIA_SERVER_STATUS_MONITOR_ENABLED" to "true",
                    "MEDIA_SERVER_STATUS_POLL_INTERVAL_SECONDS" to "0"
                )
            )
        }
        ConfigLoader.load(mapOf("MEDIA_SERVER_STATUS_POLL_INTERVAL_SECONDS" to "0"))
    }

    @Test
    fun `status templates don't replace the now available card`() {
        val templates = ConfigLoader.load(emptyMap()).templates
        assertEquals("🍿 {title} now available on {media_server_name}", templates.events["media_available"]?.title)
        assertEquals("🍿 {title} now available on {media_server_name}", templates.events["available"]?.title)
        assertNotNull(templates.events["media_server.status_online"])
        assertNotNull(templates.events["media_server.status_offline"])
    }

    @Test
    fun `a flat status template is found under its short name`() {
        val engine =
            TemplateEngine(
                YamlParser.parseTemplateConfig("events:\n  status_offline:\n    title: 'DOWN {media_server_name}'")
            )
        val card =
            MediaServerStatusCards.build(
                MediaServerStatusCards.StatusView(
                    "Plex",
                    "Plex",
                    online = false,
                    since = 0,
                    downtimeMillis = 0,
                    checkedAt = 0
                ),
                engine = engine
            )
        assertEquals("DOWN Plex", card.title)
    }

    @Test
    fun `the default templates render the status cards`() {
        val engine = TemplateEngine(ConfigLoader.load(emptyMap()).templates)
        val view =
            MediaServerStatusCards.StatusView(
                serverName = "Kerrlab Plex",
                sourceName = "Plex",
                online = false,
                since = 0,
                downtimeMillis = 4_320_000,
                checkedAt = 4_320_000,
                error = "HTTP 503"
            )
        val card = MediaServerStatusCards.build(view, java.time.ZoneOffset.UTC, engine)
        assertEquals("🔴 Kerrlab Plex — Offline", card.title)
        assertTrue(card.silent)
        assertEquals(
            "▪ <b>Down since:</b> 00:00\n▪ <b>Downtime:</b> 1h 12m\n▪ <b>Error:</b> HTTP 503\n▪ <b>Last checked:</b> 01:12",
            card.customBody
        )
    }

    @Test
    fun `times on another day show the theme's date format`() {
        val day = 86_400_000L
        assertEquals(
            "1970-01-01 00:00",
            MediaServerStatusCards.formatTime(0, 2 * day, java.time.ZoneOffset.UTC, "yyyy-MM-dd HH:mm")
        )
        assertEquals("00:00", MediaServerStatusCards.formatTime(0, 1000, java.time.ZoneOffset.UTC, "yyyy-MM-dd HH:mm"))
    }

    @Test
    fun `values in the status body are HTML-escaped, the title is left to the publisher`() {
        val view =
            MediaServerStatusCards.StatusView(
                serverName = "Plex & Co",
                sourceName = "Plex",
                online = false,
                since = 0,
                downtimeMillis = 0,
                checkedAt = 0,
                error = "Unexpected <html> from proxy"
            )
        val card =
            MediaServerStatusCards.build(
                view,
                java.time.ZoneOffset.UTC,
                TemplateEngine(ConfigLoader.load(emptyMap()).templates)
            )
        assertEquals("🔴 Plex & Co — Offline", card.title)
        assertTrue(card.customBody!!.contains("▪ <b>Error:</b> Unexpected &lt;html&gt; from proxy"), card.customBody)

        val custom =
            TemplateEngine(
                YamlParser.parseTemplateConfig("events:\n  status_offline:\n    body: '{media_server_name}: {error}'")
            )
        assertEquals(
            "Plex &amp; Co: Unexpected &lt;html&gt; from proxy",
            MediaServerStatusCards.build(view, engine = custom).customBody
        )
    }
}
