package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.ProgressUpdate
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaAvailableServiceTest {
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
    fun `should announce Plex and Jellyfin media items successfully`() =
        runTest {
            val publisher = FakeNotificationPublisher()
            val mediaServerPort = MediaServerPort { "https://plex.example.com/item" }
            val service = MediaAvailableService(publisher, mediaServerPort)

            val plexPayload =
                MediaPayload.PlexLibraryNew(
                    title = "Dune: Part Two",
                    year = 2024
                )

            val jellyfinPayload =
                MediaPayload.JellyfinItemAdded(
                    itemId = "item123",
                    title = "Severance"
                )

            val result1 = service.announce(plexPayload)
            val result2 = service.announce(jellyfinPayload)

            assertTrue(result1.isRight())
            assertTrue(result2.isRight())
            assertEquals(2, publisher.sentCards.size)
        }

    @Test
    fun `should return error when notification publisher fails`() =
        runTest {
            val failingPublisher = FakeNotificationPublisher(shouldFail = true)
            val service = MediaAvailableService(failingPublisher)

            val payload =
                MediaPayload.PlexLibraryNew(
                    title = "Dune: Part Two",
                    year = 2024
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }
}
