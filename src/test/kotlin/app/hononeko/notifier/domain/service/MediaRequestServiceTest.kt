package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.testing.RecordingPublisher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaRequestServiceTest {
    @Test
    fun `should publish media request notification cards successfully`() =
        runTest {
            val publisher = RecordingPublisher()
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
            val failingPublisher = RecordingPublisher(shouldFail = true)
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

    private val admins = listOf("1", "2")

    @Test
    fun `pending request goes only to admins, with Approve and Decline carrying the request id`() =
        runTest {
            val publisher = RecordingPublisher()

            val result = MediaRequestService(publisher, admins).announce(event(EventType.REQUEST_PENDING))

            assertTrue(result.isRight())
            assertEquals(emptyList(), publisher.sentCards, "the channel must stay free of decision cards")
            val (card, recipients) = publisher.directSends.single()
            assertEquals(admins, recipients)
            assertEquals(
                listOf(
                    CardInteraction("✅ Approve", "seerr_approve", mapOf("request_id" to "12")),
                    CardInteraction("❌ Decline", "seerr_decline", mapOf("request_id" to "12"))
                ),
                card.interactions
            )
        }

    @Test
    fun `new issue goes only to admins, with Resolve carrying the issue id`() =
        runTest {
            val publisher = RecordingPublisher()

            MediaRequestService(publisher, admins).announce(event(EventType.ISSUE_CREATED))

            assertEquals(emptyList(), publisher.sentCards)
            assertEquals(
                listOf(CardInteraction("✔️ Resolve", "seerr_resolve", mapOf("issue_id" to "7"))),
                publisher.directSends
                    .single()
                    .first.interactions
            )
        }

    @Test
    fun `one reachable admin is enough`() =
        runTest {
            val publisher = RecordingPublisher().apply { unreachable = setOf("1") }

            val result = MediaRequestService(publisher, admins).announce(event(EventType.REQUEST_PENDING))

            assertTrue(result.isRight())
            assertEquals(emptyList(), publisher.sentCards)
        }

    @Test
    fun `no reachable admin falls back to the channel without buttons`() =
        runTest {
            val publisher = RecordingPublisher().apply { unreachable = admins.toSet() }

            val result = MediaRequestService(publisher, admins).announce(event(EventType.REQUEST_PENDING))

            assertTrue(result.isRight())
            assertEquals(emptyList(), publisher.sentCards.single().interactions)
        }

    @Test
    fun `without recipients, an id, or on other events cards go to the channel as before`() =
        runTest {
            val cases =
                listOf(
                    emptyList<String>() to event(EventType.REQUEST_PENDING),
                    emptyList<String>() to event(EventType.ISSUE_CREATED),
                    admins to event(EventType.REQUEST_PENDING, requestId = null),
                    admins to event(EventType.ISSUE_CREATED, issueId = null),
                    admins to event(EventType.REQUEST_APPROVED)
                )
            for ((recipients, payload) in cases) {
                val publisher = RecordingPublisher()

                MediaRequestService(publisher, recipients).announce(payload)

                assertEquals(emptyList(), publisher.directSends, payload.eventType.name)
                assertEquals(emptyList(), publisher.sentCards.single().interactions, payload.eventType.name)
            }
        }

    @Test
    fun `failed request stays in the channel with only its Acknowledge button`() =
        runTest {
            val publisher = RecordingPublisher()

            MediaRequestService(publisher, admins).announce(event(EventType.REQUEST_FAILED))

            assertEquals(listOf(CardTemplates.ACKNOWLEDGE), publisher.sentCards.single().interactions)
        }
}
