package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramCallbackData
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Inline keyboards for cards: URL buttons for links, and, with [callbackRefs] set, callback buttons backed by a ref to
 * the card. Links with a scheme other than http(s) or tg are dropped.
 */
internal class TelegramKeyboards(
    private val callbackRefs: CallbackRefRegistry?
) {
    // Logged under the publisher's name, where the keyboards used to be built.
    private val logger = LoggerFactory.getLogger(TelegramPublisherAdapter::class.java)

    private val allowedUrlSchemes = setOf("https", "http", "tg")

    /** URL buttons only, in one row; `null` when there are none. */
    fun forActions(actions: List<ActionLink>): InlineKeyboardMarkup? =
        urlButtons(actions).takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(inlineKeyboard = listOf(it)) }

    /** [card]'s keyboard, registering a fresh callback ref for its interactions. */
    suspend fun forCard(card: NotificationCard): InlineKeyboardMarkup? = forCard(card, registerRef(card))

    /** URL buttons in the first row, callback buttons in the second. Either row is left out when empty. */
    fun forCard(
        card: NotificationCard,
        ref: String?
    ): InlineKeyboardMarkup? {
        val rows = listOf(urlButtons(card.actions), callbackButtons(card, ref)).filter { it.isNotEmpty() }
        return rows.takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(inlineKeyboard = it) }
    }

    /** A ref to [card] for its callback buttons, or `null` when it has none or no ref could be stored. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun registerRef(card: NotificationCard): String? {
        val registry = callbackRefs ?: return null
        if (card.interactions.isEmpty()) return null
        val ref =
            try {
                registry.register(
                    CallbackTarget(card.copy(artworkBytes = null)),
                    TelegramPublisherAdapter.CALLBACK_TTL_SECONDS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Sending '{}' without its callback buttons: {}", card.title, e.message)
                return null
            }
        if (ref == null) {
            logger.warn("Sending '{}' without its callback buttons: no callback ref available", card.title)
        }
        return ref
    }

    private fun urlButtons(actions: List<ActionLink>): List<InlineKeyboardButton> =
        actions
            .filter { isValidUrlScheme(it.url) }
            .map { action -> InlineKeyboardButton(text = action.label, url = action.url) }

    private fun callbackButtons(
        card: NotificationCard,
        ref: String?
    ): List<InlineKeyboardButton> {
        if (ref == null) return emptyList()
        return card.interactions.mapNotNull { interaction ->
            TelegramCallbackData.encode(interaction.operation, ref)?.let { data ->
                InlineKeyboardButton(text = interaction.label, callbackData = data)
            }
        }
    }

    private fun isValidUrlScheme(url: String): Boolean {
        val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme in allowedUrlSchemes
    }
}
