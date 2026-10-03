package app.hononeko.notifier.config

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Connection details extracted from `DOWNLOAD_CLIENT_URL`. */
internal data class DownloadClientEndpoint(
    /** Client type implied by the URL scheme, or null for plain `http(s)://` URLs. */
    val type: DownloadClientType?,
    /** The client's HTTP(S) base URL with credentials and query removed. */
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null
)

/**
 * Parses download client URLs such as:
 * - `qbittorrent://user:pass@qbit:8080` (plain HTTP) or `qbittorrents://...` (HTTPS)
 * - `transmission://user:pass@nas:9091/transmission/rpc`
 * - `sabnzbd://<api-key>@sab:8080` or `sabnzbd://sab:8080?apikey=<api-key>`
 * - `http(s)://host:port`, where the client type comes from `DOWNLOAD_CLIENT_TYPE` instead
 *
 * Credentials in the user-info part must be percent-encoded.
 */
internal object DownloadClientUrlParser {
    private const val SECURE_SUFFIX = "s"
    private const val MAX_PORT = 65_535

    /** Host and port of the URL authority; [host] keeps IPv6 brackets. */
    private data class Authority(
        val userInfo: String?,
        val host: String,
        val port: Int?
    )

    // The URISyntaxException message echoes the whole input, credentials included, so it is neither
    // quoted nor chained as the cause of the boot failure.
    @Suppress("SwallowedException")
    fun parse(raw: String): DownloadClientEndpoint {
        val uri =
            try {
                URI(raw.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Invalid DOWNLOAD_CLIENT_URL: ${e.reason} at index ${e.index}")
            }
        val scheme =
            requireNotNull(uri.scheme?.lowercase()) {
                "DOWNLOAD_CLIENT_URL must start with a scheme, e.g. qbittorrent:// or transmission://"
            }

        val (type, httpScheme) = resolveScheme(scheme)
        val authority = parseAuthority(uri.rawAuthority)
        val (username, password) = parseUserInfo(authority.userInfo)
        val queryApiKey = queryParameter(uri.rawQuery, "apikey")

        val port = authority.port?.let { ":$it" }.orEmpty()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return DownloadClientEndpoint(
            type = type,
            baseUrl = "$httpScheme://${authority.host}$port$path",
            username = username.takeUnless { type == DownloadClientType.SABNZBD },
            password = password,
            apiKey = queryApiKey ?: username.takeIf { type == DownloadClientType.SABNZBD }
        )
    }

    private fun resolveScheme(scheme: String): Pair<DownloadClientType?, String> {
        val plainType = DownloadClientType.fromKey(scheme)
        val secureType = scheme.removeSuffix(SECURE_SUFFIX).takeIf { it != scheme }?.let(DownloadClientType::fromKey)
        return when {
            scheme == "http" || scheme == "https" -> null to scheme
            plainType != null -> plainType to "http"
            secureType != null -> secureType to "https"
            else -> throw IllegalArgumentException(
                "Unsupported DOWNLOAD_CLIENT_URL scheme '$scheme://'; expected one of " +
                    DownloadClientType.supportedKeys.joinToString { "$it://" } + " (append 's' for HTTPS) or http(s)://"
            )
        }
    }

    /**
     * Parsed by hand because [URI] treats hosts that are not RFC 2396 hostnames (e.g. Docker container
     * names with underscores such as `qbit_vpn`) as registry-based and reports no host, port or user-info.
     */
    private fun parseAuthority(rawAuthority: String?): Authority {
        require(!rawAuthority.isNullOrBlank()) { "DOWNLOAD_CLIENT_URL must include a host" }
        val at = rawAuthority.lastIndexOf('@')
        val userInfo = if (at == -1) null else rawAuthority.substring(0, at)
        val hostPort = rawAuthority.substring(at + 1)
        val portSeparator = if (hostPort.startsWith("[")) hostPort.indexOf("]:") + 1 else hostPort.lastIndexOf(':')
        val host = if (portSeparator > 0) hostPort.substring(0, portSeparator) else hostPort
        val rawPort = if (portSeparator > 0) hostPort.substring(portSeparator + 1) else ""
        require(host.isNotBlank() && host != "[]") { "DOWNLOAD_CLIENT_URL must include a host" }
        val port =
            rawPort.ifEmpty { null }?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { p -> p in 1..MAX_PORT }) {
                    "DOWNLOAD_CLIENT_URL has an invalid port '$it'"
                }
            }
        return Authority(userInfo, host, port)
    }

    private fun parseUserInfo(rawUserInfo: String?): Pair<String?, String?> {
        if (rawUserInfo.isNullOrEmpty()) return null to null
        val separator = rawUserInfo.indexOf(':')
        val user = if (separator == -1) rawUserInfo else rawUserInfo.substring(0, separator)
        val pass = if (separator == -1) null else rawUserInfo.substring(separator + 1)
        return decode(user).ifEmpty { null } to pass?.let(::decode)
    }

    private fun queryParameter(
        rawQuery: String?,
        name: String
    ): String? =
        rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.first().equals(name, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let(::decode)
            ?.ifBlank { null }

    // URLDecoder treats '+' as a space, which is wrong for URI components.
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}
