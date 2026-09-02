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

    suspend fun cancelProgress(
        handle: NotificationHandle,
        reasonCard: NotificationCard
    ): Either<DomainError.NotificationError, Unit>
}
