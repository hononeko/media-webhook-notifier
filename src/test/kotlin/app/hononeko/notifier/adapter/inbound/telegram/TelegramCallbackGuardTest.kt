package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotActor
import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramCallbackGuardTest {
    private val guard = TelegramCallbackGuard(allowedUserIds = setOf(42L), chatId = "-1001")

    private fun query(
        userId: Long = 42,
        username: String? = "alice",
        firstName: String = "Alice",
        chat: TelegramChat? = TelegramChat(-1001)
    ) = TelegramCallbackQuery(
        id = "cq1",
        from = TelegramUser(id = userId, firstName = firstName, username = username),
        message = chat?.let { TelegramMessage(messageId = 10, chat = it) },
        data = "mwn1:ping:AAAAAAAAAA"
    )

    private fun rejection(result: Either<DomainError.InteractionError.UnauthorizedUser, BotActor>) =
        assertIs<Either.Left<DomainError.InteractionError.UnauthorizedUser>>(result).value

    @Test
    fun `allowed user in configured chat yields actor with @username`() {
        assertEquals(Either.Right(BotActor("telegram", "42", "@alice")), guard.check(query()))
    }

    @Test
    fun `actor falls back to first name without username`() {
        assertEquals(Either.Right(BotActor("telegram", "42", "Alice")), guard.check(query(username = null)))
    }

    @Test
    fun `unknown user is UnauthorizedUser with user id`() {
        val error = rejection(guard.check(query(userId = 7)))

        assertEquals("7", error.userId)
        assertTrue(error.reason.contains("TELEGRAM_ALLOWED_USERS"), error.reason)
    }

    @Test
    fun `wrong chat is UnauthorizedUser`() {
        assertEquals("42", rejection(guard.check(query(chat = TelegramChat(-999)))).userId)
    }

    @Test
    fun `callback without message is UnauthorizedUser`() {
        assertEquals("42", rejection(guard.check(query(chat = null))).userId)
    }

    @Test
    fun `chat configured as @channel matches chat username case-insensitively`() {
        val channelGuard = TelegramCallbackGuard(setOf(42L), "@MyChannel")

        assertTrue(channelGuard.check(query(chat = TelegramChat(-1002, "mychannel"))).isRight())
        assertTrue(channelGuard.check(query(chat = TelegramChat(-1002, "otherchannel"))).isLeft())
        assertTrue(channelGuard.check(query(chat = TelegramChat(-1002))).isLeft())
    }
}
