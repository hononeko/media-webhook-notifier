package app.hononeko.notifier.testing

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [NotificationPublisherPort] for service tests: records every card it's given and answers with fixed
 * handles. [shouldFail] fails `sendCard`, [shouldFailStart] fails `startLiveProgress` (as a rate limit), and
 * [unreachable] recipients fail `sendDirect` the way Telegram does for users who never started the bot.
 */
class RecordingPublisher(
    override val providerId: String = "fake",
    private val chatId: String = "chat1",
    private val sentMessageId: String = "msg1",
    private val liveMessageId: String = "msg1",
    private val shouldFail: Boolean = false,
    private val shouldFailStart: Boolean = false,
    var unreachable: Set<String> = emptySet()
) : NotificationPublisherPort {
    override val defaultChannelOrChatId: String = chatId

    /** Cards sent with `sendCard`, plus the first card of each live progress message. */
    val sentCards: MutableList<NotificationCard> = CopyOnWriteArrayList()
    val directSends: MutableList<Pair<NotificationCard, List<String>>> = CopyOnWriteArrayList()
    val progressUpdates: MutableList<ProgressUpdate> = CopyOnWriteArrayList()
    val completedCards: MutableList<NotificationCard> = CopyOnWriteArrayList()
    val cancelledCards: MutableList<NotificationCard> = CopyOnWriteArrayList()

    /** Every card that reached the chat in any form: sent, completed or cancelled. */
    val allCards: List<NotificationCard> get() = sentCards + completedCards + cancelledCards

    override suspend fun sendCard(card: NotificationCard): Either<DomainError.NotificationError, NotificationHandle> {
        if (shouldFail) return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, "Delivery error"))
        sentCards += card
        return Either.Right(NotificationHandle(providerId, chatId, sentMessageId))
    }

    override suspend fun sendDirect(
        card: NotificationCard,
        recipients: List<String>
    ): Map<String, Either<DomainError.NotificationError, NotificationHandle>> {
        directSends += card to recipients
        return recipients.associateWith {
            if (it in unreachable) {
                Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, "Forbidden: bot can't initiate"))
            } else {
                Either.Right(NotificationHandle(providerId, it, "dm-$it"))
            }
        }
    }

    override suspend fun startLiveProgress(
        initialCard: NotificationCard
    ): Either<DomainError.NotificationError, NotificationHandle> {
        if (shouldFailStart) return Either.Left(DomainError.NotificationError.RateLimited(providerId, 30))
        sentCards += initialCard
        return Either.Right(NotificationHandle(providerId, chatId, liveMessageId))
    }

    override suspend fun updateProgress(
        handle: NotificationHandle,
        update: ProgressUpdate
    ): Either<DomainError.NotificationError, Unit> {
        progressUpdates += update
        return Either.Right(Unit)
    }

    override suspend fun completeProgress(
        handle: NotificationHandle,
        finalCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        completedCards += finalCard
        return Either.Right(Unit)
    }

    override suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        cancelledCards += reasonCard
        return Either.Right(Unit)
    }
}
