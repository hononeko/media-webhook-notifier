package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import arrow.core.Either

interface NotificationPublisherPort {
    val providerId: String

    val defaultChannelOrChatId: String get() = ""

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
     */
    suspend fun editCard(
        handle: NotificationHandle,
        card: NotificationCard
    ): Either<DomainError.NotificationError, Unit> {
        val reason = "editCard is not supported by $providerId"
        return Either.Left(DomainError.NotificationError.DeliveryFailed(providerId, reason))
    }

    suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit>
}
