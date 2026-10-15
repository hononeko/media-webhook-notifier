package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.service.CallbackRefRegistry
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramDirectMessageTest {
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private var nextMessageId = 100

    /** Chats in [blocked] answer like a user who never started the bot. */
    private fun engine(blocked: Set<String> = emptySet()) =
        MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            sent += body
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            if (body["chat_id"]!!.jsonPrimitive.content in blocked) {
                respond(
                    """{"ok":false,"error_code":403,"description":"Forbidden: bot can't initiate conversation with a user"}""",
                    HttpStatusCode.Forbidden,
                    headers
                )
            } else {
                respond("""{"ok":true,"result":{"message_id":${nextMessageId++}}}""", HttpStatusCode.OK, headers)
            }
        }

    private val registry = CallbackRefRegistry(InMemoryStateStore())
    private val config = NotificationConfig(botToken = "1:T", chatId = "-1001", topicId = 77, sendPhotos = false)
    private val card =
        NotificationCard(title = "New Request", interactions = listOf(CardInteraction("✅ Approve", "seerr_approve")))

    private fun refOf(body: JsonObject): String =
        body["reply_markup"]!!
            .jsonObject["inline_keyboard"]!!
            .jsonArray
            .flatMap { it.jsonArray }
            .single()
            .jsonObject["callback_data"]!!
            .jsonPrimitive.content
            .substringAfterLast(':')

    @Test
    fun `each admin gets a copy in their private chat, sharing one ref that lists every copy`() =
        runTest {
            val publisher = TelegramPublisherAdapter(config, engine(), registry)

            val results = publisher.sendDirect(card, listOf("1", "2"))

            assertEquals(listOf("1", "2"), sent.map { it["chat_id"]!!.jsonPrimitive.content })
            assertTrue(sent.none { it.containsKey("message_thread_id") }, "the channel's forum topic must not apply")
            val ref = sent.map { refOf(it) }.distinct().single()
            val handles = listOf(NotificationHandle("telegram", "1", "100"), NotificationHandle("telegram", "2", "101"))
            assertEquals(handles, results.values.map { it.getOrNull() })
            assertEquals(handles, registry.resolve(ref)!!.deliveries)
        }

    @Test
    fun `an admin who never started the bot is reported and left out of the copies`() =
        runTest {
            val publisher = TelegramPublisherAdapter(config, engine(blocked = setOf("1")), registry)

            val results = publisher.sendDirect(card, listOf("1", "2"))

            assertTrue(results.getValue("1").isLeft())
            assertEquals(
                listOf(NotificationHandle("telegram", "2", "100")),
                registry.resolve(refOf(sent[1]))!!.deliveries
            )
        }

    @Test
    fun `the ref is released when no copy was delivered`() =
        runTest {
            val publisher = TelegramPublisherAdapter(config, engine(blocked = setOf("1", "2")), registry)

            publisher.sendDirect(card, listOf("1", "2"))

            assertNull(registry.resolve(refOf(sent[0])))
        }

    @Test
    fun `a private message sharing a channel photo's id is still edited as text`() =
        runTest {
            val methods = CopyOnWriteArrayList<String>()
            val fixedId =
                MockEngine { request ->
                    methods += request.url.encodedPath.substringAfterLast('/')
                    respond(
                        """{"ok":true,"result":{"message_id":5}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            val publisher = TelegramPublisherAdapter(config.copy(sendPhotos = true), fixedId, registry)
            val private =
                publisher
                    .sendDirect(
                        NotificationCard(title = "Text"),
                        listOf("1")
                    ).getValue("1")
                    .getOrNull()!!

            publisher.sendCard(NotificationCard(title = "Poster", artworkUrl = "https://img/p.jpg"))

            publisher.editCard(private.copy(isPhoto = false), NotificationCard(title = "Edited"))

            assertEquals(listOf("sendMessage", "sendPhoto", "editMessageText"), methods)
        }

    @Test
    fun `an edit with callbackRef keeps the buttons on that ref instead of registering a new one`() =
        runTest {
            val publisher = TelegramPublisherAdapter(config, engine(), registry)
            val handle = publisher.sendDirect(card, listOf("1")).getValue("1").getOrNull()!!
            val ref = refOf(sent.single())
            sent.clear()

            publisher.editCard(handle, card.copy(title = "Edited"), callbackRef = ref)

            assertEquals(ref, refOf(sent.single()))
        }
}
