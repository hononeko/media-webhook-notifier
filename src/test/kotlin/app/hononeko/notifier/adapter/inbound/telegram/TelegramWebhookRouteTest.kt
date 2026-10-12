package app.hononeko.notifier.adapter.inbound.telegram

import app.hononeko.notifier.adapter.inbound.web.InboundRateLimiter
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramWebhookRouteTest {
    private val processor = mockk<TelegramCallbackProcessor>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private val callbackUpdate =
        """
        {"update_id":1,"callback_query":{"id":"cq1","from":{"id":42,"is_bot":false,"first_name":"A"},
        "message":{"message_id":5,"chat":{"id":-1001,"type":"supergroup"},"date":1},"data":"mwn1:ping:AAAAAAAAAA"}}
        """.trimIndent()

    private fun webhookTest(
        rateLimiter: InboundRateLimiter = InboundRateLimiter(limitPerMinute = 0),
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        val controller = TelegramCallbackController(webhookSecret = "s3cret", processor = processor, scope = scope)
        application { routing { registerTelegramWebhookRoute(controller, rateLimiter) } }
        block()
    }

    private suspend fun ApplicationTestBuilder.postUpdate(
        body: String,
        secret: String? = "s3cret"
    ): HttpResponse =
        client.post("/api/v1/telegram/webhook") {
            secret?.let { header("X-Telegram-Bot-Api-Secret-Token", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `missing secret header is 401 and processor is not called`() =
        webhookTest {
            assertEquals(HttpStatusCode.Unauthorized, postUpdate(callbackUpdate, secret = null).status)
            coVerify(exactly = 0) { processor.process(any()) }
        }

    @Test
    fun `wrong secret is 401`() =
        webhookTest {
            assertEquals(HttpStatusCode.Unauthorized, postUpdate(callbackUpdate, secret = "s3cret-but-wrong").status)
            coVerify(exactly = 0) { processor.process(any()) }
        }

    @Test
    fun `valid callback update is 200 and reaches the processor`() =
        webhookTest {
            assertEquals(HttpStatusCode.OK, postUpdate(callbackUpdate).status)
            coVerify(exactly = 1) { processor.process(match { it.id == "cq1" && it.from.id == 42L }) }
        }

    @Test
    fun `update without callback_query is 200 and ignored`() =
        webhookTest {
            val messageUpdate =
                """{"update_id":2,"message":{"message_id":9,"chat":{"id":-1001,"type":"group"},"date":1,"text":"hi"}}"""

            assertEquals(HttpStatusCode.OK, postUpdate(messageUpdate).status)
            coVerify(exactly = 0) { processor.process(any()) }
        }

    @Test
    fun `malformed body is 200`() =
        webhookTest {
            assertEquals(HttpStatusCode.OK, postUpdate("not json").status)
            coVerify(exactly = 0) { processor.process(any()) }
        }

    @Test
    fun `rate limit returns 429`() =
        webhookTest(rateLimiter = InboundRateLimiter(limitPerMinute = 1)) {
            assertEquals(HttpStatusCode.OK, postUpdate(callbackUpdate).status)
            val limited = postUpdate(callbackUpdate)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        }
}
