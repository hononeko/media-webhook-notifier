package app.hononeko.notifier

import app.hononeko.notifier.config.ConfigLoader
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.hononeko.notifier.Application")

fun main() {
    logger.info("Starting Media Webhook Notifier...")
    val config = ConfigLoader.load()
    logger.info("Configuration loaded successfully. Server port: {}", config.server.port)
}
