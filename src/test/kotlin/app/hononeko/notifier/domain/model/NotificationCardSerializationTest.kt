package app.hononeko.notifier.domain.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationCardSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round trip keeps every field except artworkBytes`() {
        val original =
            NotificationCard(
                title = "Title",
                subtitle = "Subtitle",
                overview = "Overview",
                level = NotificationLevel.WARNING,
                fields = listOf(CardField("Name", "Value", inline = false)),
                mediaSpecs =
                    MediaSpecs(
                        video = "x265",
                        audio = "EAC3",
                        resolution = "2160p",
                        sizeFormatted = "4 GB",
                        score = "100",
                        duration = "45m",
                        releaseGroup = "GRP"
                    ),
                customBody = "Body",
                artworkUrl = "https://example.com/poster.jpg",
                artworkBytes = byteArrayOf(1),
                actions = listOf(ActionLink("📖 Wiki", "https://wiki.example.com", ActionStyle.PRIMARY)),
                eventType = "health",
                silent = true,
                interactions = listOf(CardInteraction("✅ Acknowledge", "ack", mapOf("k" to "v"))),
                footnote = "x"
            )

        val encoded = json.encodeToString(NotificationCard.serializer(), original)
        val decoded = json.decodeFromString(NotificationCard.serializer(), encoded)

        assertEquals(original.copy(artworkBytes = null), decoded)
    }

    @Test
    fun `card without interactions or footnote decodes with defaults`() {
        val decoded = json.decodeFromString(NotificationCard.serializer(), """{"title":"T"}""")

        assertTrue(decoded.interactions.isEmpty())
        assertNull(decoded.footnote)
    }
}
