package app.hononeko.notifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            disabled: false
            null_val: null
            tilde_val: ~
            quoted_escape: "Hello\nWorld \"Test\" \\"
            single_quoted: 'single quoted string'
            pi: 3.14
            empty_val:
            """.trimIndent()

        val result = YamlParser.parse(yaml)
        assertEquals(8080L, result["port"])
        assertEquals(true, result["enabled"])
        assertEquals(false, result["disabled"])
        assertNull(result["null_val"])
        assertNull(result["tilde_val"])
        assertEquals("Hello\nWorld \"Test\" \\", result["quoted_escape"])
        assertEquals("single quoted string", result["single_quoted"])
        assertEquals(3.14, result["pi"])
        assertNull(result["empty_val"])
    }

    @Test
    fun `parse nested map and multiline block scalar`() {
        val yaml =
            """
            theme:
              max_overview_length: 250
              progress_bar_length: 12
              progress_bar_style: "minimal"
              date_format: "yyyy/MM/dd"

            events:
              grab:
                title: "⏳ Queueing: {title}"
                subtitle: "{instance_name}"
                artwork_url: "https://example.com/art.jpg"
                state_text: "Queued"
                body: |
                  ▪ <b>Quality:</b> {quality}
                  ▪ <b>Size:</b> {size}
                actions:
                  - label: "Open WebUI"
                    url: "http://localhost:8080"
                    style: "PRIMARY"
                    callback: "open_webui"
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        assertEquals(250, config.theme.maxOverviewLength)
        assertEquals(12, config.theme.progressBarLength)
        assertEquals("minimal", config.theme.progressBarStyle)
        assertEquals("yyyy/MM/dd", config.theme.dateFormat)

        val grab = config.events["grab"]
        assertNotNull(grab)
        assertEquals("⏳ Queueing: {title}", grab.title)
        assertEquals("{instance_name}", grab.subtitle)
        assertEquals("https://example.com/art.jpg", grab.artworkUrl)
        assertEquals("Queued", grab.stateText)
        assertEquals("▪ <b>Quality:</b> {quality}\n▪ <b>Size:</b> {size}", grab.body?.trim())
        assertEquals(1, grab.actions.size)
        assertEquals("Open WebUI", grab.actions[0].label)
        assertEquals("http://localhost:8080", grab.actions[0].url)
        assertEquals("PRIMARY", grab.actions[0].style)
        assertEquals("open_webui", grab.actions[0].callback)
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

    @Test
    fun `parse simple scalar list and empty list items`() {
        val yaml =
            """
            tags:
              - alpha
              - beta
              - 123
              -
            """.trimIndent()

        val result = YamlParser.parse(yaml)

        @Suppress("UNCHECKED_CAST")
        val tags = result["tags"] as List<Any?>
        assertEquals(4, tags.size)
        assertEquals("alpha", tags[0])
        assertEquals("beta", tags[1])
        assertEquals(123L, tags[2])
        assertNull(tags[3])
    }

    @Test
    fun `parse 1-level hierarchical events structure correctly`() {
        val yaml =
            """
            events:
              download:
                grab:
                  title: "⏳ Queueing: {title}"
                progress:
                  title: "⬇️ Downloading: {title}"
              seerr:
                request:
                  title: "🛎️ New Request: {subject}"
                issue:
                  title: "⚠️ Issue: {subject}"
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        assertNotNull(config.events["download.grab"])
        assertNotNull(config.events["download_grab"])
        assertNotNull(config.events["grab"])
        assertEquals("⏳ Queueing: {title}", config.events["grab"]?.title)

        assertNotNull(config.events["seerr.request"])
        assertNotNull(config.events["seerr_request"])
        assertNotNull(config.events["request"])
        assertEquals("🛎️ New Request: {subject}", config.events["request"]?.title)

        assertNotNull(config.events["seerr.issue"])
        assertNotNull(config.events["seerr_issue"])
        assertNotNull(config.events["issue"])
        assertEquals("⚠️ Issue: {subject}", config.events["issue"]?.title)
    }

    @Test
    fun `parse event template image_embed flags and aliases`() {
        val yaml =
            """
            events:
              servarr:
                grab:
                  title: "Grab"
                  image_embed: false
                import:
                  title: "Import"
                  embed_image: true
              download:
                complete:
                  title: "Complete"
                  send_photos: false
                stalled:
                  title: "Stalled"
                  photo: true
            """.trimIndent()

        val config = YamlParser.parseTemplateConfig(yaml)
        assertEquals(false, config.events["grab"]?.imageEmbed)
        assertEquals(true, config.events["import"]?.imageEmbed)
        assertEquals(false, config.events["complete"]?.imageEmbed)
        assertEquals(true, config.events["stalled"]?.imageEmbed)
    }
}
