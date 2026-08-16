package app.hononeko.notifier.domain.service

import app.hononeko.notifier.config.YamlParser
import app.hononeko.notifier.domain.model.ActionStyle
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.model.TemplateActionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateEngineTest {
    @Test
    fun `interpolate replaces known tags and empties unknown tags`() {
        val engine = TemplateEngine()
        val context = mapOf("title" to "Dune: Part Two", "year" to "2024")
        val result = engine.interpolate("Movie: {title} ({year}) [{unknown}]", context)
        assertEquals("Movie: Dune: Part Two (2024) []", result)
    }

    @Test
    fun `interpolateBody suppresses lines with missing or empty tags`() {
        val engine = TemplateEngine()
        val template =
            """
            ▪ <b>Quality:</b> {quality}
            ▪ <b>Group:</b> {release_group}
            ▪ <b>Tracker:</b> {indexer}
            ▪ <b>Size:</b> {size}
            """.trimIndent()

        val context =
            mapOf(
                "quality" to "WEBDL-1080p",
                "release_group" to "", // empty -> suppress
                // "indexer" missing -> suppress
                "size" to "2.5 GB"
            )

        val rendered = engine.interpolateBody(template, context)
        val expected =
            """
            ▪ <b>Quality:</b> WEBDL-1080p
            ▪ <b>Size:</b> 2.5 GB
            """.trimIndent()

        assertEquals(expected, rendered)
    }

    @Test
    fun `renderActions filters out invalid or empty URLs`() {
        val engine = TemplateEngine()
        val actions =
            listOf(
                TemplateActionConfig(label = "Open WebUI", url = "{webui_url}", style = "PRIMARY"),
                TemplateActionConfig(label = "Invalid Scheme", url = "ftp://example.com"),
                TemplateActionConfig(label = "Missing URL", url = "{missing_url}")
            )

        val context = mapOf("webui_url" to "http://localhost:8080")
        val rendered = engine.renderActions(actions, context)

        assertEquals(1, rendered.size)
        assertEquals("Open WebUI", rendered[0].label)
        assertEquals("http://localhost:8080", rendered[0].url)
        assertEquals(ActionStyle.PRIMARY, rendered[0].style)
    }

    @Test
    fun `renderCard applies custom template when present`() {
        val yaml =
            """
            events:
              grab:
                title: "🎬 Downloading: {title}"
                subtitle: "{instance_name}"
                body: |
                  ▪ <b>Calidad:</b> {quality}
                  ▪ <b>Tamaño:</b> {size}
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        val engine = TemplateEngine(config)

        val context =
            mapOf(
                "title" to "Breaking Bad",
                "instance_name" to "Sonarr-4K",
                "quality" to "1080p",
                "size" to "1.5 GB"
            )

        val card =
            engine.renderCard(
                eventName = "grab",
                defaultTitle = "Default Title",
                defaultSubtitle = "Default Subtitle",
                defaultLevel = NotificationLevel.PROGRESS,
                defaultBody = null,
                defaultArtworkUrl = "http://example.com/poster.jpg",
                defaultActions = emptyList(),
                context = context
            )

        assertEquals("🎬 Downloading: Breaking Bad", card.title)
        assertEquals("Sonarr-4K", card.subtitle)
        assertEquals("▪ <b>Calidad:</b> 1080p\n▪ <b>Tamaño:</b> 1.5 GB", card.overview)
    }
}
