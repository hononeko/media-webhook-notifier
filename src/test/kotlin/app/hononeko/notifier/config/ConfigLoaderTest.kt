package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigLoaderTest {
    @Test
    fun `should load default configuration from application yaml`() {
        val config = ConfigLoader.load()
        assertNotNull(config)
        assertEquals(8080, config.server.port)
        assertEquals("plex", config.mediaServer.type)
        assertEquals(5, config.qbittorrent.pollIntervalSeconds)
        assertEquals(30, config.qbittorrent.maxPollingMinutes)
        assertEquals(15, config.qbittorrent.stalledTimeoutMinutes)
        assertEquals(true, config.notifications.telegram.enabled)
        assertEquals(30, config.notifications.telegram.rateLimitPerMinute)
        assertEquals(5, config.notifications.telegram.timeoutSeconds)
    }
}
