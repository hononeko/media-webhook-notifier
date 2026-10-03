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

    fun parse(raw: String): DownloadClientEndpoint {
        val uri =
            try {
                URI(raw.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Invalid DOWNLOAD_CLIENT_URL: ${e.reason}", e)
            }
        val scheme =
            requireNotNull(uri.scheme?.lowercase()) {
                "DOWNLOAD_CLIENT_URL must start with a scheme, e.g. qbittorrent:// or transmission://"
            }
        require(!uri.host.isNullOrBlank()) { "DOWNLOAD_CLIENT_URL must include a host" }

        val (type, httpScheme) = resolveScheme(scheme)
        val (username, password) = parseUserInfo(uri.rawUserInfo)
        val queryApiKey = queryParameter(uri.rawQuery, "apikey")

        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return DownloadClientEndpoint(
            type = type,
            baseUrl = "$httpScheme://${uri.host}$port$path",
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
