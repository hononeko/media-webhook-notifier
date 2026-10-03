package app.hononeko.notifier.adapter.outbound.downloadclient

import app.hononeko.notifier.adapter.outbound.qbittorrent.QBittorrentClientAdapter
import app.hononeko.notifier.adapter.outbound.transmission.TransmissionClientAdapter
import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.DownloadClientType
import app.hononeko.notifier.domain.port.outbound.DownloadClientPort
import io.ktor.client.engine.HttpClientEngine
import org.slf4j.LoggerFactory

/** Instantiates the [DownloadClientPort] adapter selected by `DOWNLOAD_CLIENT_TYPE` / `DOWNLOAD_CLIENT_URL`. */
object DownloadClientFactory {
    private val logger = LoggerFactory.getLogger(DownloadClientFactory::class.java)

    /** Fails fast at boot when the selected client has no adapter yet. */
    fun create(
        config: AppConfig,
        engine: HttpClientEngine? = null
    ): DownloadClientPort {
        val type = config.downloadClient.type
        val (client, url) =
            when (type) {
                DownloadClientType.QBITTORRENT ->
                    QBittorrentClientAdapter(config.qbittorrent, engine) to config.qbittorrent.url
                DownloadClientType.TRANSMISSION ->
                    TransmissionClientAdapter(config.transmission, engine) to config.transmission.url
                DownloadClientType.SABNZBD ->
                    error(
                        "DOWNLOAD_CLIENT_TYPE=sabnzbd is not supported yet; " +
                            "use one of: ${DownloadClientType.QBITTORRENT.key}, ${DownloadClientType.TRANSMISSION.key}"
                    )
            }
        logger.info("Using {} download client at {}", type.key, url)
        return client
    }
}
