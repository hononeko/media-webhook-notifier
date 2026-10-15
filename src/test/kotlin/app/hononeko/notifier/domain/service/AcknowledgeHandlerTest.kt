package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.BotActor
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcknowledgeHandlerTest {
    private val publisher =
        mockk<NotificationPublisherPort> {
            every { providerId } returns "telegram"
            coEvery { editCard(any(), any()) } returns Either.Right(Unit)
        }
    private val registry = CallbackRefRegistry(InMemoryStateStore())
    private val handler =
        AcknowledgeHandler(
            publisher = publisher,
            refRegistry = registry,
            clock = { 1_760_000_000_000L },
            timeZone = TimeZone.of("Europe/Bucharest")
        )

    private val actor = BotActor(platform = "telegram", id = "42", displayName = "@alex")
    private val origin = NotificationHandle("telegram", "-1001", "5")
    private val card =
        NotificationCard(
            title = "Sonarr health issue",
            actions = listOf(ActionLink("📖 Wiki", "https://w")),
            interactions = listOf(CardInteraction("✅ Acknowledge", "ack"))
        )

    private suspend fun click(origin: NotificationHandle? = this.origin): Pair<String, Either<DomainError, BotReply>> {
        val ref = registry.register(CallbackTarget(card), ttlSeconds = 60)!!
        return ref to handler.handle(BotInteraction(actor, "ack", ref, origin), CallbackTarget(card))
    }

    @Test
    fun `stamps the card, drops interactions and keeps URL actions`() =
        runTest {
            val (_, result) = click()

            val stamped = card.copy(interactions = emptyList(), footnote = "✅ Handled by @alex · 11:53")
            coVerify(exactly = 1) { publisher.editCard(origin, stamped) }
            assertEquals(Either.Right(BotReply("✅ Acknowledged")), result)
        }

    @Test
    fun `releases the ref after a successful edit`() =
        runTest {
            val (ref, _) = click()

            assertNull(registry.resolve(ref))
        }

    @Test
    fun `keeps the ref and returns the error when the edit fails`() =
        runTest {
            val failure = DomainError.NotificationError.DeliveryFailed("telegram", "boom")
            coEvery { publisher.editCard(any(), any()) } returns Either.Left(failure)

            val (ref, result) = click()

            assertEquals(Either.Left(failure), result)
            assertNotNull(registry.resolve(ref))
        }

    @Test
    fun `returns an error without editing when origin is null`() =
        runTest {
            val (_, result) = click(origin = null)

            assertEquals(
                Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "no message to update")),
                result
            )
            coVerify(exactly = 0) { publisher.editCard(any(), any()) }
        }

    @Test
    fun `stamps every admin's copy of a card sent privately`() =
        runTest {
            val clicked = NotificationHandle("telegram", "1", "5")
            val other = NotificationHandle("telegram", "2", "8", isPhoto = true)
            val target = CallbackTarget(card, deliveries = listOf(clicked, other))
            val ref = registry.register(target, ttlSeconds = 60)!!

            val result = handler.handle(BotInteraction(actor, "ack", ref, clicked), target)

            val stamped = card.copy(interactions = emptyList(), footnote = "✅ Handled by @alex · 11:53")
            coVerify(exactly = 1) { publisher.editCard(clicked, stamped) }
            coVerify(exactly = 1) { publisher.editCard(other, stamped) }
            assertEquals(Either.Right(BotReply("✅ Acknowledged")), result)
        }

    @Test
    fun `a failed edit of another copy still acknowledges`() =
        runTest {
            val clicked = NotificationHandle("telegram", "1", "5")
            val other = NotificationHandle("telegram", "2", "8")
            coEvery { publisher.editCard(other, any()) } returns
                Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "blocked"))
            val target = CallbackTarget(card, deliveries = listOf(clicked, other))
            val ref = registry.register(target, ttlSeconds = 60)!!

            val result = handler.handle(BotInteraction(actor, "ack", ref, clicked), target)

            assertEquals(Either.Right(BotReply("✅ Acknowledged")), result)
            assertNull(registry.resolve(ref))
        }

    @Test
    fun `a press while another action holds the card is refused without editing`() =
        runTest {
            val ref = registry.register(CallbackTarget(card), ttlSeconds = 60)!!
            registry.claim(ref)

            val result = handler.handle(BotInteraction(actor, "ack", ref, origin), CallbackTarget(card))

            assertEquals(Either.Right(BotReply("⏳ Someone is already handling this.", alert = true)), result)
            coVerify(exactly = 0) { publisher.editCard(any(), any()) }
        }

    @Test
    fun `a failed edit drops the claim so Acknowledge can be pressed again`() =
        runTest {
            coEvery { publisher.editCard(any(), any()) } returns
                Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "boom"))
            val (ref, _) = click()

            assertTrue(registry.claim(ref))
        }
}
