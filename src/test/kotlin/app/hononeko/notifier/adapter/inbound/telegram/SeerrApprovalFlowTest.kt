package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.outbound.seerr.SeerrClientAdapter
import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.config.SeerrConfig
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.BotInteractionService
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import app.hononeko.notifier.domain.service.MediaRequestService
import app.hononeko.notifier.domain.service.SeerrAction
import app.hononeko.notifier.domain.service.SeerrActionHandler
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pending Seerr request goes to each admin's private chat (not the channel) with Approve / Decline. A delisted
 * admin's leftover copy can't be used, one admin's approval reaches Seerr and stamps every admin's copy, and the other
 * button is then expired. Real dispatchers, as in [AcknowledgeFlowTest].
 */
class SeerrApprovalFlowTest {
    private data class Call(
        val method: String,
        val body: JsonObject
    )

    private val telegramCalls = CopyOnWriteArrayList<Call>()
    private val seerrCalls = CopyOnWriteArrayList<String>()

    // Messages are numbered per chat-wide send order: the first private message gets 0, the second 1.
    private val telegram =
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            val sentBefore = telegramCalls.count { it.method == "sendMessage" }
            telegramCalls +=
                Call(method, Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            val result = if (method == "sendMessage") """{"message_id":$sentBefore}""" else "true"
            respond(
                """{"ok":true,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

    private val seerr =
        MockEngine { request ->
            seerrCalls += request.url.encodedPath
            respond("{}", HttpStatusCode.OK)
        }

    private fun answerTo(queryId: String) =
        telegramCalls
            .single {
                it.method == "answerCallbackQuery" &&
                    it.body["callback_query_id"]!!.jsonPrimitive.content == queryId
            }.body["text"]!!
            .jsonPrimitive.content

    /** A click by [userId] in their own private chat, on message [messageId] there. */
    private fun click(
        queryId: String,
        data: String,
        userId: Long,
        messageId: Long
    ) = TelegramCallbackQuery(
        id = queryId,
        from = TelegramUser(id = userId, firstName = "User$userId", username = "user$userId"),
        message = TelegramMessage(messageId = messageId, chat = TelegramChat(userId)),
        data = data
    )

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `admins decide a pending request in private chats and every copy is stamped`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = CallbackRefRegistry(InMemoryStateStore())
            val publisher =
                TelegramPublisherAdapter(
                    NotificationConfig(botToken = "1:T", chatId = "-1001", sendPhotos = false),
                    telegram,
                    registry
                )
            val seerrClient = SeerrClientAdapter(SeerrConfig("http://seerr:5055", "k"), seerr)
            val handlers =
                listOf(AcknowledgeHandler(publisher, registry)) +
                    SeerrAction.entries.map {
                        SeerrActionHandler(it, seerrClient, publisher, registry, { 1_760_000_000_000L }, TimeZone.UTC)
                    }
            val processor =
                TelegramCallbackProcessor(
                    guard = TelegramCallbackGuard(setOf(42L), "-1001", adminUserIds = setOf(1L, 2L)),
                    useCase = BotInteractionService(handlers, registry),
                    api = TelegramBotApi("1:T", telegram),
                    scope = scope
                )

            MediaRequestService(publisher, decisionRecipients = listOf("1", "2")).announce(
                MediaPayload.SeerrEvent(
                    eventType = EventType.REQUEST_PENDING,
                    notificationType = "MEDIA_PENDING",
                    subject = "Dune",
                    requestId = 12
                )
            )
            val sends = telegramCalls.filter { it.method == "sendMessage" }
            assertEquals(listOf("1", "2"), sends.map { it.body.text("chat_id") }, "admins only, never the channel")
            val buttons =
                sends
                    .first()
                    .body["reply_markup"]!!
                    .jsonObject["inline_keyboard"]!!
                    .jsonArray
                    .flatMap { it.jsonArray }
                    .mapNotNull { it.jsonObject["callback_data"]?.jsonPrimitive?.content }
            val approve = buttons.single { it.contains(":seerr_approve:") }
            val decline = buttons.single { it.contains(":seerr_decline:") }

            // 42 was an admin when the card went out and kept a copy, but is now only on the allowlist.
            processor.process(click("cq1", approve, userId = 42, messageId = 0))

            assertEquals("⛔ You're not allowed to control this bot.", answerTo("cq1"))
            assertEquals(emptyList(), seerrCalls)

            processor.process(click("cq2", approve, userId = 1, messageId = 0))

            assertEquals(listOf("/api/v1/request/12/approve"), seerrCalls)
            assertEquals("✅ Approved", answerTo("cq2"))
            val edits = telegramCalls.filter { it.method == "editMessageText" }
            assertEquals(
                setOf("1" to "0", "2" to "1"),
                edits
                    .map {
                        it.body.text("chat_id") to
                            it.body.text("message_id")
                    }.toSet()
            )
            edits.forEach { edit ->
                assertTrue(edit.body.text("text").endsWith("✅ Approved by @user1 · 08:53"), edit.body.toString())
                assertFalse(edit.body.toString().contains("callback_data"))
            }

            processor.process(click("cq3", decline, userId = 2, messageId = 1))

            assertEquals("⌛ This button has expired.", answerTo("cq3"))
            assertEquals(1, seerrCalls.size)
            scope.cancel()
        }
}
