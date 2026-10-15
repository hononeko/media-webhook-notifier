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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeHandlersTest {
    private var now = 1_760_000_000_000L // 08:53 UTC, 11:53 in Bucharest
    private val publisher =
        mockk<NotificationPublisherPort> {
            every { providerId } returns "telegram"
            coEvery { editCard(any(), any(), any()) } returns Either.Right(Unit)
        }
    private val registry = CallbackRefRegistry(InMemoryStateStore()) { now }
    private val snoozes = HealthSnoozes(InMemoryStateStore()) { now }
    private val zone = TimeZone.of("Europe/Bucharest")
    private val snooze = SnoozeHandler(snoozes, publisher, registry, timeZone = zone)
    private val unsnooze = UnsnoozeHandler(snoozes, publisher, registry, { now }, zone)

    private val alex = BotActor("telegram", "1", "@alex", isAdmin = true)
    private val bo = BotActor("telegram", "2", "@bo", isAdmin = true)
    private val alexCopy = NotificationHandle("telegram", "1", "5")
    private val boCopy = NotificationHandle("telegram", "2", "8")
    private val check = "SONARR|Sonarr-TV|IndexerStatusCheck"
    private val card =
        NotificationCard(
            title = "⚠️ Health Warning: Sonarr-TV",
            actions = listOf(ActionLink("📖 Wiki", "https://w")),
            interactions = listOf(CardTemplates.ACKNOWLEDGE, SnoozeHandler.button(check))
        )
    private val target = CallbackTarget(card, deliveries = listOf(alexCopy, boCopy))

    @Test
    fun `both buttons are admin-only`() {
        assertTrue(snooze.requiresAdmin)
        assertTrue(unsnooze.requiresAdmin)
    }

    @Test
    fun `snooze silences the check, stamps every copy and leaves Unsnooze on the same ref`() =
        runTest {
            val ref = registry.register(target, 60)!!

            val result = snooze.handle(BotInteraction(alex, "snooze", ref, alexCopy), target)

            assertEquals(Either.Right(BotReply("🔕 Snoozed for 24h")), result)
            assertTrue(snoozes.isSnoozed(check))
            val stamped =
                card.copy(
                    interactions = listOf(UnsnoozeHandler.button(check)),
                    footnote = "🔕 Snoozed until tomorrow 11:53 by @alex"
                )
            // Both copies keep Unsnooze on the pressed ref, not a fresh one each.
            coVerify(exactly = 1) { publisher.editCard(alexCopy, stamped, ref) }
            coVerify(exactly = 1) { publisher.editCard(boCopy, stamped, ref) }
            assertEquals(target.copy(card = stamped), registry.resolve(ref))
        }

    @Test
    fun `unsnooze right after snooze ends it and stamps every copy`() =
        runTest {
            val ref = registry.register(target, 60)!!
            snooze.handle(BotInteraction(alex, "snooze", ref, alexCopy), target)
            val snoozed = registry.resolve(ref)!!
            now += 60_000

            val result = unsnooze.handle(BotInteraction(bo, "unsnooze", ref, boCopy), snoozed)

            assertEquals(Either.Right(BotReply("🔔 Unsnoozed")), result)
            assertFalse(snoozes.isSnoozed(check))
            coVerify {
                publisher.editCard(
                    alexCopy,
                    match {
                        it.footnote == "🔔 Unsnoozed by @bo · 11:54" &&
                            it.interactions.isEmpty()
                    }
                )
            }
            coVerify { publisher.editCard(boCopy, match { it.footnote == "🔔 Unsnoozed by @bo · 11:54" }) }
            assertNull(registry.resolve(ref))
        }

    @Test
    fun `unsnooze after the 24h says it had already ended and removes the button`() =
        runTest {
            snoozes.snooze(check, "@alex")
            now += 86_400_001L
            val snoozedCard = card.copy(interactions = listOf(UnsnoozeHandler.button(check)))
            val snoozedTarget = target.copy(card = snoozedCard)
            val ref = registry.register(snoozedTarget, 60)!!

            val result = unsnooze.handle(BotInteraction(bo, "unsnooze", ref, boCopy), snoozedTarget)

            assertEquals(Either.Right(BotReply("🔔 The snooze had already ended.")), result)
            coVerify {
                publisher.editCard(
                    boCopy,
                    match { it.footnote == "🔔 Snooze ended" && it.interactions.isEmpty() }
                )
            }
        }

    @Test
    fun `a press while another action holds the card is refused`() =
        runTest {
            val ref = registry.register(target, 60)!!
            registry.claim(ref)

            val result = snooze.handle(BotInteraction(alex, "snooze", ref, alexCopy), target)

            assertEquals(Either.Right(BotReply("⏳ Someone is already handling this.", alert = true)), result)
            assertFalse(snoozes.isSnoozed(check))
        }

    @Test
    fun `a card without the check is UnknownAction`() =
        runTest {
            val bare = CallbackTarget(NotificationCard(title = "x"))
            val ref = registry.register(bare, 60)!!

            assertEquals(
                Either.Left(DomainError.InteractionError.UnknownAction("snooze")),
                snooze.handle(BotInteraction(alex, "snooze", ref, alexCopy), bare)
            )
        }
}
