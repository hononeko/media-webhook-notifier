package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadClientConfigTest {
    @Test
    fun `should default to qbittorrent with default tracking settings`() {
        val config = ConfigLoader.load(emptyMap())

        assertEquals(DownloadClientConfig(), config.downloadClient)
        assertEquals(DownloadClientType.QBITTORRENT, config.downloadClient.type)
        assertEquals(QBittorrentConfig(), config.qbittorrent)
        assertEquals(TransmissionConfig(), config.transmission)
    }

    @Test
    fun `should keep existing QBITTORRENT variables working unchanged`() {
        val passwordFile = java.io.File.createTempFile("qbit_password_", ".txt")
        passwordFile.writeText("file-secret\n")
        passwordFile.deleteOnExit()

        val config =
            ConfigLoader.load(
                mapOf(
                    "QBITTORRENT_URL" to "http://qbittorrent:8080",
                    "QBITTORRENT_USERNAME" to "admin",
                    "QBITTORRENT_PASSWORD_FILE" to passwordFile.absolutePath,
                    "QBITTORRENT_POLL_INTERVAL_SECONDS" to "7",
                    "QBITTORRENT_MAX_POLLING_MINUTES" to "45",
                    "QBITTORRENT_STALLED_TIMEOUT_MINUTES" to "20",
                    "QBITTORRENT_MISSING_GRACE_ATTEMPTS" to "9",
                    "QBITTORRENT_DEBOUNCE_SECONDS" to "3",
                    "QBITTORRENT_WEBUI_PUBLIC_URL" to "https://qbit.example.com",
                    "QBITTORRENT_RECONCILIATION_ENABLED" to "false",
                    "QBITTORRENT_RECONCILIATION_INTERVAL_MINUTES" to "11",
                    "QBITTORRENT_TAG_PREFIX" to "mwn_tg_"
                )
            )

        assertEquals(QBittorrentConfig("http://qbittorrent:8080", "admin", "file-secret"), config.qbittorrent)
        assertEquals(
            DownloadClientConfig(
                type = DownloadClientType.QBITTORRENT,
                pollIntervalSeconds = 7,
                maxPollingMinutes = 45,
                stalledTimeoutMinutes = 20,
                missingGraceAttempts = 9,
                debounceSeconds = 3,
                webuiPublicUrl = "https://qbit.example.com",
                reconciliationEnabled = false,
                reconciliationIntervalMinutes = 11,
                tagPrefix = "mwn_tg_"
            ),
            config.downloadClient
        )
    }

    @Test
    fun `should prefer DOWNLOAD_CLIENT tracking variables over QBITTORRENT aliases`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "DOWNLOAD_CLIENT_POLL_INTERVAL_SECONDS" to "10",
                    "QBITTORRENT_POLL_INTERVAL_SECONDS" to "7",
                    "DOWNLOAD_CLIENT_TAG_PREFIX" to "mwn_new_",
                    "QBITTORRENT_TAG_PREFIX" to "mwn_old_",
                    "QBITTORRENT_DEBOUNCE_SECONDS" to "2"
                )
            )

        assertEquals(10, config.downloadClient.pollIntervalSeconds)
        assertEquals("mwn_new_", config.downloadClient.tagPrefix)
        assertEquals(2, config.downloadClient.debounceSeconds)
    }

    @Test
    fun `should select transmission from DOWNLOAD_CLIENT_TYPE and its own variables`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "DOWNLOAD_CLIENT_TYPE" to " Transmission ",
                    "TRANSMISSION_URL" to "http://nas:9091",
                    "TRANSMISSION_USERNAME" to "rpc",
                    "TRANSMISSION_PASSWORD" to "secret",
                    "QBITTORRENT_URL" to "http://qbittorrent:8080"
                )
            )

        assertEquals(DownloadClientType.TRANSMISSION, config.downloadClient.type)
        assertEquals(TransmissionConfig("http://nas:9091", "rpc", "secret"), config.transmission)
        assertEquals("http://qbittorrent:8080", config.qbittorrent.url)
    }

    @Test
    fun `should detect the client from DOWNLOAD_CLIENT_URL and use its credentials`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "DOWNLOAD_CLIENT_URL" to "transmissions://rpc:s3cret@seedbox.example.com/transmission/rpc",
                    "TRANSMISSION_URL" to "http://ignored:9091",
                    "TRANSMISSION_USERNAME" to "ignored",
                    "TRANSMISSION_PASSWORD" to "ignored"
                )
            )

        assertEquals(DownloadClientType.TRANSMISSION, config.downloadClient.type)
        assertEquals(
            TransmissionConfig("https://seedbox.example.com/transmission/rpc", "rpc", "s3cret"),
            config.transmission
        )
        assertEquals(QBittorrentConfig(), config.qbittorrent)
    }

    @Test
    fun `should fall back to client credential variables when DOWNLOAD_CLIENT_URL has none`() {
        val urlFile = java.io.File.createTempFile("download_client_url_", ".txt")
        urlFile.writeText("qbittorrent://qbit.media.svc:8080\n")
        urlFile.deleteOnExit()

        val config =
            ConfigLoader.load(
                mapOf(
                    "DOWNLOAD_CLIENT_URL_FILE" to urlFile.absolutePath,
                    "QBITTORRENT_URL" to "http://old-host:8080",
                    "QBITTORRENT_USERNAME" to "admin",
                    "QBITTORRENT_PASSWORD" to "adminadmin"
                )
            )

        assertEquals(DownloadClientType.QBITTORRENT, config.downloadClient.type)
        assertEquals(QBittorrentConfig("http://qbit.media.svc:8080", "admin", "adminadmin"), config.qbittorrent)
    }

    @Test
    fun `should combine an http DOWNLOAD_CLIENT_URL with an explicit DOWNLOAD_CLIENT_TYPE`() {
        val config =
            ConfigLoader.load(
                mapOf(
                    "DOWNLOAD_CLIENT_TYPE" to "transmission",
                    "DOWNLOAD_CLIENT_URL" to "http://nas:9091"
                )
            )

        assertEquals(DownloadClientType.TRANSMISSION, config.downloadClient.type)
        assertEquals("http://nas:9091", config.transmission.url)
    }

    @Test
    fun `should accept sabnzbd selection so the factory can report it as unsupported`() {
        val config = ConfigLoader.load(mapOf("DOWNLOAD_CLIENT_URL" to "sabnzbd://apikey@sab:8080"))

        assertEquals(DownloadClientType.SABNZBD, config.downloadClient.type)
    }

    @Test
    fun `should fail fast on unknown types and conflicting type and scheme`() {
        val unknown =
            assertFailsWith<IllegalArgumentException> { ConfigLoader.load(mapOf("DOWNLOAD_CLIENT_TYPE" to "deluge")) }
        assertTrue(unknown.message!!.contains("deluge"))

        val conflict =
            assertFailsWith<IllegalArgumentException> {
                ConfigLoader.load(
                    mapOf(
                        "DOWNLOAD_CLIENT_TYPE" to "qbittorrent",
                        "DOWNLOAD_CLIENT_URL" to "transmission://nas:9091"
                    )
                )
            }
        assertTrue(conflict.message!!.contains("conflicts"))

        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(mapOf("DOWNLOAD_CLIENT_URL" to "ftp://nas"))
        }
    }

    @Test
    fun `should not switch away from qbittorrent because another client's URL is present`() {
        // Shared .env files often carry variables meant for other containers.
        val config =
            ConfigLoader.load(
                mapOf("SABNZBD_URL" to "http://sab:8080", "TRANSMISSION_URL" to "http://nas:9091")
            )

        assertEquals(DownloadClientType.QBITTORRENT, config.downloadClient.type)
    }
}
