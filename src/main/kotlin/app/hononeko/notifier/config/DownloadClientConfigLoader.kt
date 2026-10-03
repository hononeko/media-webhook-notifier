package app.hononeko.notifier.config

internal data class DownloadClientSettings(
    val downloadClient: DownloadClientConfig,
    val qbittorrent: QBittorrentConfig,
    val transmission: TransmissionConfig
)

/**
 * Resolves the active download client and its settings.
 *
 * The client type comes from `DOWNLOAD_CLIENT_TYPE`, else from the `DOWNLOAD_CLIENT_URL` scheme, else defaults to
 * qBittorrent so existing deployments keep working. `DOWNLOAD_CLIENT_URL` (and its user-info credentials) overrides
 * the selected client's own `<CLIENT>_URL` / credential variables. Tracking settings read `DOWNLOAD_CLIENT_*` first
 * and fall back to the historical `QBITTORRENT_*` names.
 */
internal object DownloadClientConfigLoader {
    fun load(reader: ConfigLoader.EnvReader): DownloadClientSettings {
        val endpoint =
            reader
                .getSecret(
                    "DOWNLOAD_CLIENT_URL",
                    "downloadClient.url"
                )?.let(DownloadClientUrlParser::parse)
        val type = resolveType(reader, endpoint)
        return DownloadClientSettings(
            downloadClient = loadTrackingSettings(reader, type),
            qbittorrent = loadQBittorrent(reader, endpoint.takeIf { type == DownloadClientType.QBITTORRENT }),
            transmission = loadTransmission(reader, endpoint.takeIf { type == DownloadClientType.TRANSMISSION })
        )
    }

    private fun resolveType(
        reader: ConfigLoader.EnvReader,
        endpoint: DownloadClientEndpoint?
    ): DownloadClientType {
        val explicit =
            reader.get("DOWNLOAD_CLIENT_TYPE", "downloadClient.type")?.let { raw ->
                requireNotNull(DownloadClientType.fromKey(raw)) {
                    "Unsupported DOWNLOAD_CLIENT_TYPE '$raw'; expected one of ${DownloadClientType.supportedKeys}"
                }
            }
        val schemeType = endpoint?.type
        require(explicit == null || schemeType == null || explicit == schemeType) {
            "DOWNLOAD_CLIENT_TYPE=${explicit?.key} conflicts with the DOWNLOAD_CLIENT_URL scheme ${schemeType?.key}://"
        }
        return explicit ?: schemeType ?: DownloadClientType.QBITTORRENT
    }

    @Suppress("SpreadOperator")
    private fun loadTrackingSettings(
        reader: ConfigLoader.EnvReader,
        type: DownloadClientType
    ): DownloadClientConfig {
        val defaults = DownloadClientConfig()
        return DownloadClientConfig(
            type = type,
            pollIntervalSeconds =
                reader.getLong(defaults.pollIntervalSeconds, *keys("POLL_INTERVAL_SECONDS", "pollIntervalSeconds")),
            maxPollingMinutes =
                reader.getLong(defaults.maxPollingMinutes, *keys("MAX_POLLING_MINUTES", "maxPollingMinutes")),
            stalledTimeoutMinutes =
                reader.getLong(
                    defaults.stalledTimeoutMinutes,
                    *keys("STALLED_TIMEOUT_MINUTES", "stalledTimeoutMinutes")
                ),
            missingGraceAttempts =
                reader.getInt(defaults.missingGraceAttempts, *keys("MISSING_GRACE_ATTEMPTS", "missingGraceAttempts")),
            debounceSeconds = reader.getLong(defaults.debounceSeconds, *keys("DEBOUNCE_SECONDS", "debounceSeconds")),
            webuiPublicUrl = reader.get(*keys("WEBUI_PUBLIC_URL", "webuiPublicUrl")) ?: defaults.webuiPublicUrl,
            reconciliationEnabled =
                reader.getBoolean(
                    defaults.reconciliationEnabled,
                    *keys("RECONCILIATION_ENABLED", "reconciliationEnabled")
                ),
            reconciliationIntervalMinutes =
                reader.getLong(
                    defaults.reconciliationIntervalMinutes,
                    *keys("RECONCILIATION_INTERVAL_MINUTES", "reconciliationIntervalMinutes")
                ),
            tagPrefix = reader.get(*keys("TAG_PREFIX", "tagPrefix")) ?: defaults.tagPrefix
        )
    }

    /** `DOWNLOAD_CLIENT_<SUFFIX>` first, then the backwards-compatible `QBITTORRENT_<SUFFIX>` aliases. */
    private fun keys(
        envSuffix: String,
        property: String
    ): Array<String> =
        arrayOf(
            "DOWNLOAD_CLIENT_$envSuffix",
            "downloadClient.$property",
            "QBITTORRENT_$envSuffix",
            "qbittorrent.$property"
        )

    private fun loadQBittorrent(
        reader: ConfigLoader.EnvReader,
        endpoint: DownloadClientEndpoint?
    ): QBittorrentConfig =
        QBittorrentConfig(
            url = endpoint?.baseUrl ?: reader.get("QBITTORRENT_URL", "qbittorrent.url") ?: QBittorrentConfig().url,
            username = endpoint?.username ?: reader.get("QBITTORRENT_USERNAME", "qbittorrent.username").orEmpty(),
            password =
                endpoint?.password ?: reader.getSecret("QBITTORRENT_PASSWORD", "qbittorrent.password").orEmpty()
        )

    private fun loadTransmission(
        reader: ConfigLoader.EnvReader,
        endpoint: DownloadClientEndpoint?
    ): TransmissionConfig =
        TransmissionConfig(
            url = endpoint?.baseUrl ?: reader.get("TRANSMISSION_URL", "transmission.url") ?: TransmissionConfig().url,
            username = endpoint?.username ?: reader.get("TRANSMISSION_USERNAME", "transmission.username").orEmpty(),
            password =
                endpoint?.password ?: reader.getSecret("TRANSMISSION_PASSWORD", "transmission.password").orEmpty()
        )
}
