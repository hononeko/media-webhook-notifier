package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramFlow.Companion.CHAT_ID
import app.hononeko.notifier.adapter.inbound.telegram.TelegramFlow.Companion.NOW
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.SystemHealthService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A health card goes out with an Acknowledge button, a press edits it, and a second press finds the button expired. */
class AcknowledgeFlowTest {
    private fun flow(adminUsers: Set<Long> = emptySet()) =
        TelegramFlow(adminUsers = adminUsers) { listOf(AcknowledgeHandler(publisher, registry, { NOW }, TimeZone.UTC)) }

    @Test
    fun `health issue card can be acknowledged once`(): Unit =
        runBlocking {
            flow().use { telegram ->
                SystemHealthService(telegram.publisher).announce(
                    MediaPayload.ServarrHealth(
                        source = AppSource.SONARR,
                        level = "warning",
                        message = "Indexer unavailable"
                    )
                )
                val ack =
                    telegram
                        .calls("sendMessage")
                        .single()
                        .buttons
                        .getValue("✅ Acknowledge")

                assertEquals("✅ Acknowledged", telegram.press(ack, userId = 42, messageId = 0, chatId = CHAT_ID))

                val edit = telegram.calls("editMessageText").single()
                assertTrue(edit.text("text").endsWith("✅ Handled by @user42 · 08:53"), edit.body.toString())
                assertEquals(emptyMap(), edit.buttons)

                assertEquals(
                    "⌛ This button has expired.",
                    telegram.press(ack, userId = 42, messageId = 0, chatId = CHAT_ID)
                )
                assertEquals(1, telegram.calls("editMessageText").size)
            }
        }

    @Test
    fun `health issue goes to each admin privately and one acknowledgement stamps every copy`(): Unit =
        runBlocking {
            flow(adminUsers = setOf(1L, 2L)).use { telegram ->
                SystemHealthService(telegram.publisher, adminRecipients = listOf("1", "2")).announce(
                    MediaPayload.ServarrHealth(source = AppSource.SONARR, level = "error", message = "Disk full")
                )
                val sends = telegram.calls("sendMessage")
                assertEquals(listOf("1", "2"), sends.map { it.text("chat_id") }, "admins only")

                assertEquals(
                    "✅ Acknowledged",
                    telegram.press(sends[1].buttons.getValue("✅ Acknowledge"), userId = 2, messageId = 1)
                )

                val edits = telegram.calls("editMessageText")
                assertEquals(
                    setOf("1" to "0", "2" to "1"),
                    edits.map { it.text("chat_id") to it.text("message_id") }.toSet()
                )
                edits.forEach {
                    assertTrue(
                        it.text("text").endsWith("✅ Handled by @user2 · 08:53"),
                        it.body.toString()
                    )
                }
            }
        }
}
