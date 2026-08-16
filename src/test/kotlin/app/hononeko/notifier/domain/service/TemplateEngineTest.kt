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

        assertEquals("", engine.interpolate(null, context))
        assertEquals("", engine.interpolate("", context))
    }

    @Test
    fun `interpolateBody handles null, blank, static lines, and line suppression`() {
        val engine = TemplateEngine()
        val context =
            mapOf(
                "quality" to "WEBDL-1080p",
                "release_group" to "", // empty -> suppress
                // "indexer" missing -> suppress
                "size" to "2.5 GB"
            )

        assertEquals("", engine.interpolateBody(null, context))
        assertEquals("", engine.interpolateBody("", context))

        val template =
            """
            Static Header

            ▪ <b>Quality:</b> {quality}
            ▪ <b>Group:</b> {release_group}
            ▪ <b>Tracker:</b> {indexer}
            ▪ <b>Size:</b> {size}


            Static Footer
            """.trimIndent()

        val rendered = engine.interpolateBody(template, context)
        val expected =
            """
            Static Header

            ▪ <b>Quality:</b> WEBDL-1080p
            ▪ <b>Size:</b> 2.5 GB

            Static Footer
            """.trimIndent()

        assertEquals(expected, rendered)
    }

    @Test
    fun `renderActions filters out invalid or empty URLs and handles unknown styles`() {
        val engine = TemplateEngine()
        val actions =
            listOf(
                TemplateActionConfig(label = "Open WebUI", url = "{webui_url}", style = "PRIMARY"),
                TemplateActionConfig(
                    label = "Default Style",
                    url = "http://localhost:8080/test",
                    style = "INVALID_STYLE"
                ),
                TemplateActionConfig(label = "Invalid Scheme", url = "ftp://example.com"),
                TemplateActionConfig(label = "Missing URL", url = "{missing_url}"),
                TemplateActionConfig(label = "", url = "http://localhost:8080")
            )

        val context = mapOf("webui_url" to "http://localhost:8080")
        val rendered = engine.renderActions(actions, context)

        assertEquals(2, rendered.size)
        assertEquals("Open WebUI", rendered[0].label)
        assertEquals("http://localhost:8080", rendered[0].url)
        assertEquals(ActionStyle.PRIMARY, rendered[0].style)

        assertEquals("Default Style", rendered[1].label)
        assertEquals(ActionStyle.DEFAULT, rendered[1].style)
    }

    @Test
    fun `renderCard applies custom template when present and falls back gracefully`() {
        val yaml =
            """
            events:
              grab:
                title: "🎬 Downloading: {title}"
                subtitle: "{instance_name}"
                artwork_url: "{poster_url}"
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
                "poster_url" to "http://example.com/custom.jpg",
                "quality" to "1080p",
                "size" to "1.5 GB"
            )

        val customCard =
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

        assertEquals("🎬 Downloading: Breaking Bad", customCard.title)
        assertEquals("Sonarr-4K", customCard.subtitle)
        assertEquals("http://example.com/custom.jpg", customCard.artworkUrl)
        assertEquals("▪ <b>Calidad:</b> 1080p\n▪ <b>Tamaño:</b> 1.5 GB", customCard.overview)

        // Fallback for unregistered event
        val fallbackCard =
            engine.renderCard(
                eventName = "unknown_event",
                defaultTitle = "Default Title",
                defaultSubtitle = "Default Subtitle",
                defaultLevel = NotificationLevel.INFO,
                defaultBody = "Default Body",
                defaultArtworkUrl = "http://example.com/default.jpg",
                defaultActions = emptyList(),
                context = emptyMap()
            )

        assertEquals("Default Title", fallbackCard.title)
        assertEquals("Default Subtitle", fallbackCard.subtitle)
        assertEquals("Default Body", fallbackCard.overview)
        assertEquals("http://example.com/default.jpg", fallbackCard.artworkUrl)
    }
}
