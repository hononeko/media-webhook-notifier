package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.BotInteraction
import app.hononeko.notifier.domain.model.BotReply
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.port.inbound.HandleBotInteractionUseCase
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramCallbackProcessorTest {
    private data class Answer(
        val callbackQueryId: String,
        val text: String,
        val showAlert: Boolean
    )

    private val answers = CopyOnWriteArrayList<Answer>()
    private val interactions = CopyOnWriteArrayList<BotInteraction>()
    private val guard = TelegramCallbackGuard(allowedUserIds = setOf(42L), chatId = "-1001")
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun api(answerStatus: HttpStatusCode) =
        TelegramBotApi(
            botToken = "1:T",
            engine =
                MockEngine { request ->
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    answers +=
                        Answer(
                            body["callback_query_id"]!!.jsonPrimitive.content,
                            body["text"]!!.jsonPrimitive.content,
                            body["show_alert"]!!.jsonPrimitive.boolean
                        )
                    val json =
                        if (answerStatus.value < 300) {
                            """{"ok":true,"result":true}"""
                        } else {
                            """{"ok":false,"error_code":${answerStatus.value},"description":"Bad Request: query is too old"}"""
                        }
                    respond(json, answerStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                }
        )

    /** A processor whose scope mirrors the app's SupervisorJob scope, running on the test's virtual clock. */
    private fun TestScope.processor(
        useCase: HandleBotInteractionUseCase,
        answerStatus: HttpStatusCode = HttpStatusCode.OK
    ): TelegramCallbackProcessor {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        val recording =
            HandleBotInteractionUseCase { interaction ->
                interactions += interaction
                useCase.handle(interaction)
            }
        return TelegramCallbackProcessor(guard, recording, api(answerStatus), scope)
    }

    private fun returning(result: Either<DomainError, BotReply>) = HandleBotInteractionUseCase { result }

    private fun query(
        userId: Long = 42,
        data: String? = "mwn1:pause:AAAAAAAAAA",
        message: TelegramMessage = TelegramMessage(messageId = 10, chat = TelegramChat(-1001))
    ) = TelegramCallbackQuery(
        id = "cq1",
        from = TelegramUser(id = userId, firstName = "Alice", username = "alice"),
        message = message,
        data = data
    )

    @Test
    fun `origin is the clicked text message`() =
        runTest {
            processor(returning(Either.Right(BotReply("ok")))).process(query())

            assertEquals(NotificationHandle("telegram", "-1001", "10", isPhoto = false), interactions.single().origin)
        }

    @Test
    fun `origin marks photo messages`() =
        runTest {
            val photo =
                TelegramMessage(messageId = 10, chat = TelegramChat(-1001), photo = listOf(JsonObject(emptyMap())))

            processor(returning(Either.Right(BotReply("ok")))).process(query(message = photo))

            assertEquals(true, interactions.single().origin?.isPhoto)
        }

    @Test
    fun `unauthorized user is answered with alert and use case is never called`() =
        runTest {
            processor(returning(Either.Right(BotReply("ok")))).process(query(userId = 7))

            assertEquals(listOf(Answer("cq1", "⛔ You're not allowed to control this bot.", true)), answers)
            assertTrue(interactions.isEmpty())
        }

    @Test
    fun `undecodable data gets an empty non-alert answer and no use case call`() =
        runTest {
            processor(returning(Either.Right(BotReply("ok")))).process(query(data = "dl:abc:pause"))

            assertEquals(listOf(Answer("cq1", "", false)), answers)
            assertTrue(interactions.isEmpty())
        }

    @Test
    fun `success answers with the reply text and alert flag`() =
        runTest {
            processor(returning(Either.Right(BotReply("Download paused")))).process(query())

            assertEquals(listOf(Answer("cq1", "Download paused", false)), answers)
            val interaction = interactions.single()
            assertEquals("pause", interaction.operation)
            assertEquals("AAAAAAAAAA", interaction.ref)
            assertEquals("@alice", interaction.actor.displayName)
        }

    @Test
    fun `each interaction error maps to its copy`() =
        runTest {
            processor(
                returning(Either.Left(DomainError.InteractionError.ExpiredCallback("AAAAAAAAAA")))
            ).process(query())
            processor(returning(Either.Left(DomainError.InteractionError.UnknownAction("pause")))).process(query())

            assertEquals(
                listOf(
                    Answer("cq1", "⌛ This button has expired.", true),
                    Answer("cq1", "This action isn't supported.", true)
                ),
                answers
            )
        }

    @Test
    fun `other domain errors answer Action failed`() =
        runTest {
            processor(returning(Either.Left(DomainError.DownloadClientError.ConnectionFailed("u")))).process(query())

            assertEquals(listOf(Answer("cq1", "⚠️ Action failed.", true)), answers)
        }

    @Test
    fun `throwing use case still answers Action failed`() =
        runTest {
            processor({ throw IllegalStateException("handler bug") }).process(query())

            assertEquals(listOf(Answer("cq1", "⚠️ Action failed.", true)), answers)
        }

    @Test
    fun `slow use case answers Working on it and still completes`() =
        runTest {
            val completed = AtomicBoolean(false)
            val slow =
                HandleBotInteractionUseCase {
                    delay(5_000)
                    completed.set(true)
                    Either.Right(BotReply("done"))
                }

            processor(slow).process(query())

            assertEquals(listOf(Answer("cq1", "⏳ Working on it…", false)), answers)
            advanceUntilIdle()
            assertTrue(completed.get())
        }

    @Test
    fun `failed answer call is swallowed`() =
        runTest {
            processor(
                returning(Either.Right(BotReply("ok"))),
                answerStatus = HttpStatusCode.BadRequest
            ).process(query())

            assertEquals(1, answers.size)
        }
}
