package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadClientUrlParserTest {
    @Test
    fun `should detect client type from scheme and map to http or https`() {
        val qbit = DownloadClientUrlParser.parse("qbittorrent://qbit.media.svc:8080")
        assertEquals(DownloadClientType.QBITTORRENT, qbit.type)
        assertEquals("http://qbit.media.svc:8080", qbit.baseUrl)

        val secureTransmission = DownloadClientUrlParser.parse("transmissions://seedbox.example.com/transmission/rpc/")
        assertEquals(DownloadClientType.TRANSMISSION, secureTransmission.type)
        assertEquals("https://seedbox.example.com/transmission/rpc", secureTransmission.baseUrl)

        val secureQbit = DownloadClientUrlParser.parse("QBITTORRENTS://box.example.com/qbittorrent")
        assertEquals(DownloadClientType.QBITTORRENT, secureQbit.type)
        assertEquals("https://box.example.com/qbittorrent", secureQbit.baseUrl)

        val sab = DownloadClientUrlParser.parse("sabnzbd://sab:8080")
        assertEquals(DownloadClientType.SABNZBD, sab.type)
        assertEquals("http://sab:8080", sab.baseUrl)
    }

    @Test
    fun `should leave the type unset for plain http urls`() {
        val endpoint = DownloadClientUrlParser.parse("https://user:pw@qbit.example.com:8443")

        assertNull(endpoint.type)
        assertEquals("https://qbit.example.com:8443", endpoint.baseUrl)
        assertEquals("user", endpoint.username)
        assertEquals("pw", endpoint.password)
    }

    @Test
    fun `should decode percent-encoded credentials without treating plus as space`() {
        val endpoint = DownloadClientUrlParser.parse("transmission://rpc%40user:p%3Ass+w%2Fd@nas:9091")

        assertEquals("rpc@user", endpoint.username)
        assertEquals("p:ss+w/d", endpoint.password)
        assertEquals("http://nas:9091", endpoint.baseUrl)
    }

    @Test
    fun `should read sabnzbd api key from user info or query`() {
        val fromUserInfo = DownloadClientUrlParser.parse("sabnzbd://abc123@sab:8080")
        assertEquals("abc123", fromUserInfo.apiKey)
        assertNull(fromUserInfo.username)

        val fromQuery = DownloadClientUrlParser.parse("sabnzbds://sab.example.com?apikey=xyz789")
        assertEquals("xyz789", fromQuery.apiKey)
        assertEquals("https://sab.example.com", fromQuery.baseUrl)
    }

    @Test
    fun `should reject malformed or unsupported urls with clear messages`() {
        val unsupported =
            assertFailsWith<IllegalArgumentException> { DownloadClientUrlParser.parse("deluge://box:8112") }
        assertTrue(unsupported.message!!.contains("deluge://"))
        assertTrue(unsupported.message!!.contains("qbittorrent://"))

        assertFailsWith<IllegalArgumentException> { DownloadClientUrlParser.parse("qbit.local:8080") }
        assertFailsWith<IllegalArgumentException> { DownloadClientUrlParser.parse("qbittorrent:///no-host") }
        assertFailsWith<IllegalArgumentException> { DownloadClientUrlParser.parse("qbittorrent://bad host") }
    }
}
