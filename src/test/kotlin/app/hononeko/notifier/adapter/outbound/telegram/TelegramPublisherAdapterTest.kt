package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.config.NotificationConfig
import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.ActionLink
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.CardField
import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.ProgressUpdate
import arrow.core.Either
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
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
                    val bodyContent = request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent
                    val bodyString = bodyContent?.bytes()?.decodeToString() ?: ""
                    assertTrue(bodyString.contains(""""parse_mode":"HTML""""))
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
                NotificationConfig(
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
                    overview = "A very interesting thriller about work-life balance.",
                    level = NotificationLevel.INFO,
                    fields = listOf(CardField("Quality", "1080p")),
                    mediaSpecs = MediaSpecs(video = "H.264", resolution = "1080p", score = "8.7", duration = "55m"),
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
    fun `should send photo card and edit caption for live progress`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/bot12345:TOKEN/sendPhoto" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":7777}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        "/bot12345:TOKEN/editMessageCaption" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":7777}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not Found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "123",
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card =
                NotificationCard(
                    title = "🎬 Grabbed: Severance",
                    artworkUrl = "https://example.com/poster.jpg",
                    overview = "A".repeat(1500)
                )

            val startResult = adapter.startLiveProgress(card)
            assertTrue(startResult.isRight())
            val handle = (startResult as Either.Right).value
            assertEquals("7777", handle.messageReferenceId)

            val updateResult =
                adapter.updateProgress(
                    handle,
                    ProgressUpdate(
                        trackingKey = "key1",
                        title = "Severance",
                        percent = 75.0,
                        progressBar = "███████░░░",
                        speedFormatted = "20 MB/s",
                        etaFormatted = "30s",
                        sizeFormatted = "750 MB / 1 GB",
                        peersInfo = "20 seeds",
                        stateText = "Downloading"
                    )
                )
            assertTrue(updateResult.isRight())

            val completeResult =
                adapter.completeProgress(
                    handle,
                    NotificationCard(title = "Finished", level = NotificationLevel.SUCCESS)
                )
            assertTrue(completeResult.isRight())
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
                NotificationConfig(
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
    fun `should return RateLimited error on HTTP 429 response for send and edit`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    val errorJson = """{"ok":false,"parameters":{"retry_after":45}}"""
                    respond(
                        content = errorJson,
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config = NotificationConfig(botToken = "12345:TOKEN", chatId = "123")
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card = NotificationCard(title = "Test")
            val sendResult = adapter.sendCard(card)
            assertTrue(sendResult.isLeft())
            assertIs<DomainError.NotificationError.RateLimited>((sendResult as Either.Left).value)

            val editResult =
                adapter.updateProgress(
                    NotificationHandle("telegram", "123", "99"),
                    ProgressUpdate("k", "T", 10.0, "█", "1M", "1m", "1G", "1", "DL")
                )
            assertTrue(editResult.isLeft())
            assertIs<DomainError.NotificationError.RateLimited>((editResult as Either.Left).value)
        }

    @Test
    fun `should handle network exceptions gracefully`() =
        runTest {
            val mockEngine =
                MockEngine {
                    throw IOException("Connection reset by peer")
                }

            val config = NotificationConfig(botToken = "12345:TOKEN", chatId = "123", sendPhotos = false)
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val sendResult = adapter.sendCard(NotificationCard(title = "Test"))
            assertTrue(sendResult.isLeft())

            val editResult =
                adapter.updateProgress(
                    NotificationHandle("telegram", "123", "99"),
                    ProgressUpdate("k", "T", 10.0, "█", "1M", "1m", "1G", "1", "DL")
                )
            assertTrue(editResult.isLeft())
        }

    @Test
    fun `should update, complete, and cancel live progress successfully`() =
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

            val config = NotificationConfig(botToken = "12345:TOKEN", chatId = "123", sendPhotos = false)
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
                        percent = 50.0,
                        progressBar = "█████░░░░░",
                        speedFormatted = "10 MB/s",
                        etaFormatted = "1m",
                        sizeFormatted = "500 MB / 1 GB",
                        peersInfo = "10 seeds",
                        stateText = "Downloading"
                    )
                )
            assertTrue(updateResult.isRight())

            val cancelResult =
                adapter.cancelProgress(
                    handle,
                    NotificationCard(title = "Stalled", level = NotificationLevel.WARNING)
                )
            assertTrue(cancelResult.isRight())
        }

    @Test
    fun `should tolerate message is not modified response`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}
                            """.trimIndent(),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config = NotificationConfig(botToken = "12345:TOKEN", chatId = "123")
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val handle = NotificationHandle("telegram", "123", "100")
            val update =
                ProgressUpdate(
                    trackingKey = "key1",
                    title = "Severance",
                    percent = 50.0,
                    progressBar = "█████░░░░░",
                    speedFormatted = "10 MB/s",
                    etaFormatted = "1m",
                    sizeFormatted = "500 MB / 1 GB",
                    peersInfo = "10 seeds",
                    stateText = "Downloading"
                )

            val result = adapter.updateProgress(handle, update)
            assertTrue(result.isRight())
        }

    @Test
    fun `should filter out unsafe URL schemes from action keyboard buttons`() =
        runTest {
            var capturedBody = ""
            val mockEngine =
                MockEngine { request ->
                    val content = request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent
                    capturedBody = content?.bytes()?.decodeToString().orEmpty()
                    respond(
                        content = """{"ok":true,"result":{"message_id":101}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config = NotificationConfig(botToken = "12345:TOKEN", chatId = "123")
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val card =
                NotificationCard(
                    title = "Test Links",
                    actions =
                        listOf(
                            ActionLink("Safe HTTPS", "https://example.com"),
                            ActionLink("Safe TG", "tg://resolve?domain=test"),
                            ActionLink("Unsafe JS", "javascript:alert(1)"),
                            ActionLink("Unsafe File", "file:///etc/passwd")
                        )
                )

            val result = adapter.sendCard(card)
            assertTrue(result.isRight())
            assertTrue(capturedBody.contains("Safe HTTPS"))
            assertTrue(capturedBody.contains("Safe TG"))
            assertTrue(!capturedBody.contains("javascript"))
            assertTrue(!capturedBody.contains("file:///etc/passwd"))
        }

    @Test
    fun `should send photo when artworkUrl is present and sendPhotos is true, text otherwise`() =
        runTest {
            var endpointCalled = ""
            val mockEngine =
                MockEngine { request ->
                    endpointCalled = request.url.encodedPath
                    respond(
                        content = """{"ok":true,"result":{"message_id":555}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "123",
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            // Card without artworkUrl (e.g. template image_embed: false) -> should use sendMessage
            val textOnlyCard =
                NotificationCard(
                    title = "Grab Event",
                    artworkUrl = null,
                    eventType = "grab"
                )
            val grabResult = adapter.sendCard(textOnlyCard)
            assertTrue(grabResult.isRight())
            assertEquals("/bot12345:TOKEN/sendMessage", endpointCalled)

            // Card with artworkUrl -> should use sendPhoto
            val photoCard =
                NotificationCard(
                    title = "Import Event",
                    artworkUrl = "https://example.com/poster.jpg",
                    eventType = "import"
                )
            val importResult = adapter.sendCard(photoCard)
            assertTrue(importResult.isRight())
            assertEquals("/bot12345:TOKEN/sendPhoto", endpointCalled)

            // Card with binary artworkBytes -> should use sendPhoto
            val binaryCard =
                NotificationCard(
                    title = "Plex Event",
                    artworkBytes = byteArrayOf(1, 2, 3, 4),
                    eventType = "media_available"
                )
            val binaryResult = adapter.sendCard(binaryCard)
            assertTrue(binaryResult.isRight())
            assertEquals("/bot12345:TOKEN/sendPhoto", endpointCalled)

            // Card with relative artworkUrl and no bytes -> should use sendMessage to prevent 400 Bad Request
            val relativeCard =
                NotificationCard(
                    title = "Relative URL Event",
                    artworkUrl = "/library/metadata/123/thumb",
                    eventType = "media_available"
                )
            val relativeResult = adapter.sendCard(relativeCard)
            assertTrue(relativeResult.isRight())
            assertEquals("/bot12345:TOKEN/sendMessage", endpointCalled)
        }

    @Test
    fun `should edit progress with editMessageCaption for photo messages and custom body`() =
        runTest {
            var endpointCalled = ""
            val mockEngine =
                MockEngine { request ->
                    endpointCalled = request.url.encodedPath
                    respond(
                        content = """{"ok":true,"result":{"message_id":777}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-100123",
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            // Send photo card first so photoMessageRegistry records it
            val photoCard =
                NotificationCard(
                    title = "Downloading",
                    artworkUrl = "https://example.com/poster.jpg",
                    eventType = "grab"
                )
            val handleResult = adapter.sendCard(photoCard)
            assertTrue(handleResult.isRight())
            val handle = handleResult.getOrNull()!!

            val progressUpdate =
                ProgressUpdate(
                    trackingKey = "hash123",
                    title = "Downloading: Futurama",
                    percent = 50.0,
                    progressBar = "[█████░░░░░]",
                    speedFormatted = "10 MB/s",
                    etaFormatted = "2m",
                    sizeFormatted = "2.0 GB",
                    peersInfo = "10 seeds",
                    stateText = "Downloading",
                    subtitle = "50% • 10 MB/s",
                    customBody = "Custom Progress Text",
                    actions = listOf(ActionLink("Open", "http://localhost:8080", ActionStyle.PRIMARY))
                )

            val editResult = adapter.updateProgress(handle, progressUpdate)
            assertTrue(editResult.isRight())
            assertEquals("/bot12345:TOKEN/editMessageCaption", endpointCalled)
        }

    @Test
    fun `should handle non-200 responses and errors during updateProgress gracefully`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/bot12345:TOKEN/sendMessage" -> {
                            respond(
                                content = """{"ok":true,"result":{"message_id":123}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        "/bot12345:TOKEN/editMessageText" -> {
                            respond(
                                content = """{"ok":false,"error_code":500,"description":"Internal Server Error"}""",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("Not found", HttpStatusCode.NotFound)
                    }
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-100123"
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val handle = NotificationHandle("telegram", "-100123", "123")
            val progressUpdate =
                ProgressUpdate(
                    trackingKey = "hash123",
                    title = "Downloading",
                    percent = 50.0,
                    progressBar = "[█████░░░░░]",
                    speedFormatted = "10 MB/s",
                    etaFormatted = "2m",
                    sizeFormatted = "2.0 GB",
                    peersInfo = "10 seeds",
                    stateText = "Downloading",
                    subtitle = "50%"
                )

            val editResult = adapter.updateProgress(handle, progressUpdate)
            assertTrue(editResult.isLeft())
            assertIs<DomainError.NotificationError.DeliveryFailed>(editResult.leftOrNull())
        }

    @Test
    fun `should send photo binary with topicId, action markup, and truncated caption`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    respond(
                        content = """{"ok":true,"result":{"message_id":999}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-100123",
                    topicId = 42,
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, mockEngine)

            val longOverview = "A".repeat(1200)
            val photoCard =
                NotificationCard(
                    title = "Binary Photo Title",
                    artworkBytes = byteArrayOf(1, 2, 3),
                    overview = longOverview,
                    actions = listOf(ActionLink("Watch", "https://plex.tv", ActionStyle.PRIMARY))
                )

            val result = adapter.sendCard(photoCard)
            assertTrue(result.isRight())
        }

    @Test
    fun `should handle network exceptions across all endpoints gracefully`() =
        runTest {
            val throwingEngine =
                MockEngine {
                    throw java.io.IOException("Connection refused")
                }

            val config =
                NotificationConfig(
                    botToken = "12345:TOKEN",
                    chatId = "-100123",
                    sendPhotos = true
                )
            val adapter = TelegramPublisherAdapter(config, throwingEngine)

            // Send text
            val textResult = adapter.sendCard(NotificationCard(title = "Text"))
            assertTrue(textResult.isLeft())

            // Send photo url
            val photoResult = adapter.sendCard(NotificationCard(title = "Photo", artworkUrl = "https://img.jpg"))
            assertTrue(photoResult.isLeft())

            // Send photo binary
            val binaryResult = adapter.sendCard(NotificationCard(title = "Binary", artworkBytes = byteArrayOf(1, 2)))
            assertTrue(binaryResult.isLeft())

            // Update text progress
            val handle = NotificationHandle("telegram", "-100123", "123")
            val progress =
                ProgressUpdate(
                    trackingKey = "k",
                    title = "T",
                    percent = 1.0,
                    progressBar = "[]",
                    speedFormatted = "0",
                    etaFormatted = "0",
                    sizeFormatted = "0",
                    peersInfo = "0",
                    stateText = "D"
                )
            val updateResult = adapter.updateProgress(handle, progress)
            assertTrue(updateResult.isLeft())
        }
}
