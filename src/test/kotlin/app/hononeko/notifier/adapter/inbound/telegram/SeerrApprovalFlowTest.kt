package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.telegram.TelegramFlow.Companion.NOW
import app.hononeko.notifier.adapter.outbound.seerr.SeerrClientAdapter
import app.hononeko.notifier.config.SeerrConfig
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.SeerrAction
import app.hononeko.notifier.domain.service.SeerrActionHandler
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pending Seerr request goes to each admin's private chat (not the channel) with Approve / Decline. A delisted
 * admin's leftover copy can't be used, one admin's approval reaches Seerr and stamps every admin's copy, and the other
 * button is then expired.
 */
class SeerrApprovalFlowTest {
    private val seerrCalls = CopyOnWriteArrayList<String>()
    private val seerr =
        SeerrClientAdapter(
            SeerrConfig("http://seerr:5055", "k"),
            MockEngine { request ->
                seerrCalls += request.url.encodedPath
                respond("{}", HttpStatusCode.OK)
            }
        )

    @Test
    fun `admins decide a pending request in private chats and every copy is stamped`(): Unit =
        runBlocking {
            TelegramFlow(adminUsers = setOf(1L, 2L)) {
                listOf(AcknowledgeHandler(publisher, registry)) +
                    SeerrAction.entries.map {
                        SeerrActionHandler(
                            it,
                            seerr,
                            publisher,
                            registry,
                            { NOW },
                            TimeZone.UTC
                        )
                    }
            }.use { telegram ->
                MediaRequestService(telegram.publisher, decisionRecipients = listOf("1", "2")).announce(
                    MediaPayload.SeerrEvent(
                        eventType = EventType.REQUEST_PENDING,
                        notificationType = "MEDIA_PENDING",
                        subject = "Dune",
                        requestId = 12
                    )
                )
                val sends = telegram.calls("sendMessage")
                assertEquals(listOf("1", "2"), sends.map { it.text("chat_id") }, "admins only, never the channel")
                val approve = sends[0].buttons.getValue("✅ Approve")
                val decline = sends[1].buttons.getValue("❌ Decline")

                // 42 was an admin when the card went out and kept a copy, but is now only on the allowlist.
                assertEquals(
                    "⛔ You're not allowed to control this bot.",
                    telegram.press(approve, userId = 42, messageId = 0)
                )
                assertEquals(emptyList(), seerrCalls)

                assertEquals("✅ Approved", telegram.press(approve, userId = 1, messageId = 0))

                assertEquals(listOf("/api/v1/request/12/approve"), seerrCalls)
                val edits = telegram.calls("editMessageText")
                assertEquals(
                    setOf("1" to "0", "2" to "1"),
                    edits.map { it.text("chat_id") to it.text("message_id") }.toSet()
                )
                edits.forEach { edit ->
                    assertTrue(edit.text("text").endsWith("✅ Approved by @user1 · 08:53"), edit.body.toString())
                    assertEquals(emptyMap(), edit.buttons)
                }

                assertEquals("⌛ This button has expired.", telegram.press(decline, userId = 2, messageId = 1))
                assertEquals(1, seerrCalls.size)
            }
        }
}
