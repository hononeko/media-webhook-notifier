package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.BotInteractionService
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import app.hononeko.notifier.domain.service.SystemHealthService
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
 * A health card goes out with an Acknowledge button, a click edits it, and a second click finds the button expired.
 * Runs on real dispatchers: the answer budget would otherwise expire in virtual time while MockEngine answers.
 */
class AcknowledgeFlowTest {
    private data class Call(
        val method: String,
        val body: JsonObject
    )

    private val calls = CopyOnWriteArrayList<Call>()

    private val engine =
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            calls += Call(method, Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            val result = if (method == "sendMessage") """{"message_id":7}""" else "true"
            respond(
                """{"ok":true,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

    private fun answerTo(queryId: String) =
        calls
            .single {
                it.method == "answerCallbackQuery" &&
                    it.body["callback_query_id"]!!.jsonPrimitive.content == queryId
            }.body["text"]!!
            .jsonPrimitive.content

    private fun click(
        queryId: String,
        data: String
    ) = TelegramCallbackQuery(
        id = queryId,
        from = TelegramUser(id = 42, firstName = "Alex", username = "alex"),
        message = TelegramMessage(messageId = 7, chat = TelegramChat(-1001)),
        data = data
    )

    @Test
    fun `health issue card can be acknowledged once`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = CallbackRefRegistry(InMemoryStateStore())
            val publisher =
                TelegramPublisherAdapter(
                    NotificationConfig(botToken = "1:T", chatId = "-1001", sendPhotos = false),
                    engine,
                    registry
                )
            val processor =
                TelegramCallbackProcessor(
                    guard = TelegramCallbackGuard(setOf(42L), "-1001"),
                    useCase =
                        BotInteractionService(
                            listOf(AcknowledgeHandler(publisher, registry, { 1_760_000_000_000L }, TimeZone.UTC)),
                            registry
                        ),
                    api = TelegramBotApi("1:T", engine),
                    scope = scope
                )

            SystemHealthService(publisher).announce(
                MediaPayload.ServarrHealth(
                    source = AppSource.SONARR,
                    level = "warning",
                    message = "Indexer unavailable"
                )
            )
            val data =
                calls
                    .single { it.method == "sendMessage" }
                    .body["reply_markup"]!!
                    .jsonObject["inline_keyboard"]!!
                    .jsonArray
                    .flatMap { it.jsonArray }
                    .mapNotNull { it.jsonObject["callback_data"]?.jsonPrimitive?.content }
                    .single()

            processor.process(click("cq1", data))

            val edit = calls.single { it.method == "editMessageText" }
            assertTrue(
                edit.body["text"]!!
                    .jsonPrimitive.content
                    .endsWith("✅ Handled by @alex · 08:53"),
                edit.body.toString()
            )
            assertFalse(edit.body.toString().contains("callback_data"))
            assertEquals("✅ Acknowledged", answerTo("cq1"))

            processor.process(click("cq2", data))

            assertEquals("⌛ This button has expired.", answerTo("cq2"))
            assertEquals(1, calls.count { it.method == "editMessageText" })
            scope.cancel()
        }

    @Test
    fun `health issue goes to each admin privately and one acknowledgement stamps every copy`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = CallbackRefRegistry(InMemoryStateStore())
            val publisher =
                TelegramPublisherAdapter(
                    NotificationConfig(botToken = "1:T", chatId = "-1001", sendPhotos = false),
                    engine,
                    registry
                )
            val processor =
                TelegramCallbackProcessor(
                    guard = TelegramCallbackGuard(setOf(42L), "-1001", adminUserIds = setOf(1L, 2L)),
                    useCase =
                        BotInteractionService(
                            listOf(AcknowledgeHandler(publisher, registry, { 1_760_000_000_000L }, TimeZone.UTC)),
                            registry
                        ),
                    api = TelegramBotApi("1:T", engine),
                    scope = scope
                )

            SystemHealthService(publisher, adminRecipients = listOf("1", "2")).announce(
                MediaPayload.ServarrHealth(source = AppSource.SONARR, level = "error", message = "Disk full")
            )
            val sends = calls.filter { it.method == "sendMessage" }
            assertEquals(listOf("1", "2"), sends.map { it.body["chat_id"]!!.jsonPrimitive.content }, "admins only")
            val data =
                sends
                    .first()
                    .body["reply_markup"]!!
                    .jsonObject["inline_keyboard"]!!
                    .jsonArray
                    .flatMap { it.jsonArray }
                    .mapNotNull { it.jsonObject["callback_data"]?.jsonPrimitive?.content }
                    .single()

            processor.process(
                TelegramCallbackQuery(
                    id = "cq1",
                    from = TelegramUser(id = 2, firstName = "Bo", username = "bo"),
                    message = TelegramMessage(messageId = 7, chat = TelegramChat(2)),
                    data = data
                )
            )

            assertEquals("✅ Acknowledged", answerTo("cq1"))
            val edits = calls.filter { it.method == "editMessageText" }
            assertEquals(setOf("1", "2"), edits.map { it.body["chat_id"]!!.jsonPrimitive.content }.toSet())
            edits.forEach {
                assertTrue(
                    it.body["text"]!!
                        .jsonPrimitive.content
                        .endsWith("✅ Handled by @bo · 08:53"),
                    it.body.toString()
                )
            }
            scope.cancel()
        }
}
