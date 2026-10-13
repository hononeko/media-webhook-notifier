package app.hononeko.notifier.domain.service

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotActor
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import arrow.core.Either
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BotInteractionServiceTest {
    private fun target(title: String) = CallbackTarget(NotificationCard(title = title))

    private val actor = BotActor(platform = "telegram", id = "42", displayName = "@alice")
    private val store = spyk(InMemoryStateStore())
    private val registry = CallbackRefRegistry(store)

    private var received: BotInteraction? = null

    private val ping =
        object : BotActionHandler {
            override val operation = "ping"

            override suspend fun handle(
                interaction: BotInteraction,
                target: CallbackTarget
            ): Either<DomainError, BotReply> {
                received = interaction
                return Either.Right(BotReply("pong ${target.card.title}"))
            }
        }

    private val service = BotInteractionService(listOf(ping), registry)

    @Test
    fun `dispatches to the handler for the operation with the resolved target`() =
        runTest {
            val ref = registry.register(target("h1"), 60)!!

            val result = service.handle(BotInteraction(actor, "ping", ref))

            assertEquals(Either.Right(BotReply("pong h1")), result)
        }

    @Test
    fun `handler receives the interaction including origin`() =
        runTest {
            val ref = registry.register(target("h1"), 60)!!
            val interaction = BotInteraction(actor, "ping", ref, origin = NotificationHandle("telegram", "-1001", "5"))

            service.handle(interaction)

            assertEquals(interaction, received)
        }

    @Test
    fun `unknown operation is UnknownAction and never reads the store`() =
        runTest {
            val result = service.handle(BotInteraction(actor, "nope", "AAAAAAAAAA"))

            assertEquals(Either.Left(DomainError.InteractionError.UnknownAction("nope")), result)
            coVerify(exactly = 0) { store.get(any(), any()) }
        }

    @Test
    fun `missing ref is ExpiredCallback`() =
        runTest {
            val result = service.handle(BotInteraction(actor, "ping", "AAAAAAAAAA"))

            assertEquals(Either.Left(DomainError.InteractionError.ExpiredCallback("AAAAAAAAAA")), result)
        }

    @Test
    fun `handler errors pass through unchanged`() =
        runTest {
            val failure = DomainError.DownloadClientError.DownloadNotFound("h1")
            val failing =
                object : BotActionHandler {
                    override val operation = "pause"

                    override suspend fun handle(
                        interaction: BotInteraction,
                        target: CallbackTarget
                    ): Either<DomainError, BotReply> = Either.Left(failure)
                }
            val ref = registry.register(target("h1"), 60)!!

            val result = BotInteractionService(listOf(failing), registry).handle(BotInteraction(actor, "pause", ref))

            assertEquals(Either.Left(failure), result)
        }

    @Test
    fun `duplicate operations are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { BotInteractionService(listOf(ping, ping), registry) }
    }
}
