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
 * A pending Seerr request goes out with Approve / Decline, a non-admin's click changes nothing, an admin's click
 * approves it in Seerr and stamps the card, and the other button is then expired. Real dispatchers, as in
 * [AcknowledgeFlowTest].
 */
class SeerrApprovalFlowTest {
    private data class Call(
        val method: String,
        val body: JsonObject
    )

    private val telegramCalls = CopyOnWriteArrayList<Call>()
    private val seerrCalls = CopyOnWriteArrayList<String>()

    private val telegram =
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            telegramCalls +=
                Call(method, Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            val result = if (method == "sendMessage") """{"message_id":7}""" else "true"
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

    private fun click(
        queryId: String,
        data: String,
        userId: Long
    ) = TelegramCallbackQuery(
        id = queryId,
        from = TelegramUser(id = userId, firstName = "User$userId", username = "user$userId"),
        message = TelegramMessage(messageId = 7, chat = TelegramChat(-1001)),
        data = data
    )

    @Test
    fun `admin approves a pending request once, non-admins change nothing`() =
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
                    guard =
                        TelegramCallbackGuard(
                            allowedUserIds = setOf(42L),
                            chatId = "-1001",
                            adminUserIds = setOf(1L)
                        ),
                    useCase = BotInteractionService(handlers, registry),
                    api = TelegramBotApi("1:T", telegram),
                    scope = scope
                )

            MediaRequestService(publisher, seerrActions = true).announce(
                MediaPayload.SeerrEvent(
                    eventType = EventType.REQUEST_PENDING,
                    notificationType = "MEDIA_PENDING",
                    subject = "Dune",
                    requestId = 12
                )
            )
            val buttons =
                telegramCalls
                    .single { it.method == "sendMessage" }
                    .body["reply_markup"]!!
                    .jsonObject["inline_keyboard"]!!
                    .jsonArray
                    .flatMap { it.jsonArray }
                    .mapNotNull { it.jsonObject["callback_data"]?.jsonPrimitive?.content }
            val approve = buttons.single { it.contains(":seerr_approve:") }
            val decline = buttons.single { it.contains(":seerr_decline:") }

            processor.process(click("cq1", approve, userId = 42))

            assertEquals("⛔ You're not allowed to control this bot.", answerTo("cq1"))
            assertEquals(emptyList(), seerrCalls)

            processor.process(click("cq2", approve, userId = 1))

            assertEquals(listOf("/api/v1/request/12/approve"), seerrCalls)
            assertEquals("✅ Approved", answerTo("cq2"))
            val edit = telegramCalls.single { it.method == "editMessageText" }
            assertTrue(
                edit.body["text"]!!
                    .jsonPrimitive.content
                    .endsWith("✅ Approved by @user1 · 08:53"),
                edit.body.toString()
            )
            assertFalse(edit.body.toString().contains("callback_data"))

            processor.process(click("cq3", decline, userId = 1))

            assertEquals("⌛ This button has expired.", answerTo("cq3"))
            assertEquals(1, seerrCalls.size)
            scope.cancel()
        }
}
