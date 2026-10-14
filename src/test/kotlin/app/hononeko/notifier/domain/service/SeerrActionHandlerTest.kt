package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.BotActor
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.outbound.MediaRequestManagerPort
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import arrow.core.Either
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeerrActionHandlerTest {
    private val publisher =
        mockk<NotificationPublisherPort> {
            every { providerId } returns "telegram"
            coEvery { editCard(any(), any()) } returns Either.Right(Unit)
        }
    private val seerr =
        mockk<MediaRequestManagerPort> {
            coEvery { approveRequest(any()) } returns Either.Right(Unit)
            coEvery { declineRequest(any()) } returns Either.Right(Unit)
            coEvery { resolveIssue(any()) } returns Either.Right(Unit)
        }
    private val registry = CallbackRefRegistry(InMemoryStateStore())

    private fun handler(action: SeerrAction) =
        SeerrActionHandler(
            action = action,
            requestManager = seerr,
            publisher = publisher,
            refRegistry = registry,
            clock = { 1_760_000_000_000L },
            timeZone = TimeZone.of("Europe/Bucharest")
        )

    private val admin = BotActor(platform = "telegram", id = "42", displayName = "@alex", isAdmin = true)
    private val origin = NotificationHandle("telegram", "-1001", "5")
    private val requestCard =
        NotificationCard(
            title = "🛎️ New Request: Dune",
            actions = listOf(ActionLink("🌐 Open", "https://seerr")),
            interactions = listOf(SeerrAction.APPROVE.interaction(12), SeerrAction.DECLINE.interaction(12))
        )
    private val issueCard = NotificationCard(title = "Issue", interactions = listOf(SeerrAction.RESOLVE.interaction(7)))

    private suspend fun click(
        action: SeerrAction,
        card: NotificationCard = requestCard,
        origin: NotificationHandle? = this.origin
    ): Pair<String, Either<DomainError, BotReply>> {
        val ref = registry.register(CallbackTarget(card), ttlSeconds = 60)!!
        return ref to handler(action).handle(BotInteraction(admin, action.operation, ref, origin), CallbackTarget(card))
    }

    @Test
    fun `every Seerr action is admin-only`() {
        SeerrAction.entries.forEach { assertTrue(handler(it).requiresAdmin, it.name) }
    }

    @Test
    fun `approve calls Seerr with the card's request id, stamps the card and releases the ref`() =
        runTest {
            val (ref, result) = click(SeerrAction.APPROVE)

            coVerify(exactly = 1) { seerr.approveRequest(12) }
            val stamped = requestCard.copy(interactions = emptyList(), footnote = "✅ Approved by @alex · 11:53")
            coVerify(exactly = 1) { publisher.editCard(origin, stamped) }
            assertEquals(Either.Right(BotReply("✅ Approved")), result)
            assertNull(registry.resolve(ref))
        }

    @Test
    fun `decline calls Seerr decline and stamps Declined by`() =
        runTest {
            val (_, result) = click(SeerrAction.DECLINE)

            coVerify(exactly = 1) { seerr.declineRequest(12) }
            coVerify(exactly = 0) { seerr.approveRequest(any()) }
            coVerify { publisher.editCard(origin, match { it.footnote == "❌ Declined by @alex · 11:53" }) }
            assertEquals(Either.Right(BotReply("❌ Declined")), result)
        }

    @Test
    fun `resolve calls Seerr with the card's issue id`() =
        runTest {
            val (_, result) = click(SeerrAction.RESOLVE, issueCard)

            coVerify(exactly = 1) { seerr.resolveIssue(7) }
            coVerify { publisher.editCard(origin, match { it.footnote == "✔️ Resolved by @alex · 11:53" }) }
            assertEquals(Either.Right(BotReply("✔️ Resolved")), result)
        }

    @Test
    fun `Seerr failure keeps card, ref and lets the button be pressed again`() =
        runTest {
            val failure = DomainError.RequestManagerError.Rejected(500, "boom")
            coEvery { seerr.approveRequest(any()) } returns Either.Left(failure)

            val (ref, result) = click(SeerrAction.APPROVE)

            assertEquals(Either.Left(failure), result)
            coVerify(exactly = 0) { publisher.editCard(any(), any()) }
            assertNotNull(registry.resolve(ref))
            assertTrue(registry.claim(ref), "a failed action must drop its claim")
        }

    @Test
    fun `request missing in Seerr answers with a specific alert`() =
        runTest {
            coEvery { seerr.approveRequest(any()) } returns
                Either.Left(DomainError.RequestManagerError.NotFound("request", 12))

            val (_, result) = click(SeerrAction.APPROVE)

            assertEquals(Either.Right(BotReply("⚠️ This request no longer exists in Seerr.", alert = true)), result)
            coVerify(exactly = 0) { publisher.editCard(any(), any()) }
        }

    @Test
    fun `failed card edit still reports success and releases the ref`() =
        runTest {
            coEvery { publisher.editCard(any(), any()) } returns
                Either.Left(DomainError.NotificationError.DeliveryFailed("telegram", "gone"))

            val (ref, result) = click(SeerrAction.APPROVE)

            assertEquals(Either.Right(BotReply("✅ Approved")), result)
            assertNull(registry.resolve(ref))
        }

    @Test
    fun `a second press while the first is in flight is refused without calling Seerr`() =
        runTest {
            val gate = CompletableDeferred<Either<DomainError.RequestManagerError, Unit>>()
            coEvery { seerr.approveRequest(any()) } coAnswers { gate.await() }
            val ref = registry.register(CallbackTarget(requestCard), ttlSeconds = 60)!!
            val first =
                async {
                    handler(SeerrAction.APPROVE)
                        .handle(BotInteraction(admin, "seerr_approve", ref, origin), CallbackTarget(requestCard))
                }
            testScheduler.runCurrent()

            val second =
                handler(SeerrAction.DECLINE)
                    .handle(BotInteraction(admin, "seerr_decline", ref, origin), CallbackTarget(requestCard))

            assertEquals(Either.Right(BotReply("⏳ Someone is already handling this.", alert = true)), second)
            coVerify(exactly = 0) { seerr.declineRequest(any()) }
            gate.complete(Either.Right(Unit))
            assertEquals(Either.Right(BotReply("✅ Approved")), first.await())
        }

    @Test
    fun `card without the action's id is UnknownAction and never calls Seerr`() =
        runTest {
            val (_, result) = click(SeerrAction.APPROVE, NotificationCard(title = "no buttons"))

            assertEquals(Either.Left(DomainError.InteractionError.UnknownAction("seerr_approve")), result)
            coVerify(exactly = 0) { seerr.approveRequest(any()) }
        }

    @Test
    fun `missing origin fails before calling Seerr`() =
        runTest {
            val (_, result) = click(SeerrAction.APPROVE, origin = null)

            assertTrue(result.isLeft())
            coVerify(exactly = 0) { seerr.approveRequest(any()) }
        }

    @Test
    fun `a decision stamps every admin's copy of the card`() =
        runTest {
            val otherAdmin = NotificationHandle("telegram", "2", "9", isPhoto = true)
            val clicked = NotificationHandle("telegram", "1", "5")
            val target = CallbackTarget(requestCard, deliveries = listOf(clicked, otherAdmin))
            val ref = registry.register(target, ttlSeconds = 60)!!

            handler(SeerrAction.APPROVE).handle(BotInteraction(admin, "seerr_approve", ref, clicked), target)

            coVerify(exactly = 1) { publisher.editCard(clicked, any()) }
            coVerify(
                exactly = 1
            ) { publisher.editCard(otherAdmin, match { it.footnote!!.startsWith("✅ Approved by @alex") }) }
        }
}
