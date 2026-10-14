package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotActor
import arrow.core.Either
import org.slf4j.LoggerFactory

/**
 * Edge authorization for button clicks: the clicking user must be in `TELEGRAM_ALLOWED_USERS` or
 * `TELEGRAM_ADMIN_USERS`, and the button must sit on a message in the configured chat or, for admins, in their own
 * private chat with the bot (where admin-only decisions are delivered). Rejected clicks never reach the domain.
 * Admins are marked on the [BotActor], so the domain can refuse admin-only actions to everyone else.
 */
internal class TelegramCallbackGuard(
    allowedUserIds: Set<Long>,
    private val chatId: String,
    private val adminUserIds: Set<Long> = emptySet()
) {
    private val authorizedUserIds = allowedUserIds + adminUserIds

    private val logger = LoggerFactory.getLogger(TelegramCallbackGuard::class.java)

    fun check(query: TelegramCallbackQuery): Either<DomainError.InteractionError.UnauthorizedUser, BotActor> {
        val userId = query.from.id
        val chat = query.message?.chat
        val reason =
            when {
                userId !in authorizedUserIds -> "user is not in TELEGRAM_ALLOWED_USERS or TELEGRAM_ADMIN_USERS"
                chat == null -> "callback has no message (inline-mode buttons are not supported)"
                !isConfiguredChat(chat) && !isAdminPrivateChat(chat, userId) ->
                    "chat ${chat.id} is neither the configured TELEGRAM_CHAT_ID nor the private chat of an admin"
                else -> null
            }
        if (reason != null) {
            logger.info("Rejected Telegram callback from user {}: {}", userId, reason)
            return Either.Left(DomainError.InteractionError.UnauthorizedUser(userId.toString(), reason))
        }
        val displayName = query.from.username?.let { "@$it" } ?: query.from.firstName
        return Either.Right(
            BotActor(
                platform = "telegram",
                id = userId.toString(),
                displayName = displayName,
                isAdmin = userId in adminUserIds
            )
        )
    }

    // A private chat's id is the user's id; a former admin's leftover buttons fail here once they're delisted.
    private fun isAdminPrivateChat(
        chat: TelegramChat,
        userId: Long
    ): Boolean = chat.id == userId && userId in adminUserIds

    // TELEGRAM_CHAT_ID may be a numeric id or a public "@channelname"; callbacks always carry the numeric id.
    private fun isConfiguredChat(chat: TelegramChat): Boolean {
        val configured = chatId.trim()
        return if (configured.startsWith("@")) {
            chat.username.equals(configured.removePrefix("@"), ignoreCase = true)
        } else {
            chat.id.toString() == configured
        }
    }
}
