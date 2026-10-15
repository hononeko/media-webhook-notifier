package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.outbound.state.InMemoryStateStore
import app.hononeko.notifier.adapter.outbound.telegram.TelegramPublisherAdapter
import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.service.BotActionHandler
import app.hononeko.notifier.domain.service.BotInteractionService
import app.hononeko.notifier.domain.service.CallbackRefRegistry
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end harness for button flows: a fake Telegram plus the real publisher, guard and callback processor.
 *
 * The fake records every Bot API call and answers like Telegram, numbering sent messages 0, 1, 2… in send order.
 * It runs on real dispatchers, since the processor's answer budget would otherwise expire in virtual time while
 * MockEngine answers. [handlers] builds the button handlers from the harness's publisher, registry and store.
 */
internal class TelegramFlow(
    allowedUsers: Set<Long> = setOf(42L),
    adminUsers: Set<Long> = emptySet(),
    handlers: TelegramFlow.() -> List<BotActionHandler>
) : AutoCloseable {
    companion object {
        const val CHAT_ID = -1001L

        /** 2025-10-09 08:53 UTC, for deterministic "· 08:53" stamps. */
        const val NOW = 1_760_000_000_000L
    }

    data class Call(
        val method: String,
        val body: JsonObject
    ) {
        fun text(key: String): String = body[key]!!.jsonPrimitive.content

        /** The callback buttons on the message, as label to `callback_data`. */
        val buttons: Map<String, String>
            get() =
                body["reply_markup"]
                    ?.jsonObject
                    ?.get("inline_keyboard")
                    ?.jsonArray
                    ?.flatMap { it.jsonArray }
                    ?.mapNotNull { button ->
                        val data = button.jsonObject["callback_data"] ?: return@mapNotNull null
                        button.jsonObject["text"]!!.jsonPrimitive.content to data.jsonPrimitive.content
                    }?.toMap()
                    .orEmpty()
    }

    val calls = CopyOnWriteArrayList<Call>()

    val engine =
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

    val store = InMemoryStateStore()
    val registry = CallbackRefRegistry(store)
    val publisher =
        TelegramPublisherAdapter(
            NotificationConfig(botToken = "1:T", chatId = CHAT_ID.toString(), sendPhotos = false),
            engine,
            registry
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queryIds = AtomicInteger()
    private val processor =
        TelegramCallbackProcessor(
            guard = TelegramCallbackGuard(allowedUsers, CHAT_ID.toString(), adminUsers),
            useCase = BotInteractionService(handlers(), registry),
            api = TelegramBotApi("1:T", engine),
            scope = scope
        )

    fun calls(method: String): List<Call> = calls.filter { it.method == method }

    /**
     * Presses the button with callback [data] as user [userId] (shown as `@user<id>`), on message [messageId] in
     * [chatId] (their private chat unless given), and returns the answer Telegram showed them.
     */
    suspend fun press(
        data: String,
        userId: Long,
        messageId: Long,
        chatId: Long = userId
    ): String {
        val id = "cq${queryIds.incrementAndGet()}"
        processor.process(
            TelegramCallbackQuery(
                id = id,
                from = TelegramUser(id = userId, firstName = "User$userId", username = "user$userId"),
                message = TelegramMessage(messageId = messageId, chat = TelegramChat(chatId)),
                data = data
            )
        )
        return calls("answerCallbackQuery").single { it.text("callback_query_id") == id }.text("text")
    }

    override fun close() = scope.cancel()
}
