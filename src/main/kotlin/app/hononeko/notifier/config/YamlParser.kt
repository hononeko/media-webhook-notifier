package app.hononeko.notifier.config

import app.hononeko.notifier.domain.model.TemplateConfig
import app.hononeko.notifier.domain.model.ThemeConfig

/** A small YAML subset parser for the card templates, plus the mapping onto [TemplateConfig]. */
object YamlParser {
    fun parse(yaml: String): Map<String, Any?> {
        if (yaml.isBlank()) return emptyMap()
        val lines = yaml.lines()
        val parser = LineParser(lines)
        return parser.parseMap(parentIndent = -1)
    }

    fun parseTemplateConfig(
        yaml: String,
        defaultTheme: ThemeConfig = ThemeConfig()
    ): TemplateConfig {
        val root = parse(yaml)
        return TemplateConfigMapper.mapToTemplateConfig(root, defaultTheme)
    }
}
