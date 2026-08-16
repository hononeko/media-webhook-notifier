package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPublisherPortTest {
    private val minimalPublisher =
        object : NotificationPublisherPort {
            override val providerId: String = "test-pub"

            override suspend fun sendCard(
                card: NotificationCard
            ): Either<DomainError.NotificationError, NotificationHandle> =
                Either.Right(NotificationHandle("test-pub", "chat", "msg"))

            override suspend fun startLiveProgress(
                initialCard: NotificationCard
            ): Either<DomainError.NotificationError, NotificationHandle> =
                Either.Right(NotificationHandle("test-pub", "chat", "msg"))

            override suspend fun updateProgress(
                handle: NotificationHandle,
                update: ProgressUpdate
            ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

            override suspend fun completeProgress(
                handle: NotificationHandle,
                finalCard: NotificationCard
            ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)

            override suspend fun cancelProgress(
                handle: NotificationHandle,
                reasonCard: NotificationCard
            ): Either<DomainError.NotificationError, Unit> = Either.Right(Unit)
        }

    @Test
    fun `default supportsLiveProgress returns true`() {
        assertEquals("test-pub", minimalPublisher.providerId)
        assertTrue(minimalPublisher.supportsLiveProgress())
    }
}
