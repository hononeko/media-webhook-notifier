package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.CardInteraction
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

class MediaRequestServiceTest {
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
    fun `should publish media request notification cards successfully`() =
        runTest {
            val publisher = FakeNotificationPublisher()
            val service = MediaRequestService(publisher)

            val payload =
                MediaPayload.SeerrEvent(
                    source = AppSource.SEERR,
                    eventType = EventType.REQUEST_PENDING,
                    notificationType = "MEDIA_PENDING",
                    subject = "Dune: Part Two (2024)",
                    message = "A new request has been submitted by Admin.",
                    mediaType = "movie",
                    requestedByUsername = "Admin",
                    is4k = true,
                    webUrl = "https://seerr.example.com/movie/693134",
                    instanceName = "Jellyseerr"
                )

            val result = service.announce(payload)
            assertTrue(result.isRight())
            assertEquals(1, publisher.sentCards.size)
            assertEquals("🛎️ New Request: Dune: Part Two (2024)", publisher.sentCards.first().title)
            assertEquals("Jellyseerr • Request Pending", publisher.sentCards.first().subtitle)
        }

    @Test
    fun `should return error when publisher fails`() =
        runTest {
            val failingPublisher = FakeNotificationPublisher(shouldFail = true)
            val service = MediaRequestService(failingPublisher)

            val payload =
                MediaPayload.SeerrEvent(
                    source = AppSource.SEERR,
                    eventType = EventType.REQUEST_DECLINED,
                    notificationType = "MEDIA_DECLINED",
                    subject = "Test Movie"
                )

            val result = service.announce(payload)
            assertTrue(result.isLeft())
        }

    private fun event(
        type: EventType,
        requestId: Long? = 12,
        issueId: Long? = 7
    ) = MediaPayload.SeerrEvent(
        eventType = type,
        notificationType = type.name,
        subject = "Dune",
        requestId = requestId,
        issueId = issueId
    )

    private suspend fun interactionsFor(
        payload: MediaPayload.SeerrEvent,
        seerrActions: Boolean = true
    ): List<CardInteraction> {
        val publisher = FakeNotificationPublisher()
        MediaRequestService(publisher, seerrActions).announce(payload)
        return publisher.sentCards.single().interactions
    }

    @Test
    fun `pending request gets Approve and Decline buttons carrying the request id`() =
        runTest {
            assertEquals(
                listOf(
                    CardInteraction("✅ Approve", "seerr_approve", mapOf("request_id" to "12")),
                    CardInteraction("❌ Decline", "seerr_decline", mapOf("request_id" to "12"))
                ),
                interactionsFor(event(EventType.REQUEST_PENDING))
            )
        }

    @Test
    fun `new issue gets a Resolve button carrying the issue id`() =
        runTest {
            assertEquals(
                listOf(CardInteraction("✔️ Resolve", "seerr_resolve", mapOf("issue_id" to "7"))),
                interactionsFor(event(EventType.ISSUE_CREATED))
            )
        }

    @Test
    fun `no Seerr buttons when disabled, without an id, or on other events`() =
        runTest {
            assertEquals(emptyList(), interactionsFor(event(EventType.REQUEST_PENDING), seerrActions = false))
            assertEquals(emptyList(), interactionsFor(event(EventType.ISSUE_CREATED), seerrActions = false))
            assertEquals(emptyList(), interactionsFor(event(EventType.REQUEST_PENDING, requestId = null)))
            assertEquals(emptyList(), interactionsFor(event(EventType.ISSUE_CREATED, issueId = null)))
            assertEquals(emptyList(), interactionsFor(event(EventType.REQUEST_APPROVED)))
        }

    @Test
    fun `failed request keeps only its Acknowledge button`() =
        runTest {
            assertEquals(listOf(CardFormatterService.ACKNOWLEDGE), interactionsFor(event(EventType.REQUEST_FAILED)))
        }
}
