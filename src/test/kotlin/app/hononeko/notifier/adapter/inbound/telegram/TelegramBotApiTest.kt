package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.domain.error.DomainError
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramBotApiTest {
    private val token = "1:T"
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<JsonObject>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        TelegramBotApi(
            botToken = token,
            engine =
                MockEngine { request ->
                    requests += request
                    bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    handler(request)
                }
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val okTrue = """{"ok":true,"result":true}"""

    @Test
    fun `getUpdates posts offset, timeout and allowed_updates and parses callback updates`() =
        runTest {
            val result =
                api {
                    json(
                        """
                        {"ok":true,"result":[{"update_id":10,"callback_query":{"id":"cq1","from":{"id":42,"is_bot":false,"first_name":"A"},
                        "message":{"message_id":5,"chat":{"id":-1001,"type":"supergroup"},"date":1},"data":"mwn1:ping:AAAAAAAAAA"}}]}
                        """.trimIndent()
                    )
                }.getUpdates(offset = 10, timeoutSeconds = 25)

            assertEquals("/bot1:T/getUpdates", requests.single().url.encodedPath)
            val body = bodies.single()
            assertEquals(10L, body["offset"]!!.jsonPrimitive.long)
            assertEquals(25, body["timeout"]!!.jsonPrimitive.int)
            assertEquals(listOf("callback_query"), body["allowed_updates"]!!.jsonArray.map { it.jsonPrimitive.content })
            val update = assertIs<Either.Right<List<TelegramUpdate>>>(result).value.single()
            assertEquals(10L, update.updateId)
            assertEquals("cq1", update.callbackQuery?.id)
            assertEquals(
                -1001L,
                update.callbackQuery
                    ?.message
                    ?.chat
                    ?.id
            )
        }

    @Test
    fun `getUpdates omits offset when none is known yet`() =
        runTest {
            api { json("""{"ok":true,"result":[]}""") }.getUpdates(offset = null, timeoutSeconds = 25)

            assertFalse("offset" in bodies.single())
        }

    @Test
    fun `getUpdates extends the request timeout beyond the long poll`() =
        runTest {
            api { json("""{"ok":true,"result":[]}""") }.getUpdates(offset = null, timeoutSeconds = 25)

            val timeouts = requests.single().getCapabilityOrNull(HttpTimeoutCapability)!!
            assertEquals(35_000L, timeouts.requestTimeoutMillis)
            assertEquals(35_000L, timeouts.socketTimeoutMillis)
            assertEquals(5_000L, timeouts.connectTimeoutMillis)
        }

    @Test
    fun `answerCallbackQuery truncates text to 200 chars and sends show_alert`() =
        runTest {
            val result = api { json(okTrue) }.answerCallbackQuery("cq1", "x".repeat(250), showAlert = true)

            assertEquals(Either.Right(Unit), result)
            assertEquals("/bot1:T/answerCallbackQuery", requests.single().url.encodedPath)
            val body = bodies.single()
            assertEquals("cq1", body["callback_query_id"]!!.jsonPrimitive.content)
            assertEquals(200, body["text"]!!.jsonPrimitive.content.length)
            assertTrue(body["show_alert"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `setWebhook sends url, secret_token and allowed_updates`() =
        runTest {
            val result = api { json(okTrue) }.setWebhook("https://n.example.com/api/v1/telegram/webhook", "s3cret")

            assertEquals(Either.Right(Unit), result)
            assertEquals("/bot1:T/setWebhook", requests.single().url.encodedPath)
            val body = bodies.single()
            assertEquals("https://n.example.com/api/v1/telegram/webhook", body["url"]!!.jsonPrimitive.content)
            assertEquals("s3cret", body["secret_token"]!!.jsonPrimitive.content)
            assertEquals(listOf("callback_query"), body["allowed_updates"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `deleteWebhook keeps pending updates`() =
        runTest {
            val result = api { json(okTrue) }.deleteWebhook()

            assertEquals(Either.Right(Unit), result)
            assertEquals("/bot1:T/deleteWebhook", requests.single().url.encodedPath)
            assertFalse(bodies.single()["drop_pending_updates"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `429 maps to RateLimited with retry_after`() =
        runTest {
            val result =
                api {
                    json(
                        """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":17}}""",
                        HttpStatusCode.TooManyRequests
                    )
                }.deleteWebhook()

            assertEquals(Either.Left(DomainError.NotificationError.RateLimited("telegram", 17)), result)
        }

    @Test
    fun `409 maps to DeliveryFailed with httpStatus 409`() =
        runTest {
            val result =
                api {
                    json(
                        """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""",
                        HttpStatusCode.Conflict
                    )
                }.getUpdates(offset = null, timeoutSeconds = 25)

            val error = assertIs<DomainError.NotificationError.DeliveryFailed>(assertIs<Either.Left<*>>(result).value)
            assertEquals(409, error.httpStatus)
            assertTrue(error.message.contains("Conflict"), error.message)
        }

    @Test
    fun `ok false on a 2xx response is a failure`() =
        runTest {
            val result =
                api { json("""{"ok":false,"error_code":400,"description":"Bad Request: query is too old"}""") }
                    .answerCallbackQuery("cq1", "x", showAlert = false)

            val error = assertIs<DomainError.NotificationError.DeliveryFailed>(assertIs<Either.Left<*>>(result).value)
            assertEquals(400, error.httpStatus)
        }

    @Test
    fun `io failure maps to DeliveryFailed without status and never leaks the bot token`() =
        runTest {
            val result =
                api {
                    throw IOException(
                        "Request timeout has expired [url=https://api.telegram.org/bot$token/getUpdates]"
                    )
                }.getUpdates(offset = null, timeoutSeconds = 25)

            val error = assertIs<DomainError.NotificationError.DeliveryFailed>(assertIs<Either.Left<*>>(result).value)
            assertNull(error.httpStatus)
            assertFalse(error.message.contains(token), error.message)
            assertFalse(
                error.cause
                    ?.message
                    .orEmpty()
                    .contains(token),
                "cause must not carry the token either"
            )
        }

    @Test
    fun `unresolvable host maps to DeliveryFailed instead of throwing`() =
        runTest {
            val result = api { throw UnresolvedAddressException() }.deleteWebhook()

            val error = assertIs<DomainError.NotificationError.DeliveryFailed>(assertIs<Either.Left<*>>(result).value)
            assertNull(error.httpStatus)
            assertTrue(error.message.contains("resolve"), error.message)
        }
}
