package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.config.TelegramConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.ProgressUpdate
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramPublisherAdapterTest {
    @Test
    fun `should send text card with keyboard successfully`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/bot12345:TOKEN/sendMessage" -> {
                            val successJson =
                                """
                                {
                                    "ok": true,
                                    "result": {
                                        "message_id": 9988
                                    }
                                }
                                """.trimIndent()
                            respond(
                                content = successJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                TelegramConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-1001234567890",
                    topicId = 42,
                    sendPhotos = false
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card =
                NotificationCard(
                    title = "🎬 Grabbed: Severance",
                    subtitle = "Sonarr",
                    level = NotificationLevel.INFO,
                    fields = listOf(CardField("Quality", "1080p")),
                    mediaSpecs = MediaSpecs(video = "H.264", resolution = "1080p"),
                    actions = listOf(ActionLink("Open WebUI", "http://localhost:8080", ActionStyle.PRIMARY))
                )

            val result = adapter.sendCard(card)
            assertTrue(result.isRight())

            val handle = (result as Either.Right).value
            assertEquals("telegram", handle.providerId)
            assertEquals("-1001234567890", handle.channelOrChatId)
            assertEquals("9988", handle.messageReferenceId)
        }

    @Test
    fun `should fallback to sendMessage when sendPhoto fails with 400 Bad Request`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/bot12345:TOKEN/sendPhoto" -> {
                            val errorJson = """{"ok":false,"error_code":400,"description":"Bad Request: failed"}"""
                            respond(
                                content = errorJson,
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        "/bot12345:TOKEN/sendMessage" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":9989}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                TelegramConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-1001234567890",
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card =
                NotificationCard(
                    title = "🎬 Grabbed: Dune",
                    artworkUrl = "https://invalid-host/poster.jpg",
                    level = NotificationLevel.INFO
                )

            val result = adapter.sendCard(card)
            assertTrue(result.isRight())
            assertEquals("9989", (result as Either.Right).value.messageReferenceId)
        }

    @Test
    fun `should return RateLimited error on HTTP 429 response`() =
        runTest {
            val mockEngine =
                MockEngine {
                    val errorJson = """{"ok":false,"parameters":{"retry_after":45}}"""
                    respond(
                        content = errorJson,
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config = TelegramConfig(botToken = "12345:TOKEN", chatId = "123")
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card = NotificationCard(title = "Test")
            val result = adapter.sendCard(card)

            assertTrue(result.isLeft())
            val error = (result as Either.Left).value
            assertIs<DomainError.NotificationError.RateLimited>(error)
            assertEquals(45, error.retryAfterSeconds)
        }

    @Test
    fun `should update and complete live progress successfully`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/bot12345:TOKEN/sendMessage" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":100}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        "/bot12345:TOKEN/editMessageText" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":100}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config = TelegramConfig(botToken = "12345:TOKEN", chatId = "123", sendPhotos = false)
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val startResult = adapter.startLiveProgress(NotificationCard(title = "Downloading"))
            assertTrue(startResult.isRight())
            val handle = (startResult as Either.Right).value

            val updateResult =
                adapter.updateProgress(
                    handle,
                    ProgressUpdate(
                        trackingKey = "key1",
                        title = "Severance",
                        percent = 50,
                        progressBar = "█████░░░░░",
                        speedFormatted = "10 MB/s",
                        etaFormatted = "1m",
                        sizeFormatted = "500 MB / 1 GB",
                        peersInfo = "10 seeds",
                        stateText = "Downloading"
                    )
                )
            assertTrue(updateResult.isRight())

            val completeResult =
                adapter.completeProgress(
                    handle,
                    NotificationCard(title = "Completed", level = NotificationLevel.SUCCESS)
                )
            assertTrue(completeResult.isRight())
        }
}
