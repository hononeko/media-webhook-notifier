package app.hononeko.notifier

import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.MediaServerConfig
import app.hononeko.notifier.config.MediaServerStatusSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaServerStatusBootstrapTest {
    private fun dependencies(mediaServer: MediaServerConfig) =
        buildDependencies(AppConfig(mediaServer = mediaServer), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun `the monitor runs when it's on and there is a server to check`() {
        val deps =
            dependencies(
                MediaServerConfig(url = "http://127.0.0.1:9", status = MediaServerStatusSettings(enabled = true))
            )
        try {
            assertNotNull(deps.mediaServerStatus)
        } finally {
            deps.close()
        }
    }

    @Test
    fun `the monitor stays off by default and without a server URL`() {
        listOf(
            MediaServerConfig(url = "http://127.0.0.1:9"),
            MediaServerConfig(url = "", status = MediaServerStatusSettings(enabled = true))
        ).forEach { mediaServer ->
            val deps = dependencies(mediaServer)
            try {
                assertNull(deps.mediaServerStatus)
            } finally {
                deps.close()
            }
        }
    }
}
