package app.hononeko.notifier

import app.hononeko.notifier.config.AppConfig
import app.hononeko.notifier.config.FeatureFlags
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.config.TelegramInteractionConfig
import app.hononeko.notifier.config.TelegramUpdatesMode
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.service.CardFormatterService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramInteractionBootstrapTest {
    private data class ApiCall(
        val method: String,
        val body: String
    )

    private val calls = CopyOnWriteArrayList<ApiCall>()

    private val engine =
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            calls += ApiCall(method, request.body.toByteArray().decodeToString())
            val result =
                when (method) {
                    "getUpdates" -> {
                        delay(200) // stand-in for a long poll that returns nothing
                        "[]"
                    }
                    "sendMessage" -> """{"message_id":5}"""
                    else -> "true"
                }
            respond(
                """{"ok":true,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

    private fun methods() = calls.map { it.method }

    private fun config(mode: TelegramUpdatesMode) =
        AppConfig(
            notifications = NotificationConfig(botToken = "1:T", chatId = "-1001"),
            features = FeatureFlags(telegramInteractive = true),
            telegramInteraction =
                TelegramInteractionConfig(
                    mode = mode,
                    allowedUserIds = setOf(42L),
                    webhookUrl = "https://n.example.com/",
                    webhookSecret = "s3cret"
                )
        )

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(20) }
    }

    private val callbackUpdate =
        """
        {"update_id":1,"callback_query":{"id":"cq1","from":{"id":42,"is_bot":false,"first_name":"A"},
        "message":{"message_id":5,"chat":{"id":-1001,"type":"supergroup"},"date":1},"data":"mwn1:ping:AAAAAAAAAA"}}
        """.trimIndent()

    @Test
    fun `flag off - no route and no Telegram calls`() =
        testApplication {
            val dependencies =
                buildDependencies(
                    AppConfig(),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )
            application { module(dependencies) }

            val response = client.post("/api/v1/telegram/webhook") { setBody(callbackUpdate) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNull(dependencies.telegramInteractions)
            dependencies.close()
            assertTrue(calls.isEmpty(), "unexpected Telegram calls: ${methods()}")
        }

    @Test
    fun `webhook mode - route answers and setWebhook is registered`() =
        testApplication {
            val dependencies =
                buildDependencies(
                    config(TelegramUpdatesMode.WEBHOOK),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )
            application { module(dependencies) }

            eventually { "setWebhook" in methods() }
            val registration = Json.parseToJsonElement(calls.first { it.method == "setWebhook" }.body).jsonObject
            assertEquals("https://n.example.com/api/v1/telegram/webhook", registration["url"]!!.jsonPrimitive.content)
            assertEquals("s3cret", registration["secret_token"]!!.jsonPrimitive.content)

            val unauthenticated =
                client.post("/api/v1/telegram/webhook") {
                    contentType(ContentType.Application.Json)
                    setBody(callbackUpdate)
                }
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

            val accepted =
                client.post("/api/v1/telegram/webhook") {
                    header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                    contentType(ContentType.Application.Json)
                    setBody(callbackUpdate)
                }
            assertEquals(HttpStatusCode.OK, accepted.status)

            // No actions are registered in MWN-1, so a valid click from an allowed user is answered "not supported".
            eventually { "answerCallbackQuery" in methods() }
            val answer = Json.parseToJsonElement(calls.first { it.method == "answerCallbackQuery" }.body).jsonObject
            assertEquals("cq1", answer["callback_query_id"]!!.jsonPrimitive.content)
            assertEquals("This action isn't supported.", answer["text"]!!.jsonPrimitive.content)

            dependencies.close()
            assertTrue("deleteWebhook" !in methods(), "webhook must stay registered on shutdown")
            assertTrue("getUpdates" !in methods(), "webhook mode must not poll")
        }

    @Test
    fun `polling mode - no route, deleteWebhook then getUpdates`() =
        testApplication {
            val dependencies =
                buildDependencies(
                    config(TelegramUpdatesMode.POLLING),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )
            application { module(dependencies) }

            eventually { "getUpdates" in methods() }
            assertEquals("deleteWebhook", methods().first())

            val response =
                client.post("/api/v1/telegram/webhook") {
                    header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                    setBody(callbackUpdate)
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            dependencies.close()
        }

    @Test
    fun `close stops polling`() =
        testApplication {
            val dependencies =
                buildDependencies(
                    config(TelegramUpdatesMode.POLLING),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )
            application { module(dependencies) }
            eventually { methods().count { it == "getUpdates" } >= 2 }

            dependencies.close()
            val pollsAtClose = methods().count { it == "getUpdates" }
            delay(600)

            assertEquals(pollsAtClose, methods().count { it == "getUpdates" })
        }

    private fun sentBodies() = calls.filter { it.method == "sendMessage" }.map { it.body }

    private val ackCard = NotificationCard(title = "t", interactions = listOf(CardFormatterService.ACKNOWLEDGE))

    @Test
    fun `flag on - publisher renders the acknowledge row`() =
        runBlocking {
            val dependencies =
                buildDependencies(
                    config(TelegramUpdatesMode.WEBHOOK),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )

            dependencies.notificationPublisher.sendCard(ackCard)

            assertTrue(sentBodies().single().contains("mwn1:ack:"), sentBodies().single())
            dependencies.close()
        }

    @Test
    fun `flag off - publisher ignores interactions`() =
        runBlocking {
            val dependencies =
                buildDependencies(
                    AppConfig(notifications = NotificationConfig(botToken = "1:T", chatId = "-1001")),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )

            dependencies.notificationPublisher.sendCard(ackCard)

            assertFalse(sentBodies().single().contains("callback_data"), sentBodies().single())
            dependencies.close()
        }

    @Test
    fun `flag on - ack with an unknown ref answers expired`() =
        testApplication {
            val dependencies =
                buildDependencies(
                    config(TelegramUpdatesMode.WEBHOOK),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    telegramApiEngine = engine
                )
            application { module(dependencies) }

            val accepted =
                client.post("/api/v1/telegram/webhook") {
                    header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                    contentType(ContentType.Application.Json)
                    setBody(callbackUpdate.replace("mwn1:ping:", "mwn1:ack:"))
                }
            assertEquals(HttpStatusCode.OK, accepted.status)

            eventually { "answerCallbackQuery" in methods() }
            val answer = Json.parseToJsonElement(calls.first { it.method == "answerCallbackQuery" }.body).jsonObject
            assertEquals("⌛ This button has expired.", answer["text"]!!.jsonPrimitive.content)
            dependencies.close()
        }
}
