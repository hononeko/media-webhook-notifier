package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.TelegramUpdatesMode
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.BotInteractionService
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Everything behind `TELEGRAM_INTERACTIVE_ENABLED`, built in one place so the application only has to decide whether
 * to create it. In webhook mode it registers the webhook and exposes [controller]; in polling mode it runs the poller.
 */
class TelegramInteractionModule private constructor(
    private val config: AppConfig,
    private val api: TelegramBotApi,
    internal val controller: TelegramCallbackController?,
    private val pollingService: TelegramPollingService?
) {
    private val logger = LoggerFactory.getLogger(TelegramInteractionModule::class.java)
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        val interaction = config.telegramInteraction
        logger.info("Telegram interactions enabled in {} mode", interaction.mode.key)
        jobs +=
            if (pollingService != null) {
                pollingService.start(scope)
            } else {
                scope.launch {
                    retryUntilSuccess("setWebhook", ExponentialBackoff(1.seconds, 60.seconds)) {
                        api.setWebhook(interaction.webhookEndpoint, interaction.webhookSecret)
                    }
                    logger.info("Telegram webhook registered at {}", interaction.webhookEndpoint)
                }
            }
    }

    /** Stops polling and registration. The webhook stays registered so Telegram queues clicks while the app is down. */
    fun close() {
        jobs.forEach { it.cancel() }
        api.close()
    }

    companion object {
        fun create(
            config: AppConfig,
            refRegistry: CallbackRefRegistry,
            publisher: NotificationPublisherPort,
            scope: CoroutineScope,
            engine: HttpClientEngine? = null
        ): TelegramInteractionModule {
            val interaction = config.telegramInteraction
            val api = TelegramBotApi(config.notifications.botToken, engine)
            val processor =
                TelegramCallbackProcessor(
                    guard = TelegramCallbackGuard(interaction.allowedUserIds, config.notifications.chatId),
                    useCase =
                        BotInteractionService(
                            handlers = listOf(AcknowledgeHandler(publisher, refRegistry)),
                            refRegistry = refRegistry
                        ),
                    api = api,
                    scope = scope
                )
            return when (interaction.mode) {
                TelegramUpdatesMode.WEBHOOK ->
                    TelegramInteractionModule(
                        config,
                        api,
                        controller = TelegramCallbackController(interaction.webhookSecret, processor, scope),
                        pollingService = null
                    )
                TelegramUpdatesMode.POLLING ->
                    TelegramInteractionModule(
                        config,
                        api,
                        controller = null,
                        pollingService = TelegramPollingService(api, processor, interaction.pollTimeoutSeconds)
                    )
            }
        }
    }
}
