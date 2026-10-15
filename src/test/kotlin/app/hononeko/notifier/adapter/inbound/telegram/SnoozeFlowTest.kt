package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.EventType
import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.service.AcknowledgeHandler
import app.hononeko.notifier.domain.service.BotInteractionService
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import app.hononeko.notifier.domain.service.HealthSnoozes
import app.hononeko.notifier.domain.service.SnoozeHandler
import app.hononeko.notifier.domain.service.SystemHealthService
import app.hononeko.notifier.domain.service.UnsnoozeHandler
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
import kotlin.test.assertTrue

/**
 * A health issue reaches two admins with Acknowledge + Snooze. One admin snoozes it: both copies are stamped and keep
 * only Unsnooze, and repeats of that check (issue and restored) are dropped. The other admin unsnoozes it, and the
 * next issue is delivered again. Real dispatchers, as in [AcknowledgeFlowTest].
 */
class SnoozeFlowTest {
    private data class Call(
        val method: String,
        val body: JsonObject
    )

    private val calls = CopyOnWriteArrayList<Call>()

    // Message ids count up across sends: the first private message gets 0, the second 1, and so on.
    private val engine =
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            val sentBefore = calls.count { it.method == "sendMessage" }
            calls += Call(method, Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            val result = if (method == "sendMessage") """{"message_id":$sentBefore}""" else "true"
            respond(
                """{"ok":true,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun buttonsOf(call: Call): List<Pair<String, String>> =
        call.body["reply_markup"]
            ?.jsonObject
            ?.get("inline_keyboard")
            ?.jsonArray
            ?.flatMap { it.jsonArray }
            ?.mapNotNull { b ->
                b.jsonObject["callback_data"]?.let {
                    b.jsonObject.text("text") to
                        it.jsonPrimitive.content
                }
            }.orEmpty()

    private fun answerTo(queryId: String) =
        calls
            .single {
                it.method == "answerCallbackQuery" &&
                    it.body.text(
                        "callback_query_id"
                    ) == queryId
            }.body
            .text("text")

    private fun click(
        queryId: String,
        data: String,
        adminId: Long,
        messageId: Long
    ) = TelegramCallbackQuery(
        id = queryId,
        from = TelegramUser(id = adminId, firstName = "Admin$adminId", username = "admin$adminId"),
        message = TelegramMessage(messageId = messageId, chat = TelegramChat(adminId)),
        data = data
    )

    private fun issue() =
        MediaPayload.ServarrHealth(
            source = AppSource.SONARR,
            level = "warning",
            message = "All indexers are unavailable",
            type = "IndexerStatusCheck",
            instanceName = "Sonarr-TV"
        )

    @Test
    fun `admins snooze a check, repeats are dropped, unsnooze brings it back`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = InMemoryStateStore()
            val registry = CallbackRefRegistry(store)
            val snoozes = HealthSnoozes(store) { 1_760_000_000_000L }
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
                            listOf(
                                AcknowledgeHandler(publisher, registry),
                                SnoozeHandler(snoozes, publisher, registry, TimeZone.UTC),
                                UnsnoozeHandler(snoozes, publisher, registry, { 1_760_000_060_000L }, TimeZone.UTC)
                            ),
                            registry
                        ),
                    api = TelegramBotApi("1:T", engine),
                    scope = scope
                )
            val health = SystemHealthService(publisher, adminRecipients = listOf("1", "2"), snoozes = snoozes)

            health.announce(issue())
            val sends = calls.filter { it.method == "sendMessage" }
            assertEquals(listOf("1", "2"), sends.map { it.body.text("chat_id") })
            assertEquals(listOf("✅ Acknowledge", "🔕 Snooze 24h"), buttonsOf(sends.first()).map { it.first })
            val snoozeData = buttonsOf(sends.first()).single { it.first == "🔕 Snooze 24h" }.second

            processor.process(click("cq1", snoozeData, adminId = 1, messageId = 0))

            assertEquals("🔕 Snoozed for 24h", answerTo("cq1"))
            val snoozedEdits = calls.filter { it.method == "editMessageText" }
            assertEquals(setOf("1", "2"), snoozedEdits.map { it.body.text("chat_id") }.toSet())
            snoozedEdits.forEach { edit ->
                assertTrue(
                    edit.body.text("text").endsWith("🔕 Snoozed until tomorrow 08:53 by @admin1"),
                    edit.body.toString()
                )
                assertEquals(listOf("🔔 Unsnooze"), buttonsOf(edit).map { it.first })
            }

            health.announce(issue())
            health.announce(
                issue().copy(eventType = EventType.HEALTH_RESTORED, level = "ok", message = "Indexers available")
            )
            assertEquals(2, calls.count { it.method == "sendMessage" }, "snoozed check must not send anything")

            val unsnoozeData = buttonsOf(snoozedEdits.first()).single().second
            processor.process(click("cq2", unsnoozeData, adminId = 2, messageId = 1))

            assertEquals("🔔 Unsnoozed", answerTo("cq2"))
            val unsnoozedEdits = calls.filter { it.method == "editMessageText" }.drop(snoozedEdits.size)
            assertEquals(setOf("1", "2"), unsnoozedEdits.map { it.body.text("chat_id") }.toSet())
            unsnoozedEdits.forEach {
                assertTrue(it.body.text("text").endsWith("🔔 Unsnoozed by @admin2 · 08:54"), it.body.toString())
            }

            health.announce(issue())
            assertEquals(
                4,
                calls.count { it.method == "sendMessage" },
                "after unsnooze the check reaches both admins again"
            )
            scope.cancel()
        }
}
