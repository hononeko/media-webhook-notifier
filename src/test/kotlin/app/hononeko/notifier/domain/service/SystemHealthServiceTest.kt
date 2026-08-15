package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemHealthServiceTest {
    private class FakeNotificationPublisher(
        private val shouldFail: Boolean = false
    ) : NotificationPublisherPort {
        override val providerId: String = "fake"
        val sentCards = Collections.synchronizedList(mutableListOf<NotificationCard>())

        override suspend fun sendCard(
            card: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            if (shouldFail) {
                Either.Left(DomainError.NotificationError.DeliveryFailed("fake", "Delivery error"))
            } else {
                sentCards.add(card)
                Either.Right(NotificationHandle("fake", "chat1", "msg1"))
            }

        override suspend fun startLiveProgress(
            initialCard: NotificationCard
        ): Either<DomainError.NotificationError, NotificationHandle> =
            Either.Right(NotificationHandle("fake", "chat1", "msg1"))

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
    fun `should announce system health warnings successfully`() =
        runTest {
            val publisher = FakeNotificationPublisher()
            val service = SystemHealthService(publisher)

            val payload =
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    eventType = EventType.HEALTH_ISSUE,
                    level = "warning",
                    message = "Disk space below 10%",
                    type = "DiskSpace",
                    instanceName = "Sonarr-TV"
                )

            val result = service.announce(payload)
            assertTrue(result.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertEquals("⚠️ Health Warning: Sonarr-TV", publisher.sentCards.first().title)
        }

    @Test
    fun `should return error when notification publisher fails`() =
        runTest {
            val publisher = FakeNotificationPublisher(shouldFail = true)
            val service = SystemHealthService(publisher)

            val payload =
                MediaPayload.ServarrHealth(
                    source = AppSource.RADARR,
                    eventType = EventType.HEALTH_ISSUE,
                    level = "error",
                    message = "qBittorrent connection refused"
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }
}
