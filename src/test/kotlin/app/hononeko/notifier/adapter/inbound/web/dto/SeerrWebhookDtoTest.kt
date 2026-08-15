package app.hononeko.notifier.adapter.inbound.web.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeerrWebhookDtoTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `should construct and serialize SeerrMediaDto correctly`() {
        val dto =
            SeerrMediaDto(
                mediaType = "movie",
                imdbId = "tt123",
                tmdbId = "456",
                tvdbId = "789",
                jellyfinMediaId = "jf-1",
                status = "AVAILABLE",
                status4k = "PROCESSING"
            )

        assertEquals("movie", dto.mediaType)
        assertEquals("tt123", dto.imdbId)
        assertEquals("456", dto.tmdbId)
        assertEquals("789", dto.tvdbId)
        assertEquals("jf-1", dto.jellyfinMediaId)
        assertEquals("AVAILABLE", dto.status)
        assertEquals("PROCESSING", dto.status4k)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrMediaDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("tt123"))

        val copy = dto.copy(mediaType = "tv")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct and serialize SeerrRequestDto correctly`() {
        val dto =
            SeerrRequestDto(
                requestId = JsonPrimitive(123),
                requestedByEmail = "admin@example.com",
                requestedByUsername = "admin",
                requestedByAvatar = "https://avatar.png",
                requestedByJellyfinUserId = "jf-user-1",
                requestedBySettingsDiscordIds = "111",
                requestedBySettingsTelegramChatId = "222",
                is4k = JsonPrimitive(true)
            )

        assertEquals(JsonPrimitive(123), dto.requestId)
        assertEquals("admin@example.com", dto.requestedByEmail)
        assertEquals("admin", dto.requestedByUsername)
        assertEquals("https://avatar.png", dto.requestedByAvatar)
        assertEquals("jf-user-1", dto.requestedByJellyfinUserId)
        assertEquals("111", dto.requestedBySettingsDiscordIds)
        assertEquals("222", dto.requestedBySettingsTelegramChatId)
        assertEquals(JsonPrimitive(true), dto.is4k)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrRequestDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("admin"))

        val copy = dto.copy(requestedByUsername = "bob")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct and serialize SeerrIssueDto correctly`() {
        val dto =
            SeerrIssueDto(
                issueId = JsonPrimitive("issue-1"),
                issueType = "Audio",
                issueStatus = "OPEN",
                reportedByEmail = "user@example.com",
                reportedByUsername = "reporter",
                reportedByAvatar = "https://avatar.png",
                reportedBySettingsDiscordIds = "333",
                reportedBySettingsTelegramChatId = "444"
            )

        assertEquals(JsonPrimitive("issue-1"), dto.issueId)
        assertEquals("Audio", dto.issueType)
        assertEquals("OPEN", dto.issueStatus)
        assertEquals("user@example.com", dto.reportedByEmail)
        assertEquals("reporter", dto.reportedByUsername)
        assertEquals("https://avatar.png", dto.reportedByAvatar)
        assertEquals("333", dto.reportedBySettingsDiscordIds)
        assertEquals("444", dto.reportedBySettingsTelegramChatId)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrIssueDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("Audio"))

        val copy = dto.copy(issueType = "Video")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct and serialize SeerrCommentDto correctly`() {
        val dto =
            SeerrCommentDto(
                commentId = JsonPrimitive(55),
                commentMessage = "Looks good now",
                commentedByEmail = "dev@example.com",
                commentedByUsername = "dev",
                commentedByAvatar = "https://avatar2.png",
                commentedBySettingsDiscordIds = "555",
                commentedBySettingsTelegramChatId = "666"
            )

        assertEquals(JsonPrimitive(55), dto.commentId)
        assertEquals("Looks good now", dto.commentMessage)
        assertEquals("dev@example.com", dto.commentedByEmail)
        assertEquals("dev", dto.commentedByUsername)
        assertEquals("https://avatar2.png", dto.commentedByAvatar)
        assertEquals("555", dto.commentedBySettingsDiscordIds)
        assertEquals("666", dto.commentedBySettingsTelegramChatId)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrCommentDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("Looks good now"))

        val copy = dto.copy(commentMessage = "Fixed")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct and serialize SeerrExtraDto correctly`() {
        val dto = SeerrExtraDto(name = "Requested By", value = "Alice")
        assertEquals("Requested By", dto.name)
        assertEquals("Alice", dto.value)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrExtraDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("Alice"))

        val copy = dto.copy(value = "Bob")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct and serialize full SeerrWebhookDto correctly`() {
        val dto =
            SeerrWebhookDto(
                notificationType = "MEDIA_PENDING",
                event = "New Request",
                subject = "Severance",
                message = "Requested by Alice",
                image = "https://poster.jpg",
                media = SeerrMediaDto(mediaType = "tv"),
                request = SeerrRequestDto(requestedByUsername = "Alice"),
                issue = SeerrIssueDto(issueType = "Subtitles"),
                comment = SeerrCommentDto(commentMessage = "Missing subs"),
                extra = listOf(SeerrExtraDto("Key", "Value")),
                applicationUrl = "https://overseerr.local",
                url = "https://overseerr.local/tv/123"
            )

        assertEquals("MEDIA_PENDING", dto.notificationType)
        assertEquals("New Request", dto.event)
        assertEquals("Severance", dto.subject)
        assertEquals("Requested by Alice", dto.message)
        assertEquals("https://poster.jpg", dto.image)
        assertNotNull(dto.media)
        assertNotNull(dto.request)
        assertNotNull(dto.issue)
        assertNotNull(dto.comment)
        assertEquals(1, dto.extra?.size)
        assertEquals("https://overseerr.local", dto.applicationUrl)
        assertEquals("https://overseerr.local/tv/123", dto.url)

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<SeerrWebhookDto>(encoded)
        assertEquals(dto, decoded)
        assertEquals(dto.hashCode(), decoded.hashCode())
        assertTrue(dto.toString().contains("Severance"))

        val copy = dto.copy(subject = "Severance Season 2")
        assertNotEquals(dto, copy)
    }

    @Test
    fun `should construct default instances of all DTOs`() {
        val emptyDto = SeerrWebhookDto()
        assertEquals(null, emptyDto.notificationType)
        assertEquals(null, emptyDto.media)

        val emptyMedia = SeerrMediaDto()
        assertEquals(null, emptyMedia.mediaType)

        val emptyRequest = SeerrRequestDto()
        assertEquals(null, emptyRequest.requestId)

        val emptyIssue = SeerrIssueDto()
        assertEquals(null, emptyIssue.issueId)

        val emptyComment = SeerrCommentDto()
        assertEquals(null, emptyComment.commentId)

        val emptyExtra = SeerrExtraDto()
        assertEquals(null, emptyExtra.name)
    }
}
