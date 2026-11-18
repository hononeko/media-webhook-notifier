package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import arrow.core.Either

interface NotificationPublisherPort {
    val providerId: String

    val defaultChannelOrChatId: String get() = ""

    val defaultTopicId: Long? get() = null

    fun supportsLiveProgress(): Boolean = true

    suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle>

    suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle>

    suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit>

    suspend fun completeProgress(
        handle: NotificationHandle,
        finalCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit>

    /**
     * Replaces the content and buttons of an already sent card. Publishers that can't edit messages report it as a
     * failure rather than pretending the edit happened.
     *
     * With [callbackRef], the card's remaining interactions stay bound to that existing ref instead of a new one, so
     * every copy of a card keeps pointing at the same target (whose deliveries list them all).
     */
    suspend fun editCard(
        handle: NotificationHandle,
        card: NotificationCard,
        callbackRef: String? = null
    ): Either<DomainError.NotificationError, Unit> {
        val reason = "editCard is not supported by $providerId"
        return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
    }

    /**
     * Sends [card] privately to each of [recipients] (for Telegram, user ids; each must have started a chat with the
     * bot). The copies share one callback ref whose target lists every delivered copy, so pressing a button on one
     * copy can update all of them. Returns each recipient's result.
     */
    suspend fun sendDirect(
        card: NotificationCard,
        recipients: List<String>
    ): Map<String, Either<DomainError.NotificationError, NotificationHandle>> {
        val failure =
            DomainError.NotificationError.DeliveryFailed(
                providerId,
                "direct messages are not supported by $providerId"
            )
        return recipients.associateWith { Either.Left(failure) }
    }

    /**
     * Pins an already sent message in its chat, notifying members only when [silent] is false. Publishers without
     * pinned messages report it as a failure.
     */
    suspend fun pinMessage(
        handle: NotificationHandle,
        silent: Boolean = true
    ): Either<DomainError.NotificationError, Unit> {
        val reason = "pinMessage is not supported by $providerId"
        return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
    }

    suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit>
}

internal data class ResolvedDestination(
    val chatId: String,
    val topicId: Long?
)

/**
 * Resolves a card's target `(chatId, topicId)` against this publisher's defaults: an overridden `chatId` never inherits
 * [NotificationPublisherPort.defaultTopicId], and an explicit `topicId <= 0` resets a global topic back to `null`.
 */
internal fun NotificationPublisherPort.resolveDestination(
    cardChatId: String?,
    cardTopicId: Long?
): ResolvedDestination {
    val overrideChat = cardChatId?.trim()?.takeIf { it.isNotEmpty() }
    val targetChat = overrideChat ?: defaultChannelOrChatId
    val chatOverridden = overrideChat != null && overrideChat != defaultChannelOrChatId
    val targetTopic =
        when {
            cardTopicId != null -> cardTopicId.takeIf { it > 0L }
            chatOverridden -> null
            else -> defaultTopicId
        }
    return ResolvedDestination(targetChat, targetTopic)
}
