package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YamlParserTest {
    @Test
    fun `parse empty and whitespace yaml returns empty map`() {
        assertTrue(YamlParser.parse("").isEmpty())
        assertTrue(YamlParser.parse("   \n\n  # just comments\n  ").isEmpty())
    }

    @Test
    fun `parse basic key value pairs with comments`() {
        val yaml =
            """
            # Global config
            port: 8080
            enabled: true
            name: "Media Notifier"
            pi: 3.14
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals(8080L, result["port"])
        assertEquals(true, result["enabled"])
        assertEquals("Media Notifier", result["name"])
        assertEquals(3.14, result["pi"])
    }

    @Test
    fun `parse nested map and multiline block scalar`() {
        val yaml =
            """
            theme:
              max_overview_length: 250
              progress_bar_length: 12
              progress_bar_style: "minimal"

            events:
              grab:
                title: "⏳ Queueing: {title}"
                subtitle: "{instance_name}"
                body: |
                  ▪ <b>Quality:</b> {quality}
                  ▪ <b>Size:</b> {size}
                actions:
                  - label: "Open WebUI"
                    url: "http://localhost:8080"
                    style: "PRIMARY"
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        assertEquals(250, config.theme.maxOverviewLength)
        assertEquals(12, config.theme.progressBarLength)
        assertEquals("minimal", config.theme.progressBarStyle)

        val grab = config.events["grab"]
        assertNotNull(grab)
        assertEquals("⏳ Queueing: {title}", grab.title)
        assertEquals("{instance_name}", grab.subtitle)
        assertEquals("▪ <b>Quality:</b> {quality}\n▪ <b>Size:</b> {size}", grab.body?.trim())
        assertEquals(1, grab.actions.size)
        assertEquals("Open WebUI", grab.actions[0].label)
        assertEquals("http://localhost:8080", grab.actions[0].url)
        assertEquals("PRIMARY", grab.actions[0].style)
    }

    @Test
    fun `parse block scalar with chomp strip`() {
        val yaml =
            """
            events:
              test:
                body: |-
                  Line 1
                  Line 2
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        val test = config.events["test"]
        assertNotNull(test)
        assertEquals("Line 1\nLine 2", test.body)
    }
}
