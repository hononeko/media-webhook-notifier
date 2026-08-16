package app.hononeko.notifier.config

import app.hononeko.notifier.domain.model.EventTemplate
import app.hononeko.notifier.domain.model.TemplateActionConfig
import app.hononeko.notifier.domain.model.TemplateConfig
import app.hononeko.notifier.domain.model.ThemeConfig

object YamlParser {
    fun parse(yaml: String): Map<String, Any?> {
        if (yaml.isBlank()) return emptyMap()
        val lines = yaml.lines()
        val parser = LineParser(lines)
        return parser.parseMap(parentIndent = -1)
    }

    fun parseTemplateConfig(yaml: String): TemplateConfig {
        val root = parse(yaml)
        return mapToTemplateConfig(root)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun mapToTemplateConfig(root: Map<String, Any?>): TemplateConfig {
        val themeMap = root["theme"] as? Map<String, Any?> ?: emptyMap()
        val theme =
            ThemeConfig(
                maxOverviewLength = (themeMap["max_overview_length"] as? Number)?.toInt() ?: 220,
                progressBarLength = (themeMap["progress_bar_length"] as? Number)?.toInt() ?: 10,
                progressBarStyle = themeMap["progress_bar_style"]?.toString() ?: "default",
                dateFormat = themeMap["date_format"]?.toString() ?: "yyyy-MM-dd HH:mm"
            )

        val eventsMap = root["events"] as? Map<String, Any?> ?: emptyMap()
        val events = mutableMapOf<String, EventTemplate>()

        for ((eventName, eventObj) in eventsMap) {
            if (eventObj !is Map<*, *>) continue
            val eventProps = eventObj as Map<String, Any?>
            val actionsList = mutableListOf<TemplateActionConfig>()
            val rawActions = eventProps["actions"] as? List<*> ?: emptyList<Any?>()
            for (actionItem in rawActions) {
                if (actionItem is Map<*, *>) {
                    val actionMap = actionItem as Map<String, Any?>
                    val label = actionMap["label"]?.toString() ?: continue
                    val url = actionMap["url"]?.toString()
                    val callback = actionMap["callback"]?.toString()
                    val style = actionMap["style"]?.toString() ?: "DEFAULT"
                    actionsList.add(
                        TemplateActionConfig(
                            label = label,
                            url = url,
                            callback = callback,
                            style = style
                        )
                    )
                }
            }

            events[eventName] =
                EventTemplate(
                    title = eventProps["title"]?.toString(),
                    subtitle = eventProps["subtitle"]?.toString(),
                    body = eventProps["body"]?.toString(),
                    artworkUrl = eventProps["artwork_url"]?.toString() ?: eventProps["poster_url"]?.toString(),
                    stateText = eventProps["state_text"]?.toString(),
                    actions = actionsList
                )
        }

        return TemplateConfig(
            theme = theme,
            events = events
        )
    }

    private class LineParser(
        private val lines: List<String>
    ) {
        private var index = 0

        fun parseMap(parentIndent: Int): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()

            while (index < lines.size) {
                val rawLine = lines[index]
                val trimmed = stripComment(rawLine).trim()

                if (trimmed.isBlank()) {
                    index++
                    continue
                }

                val currentIndent = countIndent(rawLine)
                if (currentIndent <= parentIndent && parentIndent != -1) {
                    break
                }

                val colonIdx = findKeySeparator(trimmed)
                if (colonIdx == -1) {
                    index++
                    continue
                }

                val key = trimmed.substring(0, colonIdx).trim().trim('"', '\'')
                val valuePart = trimmed.substring(colonIdx + 1).trim()
                index++

                result[key] = parseMapValue(currentIndent, valuePart)
            }

            return result
        }

        private fun parseMapValue(
            currentIndent: Int,
            valuePart: String
        ): Any? =
            when {
                valuePart.isEmpty() -> {
                    val peekIndent = peekNextIndent()
                    if (peekIndent > currentIndent) {
                        val nextTrimmed = peekNextTrimmed()
                        if (nextTrimmed.startsWith("- ") || nextTrimmed == "-") {
                            parseList(currentIndent)
                        } else {
                            parseMap(currentIndent)
                        }
                    } else {
                        null
                    }
                }
                valuePart == "|" || valuePart == "|-" || valuePart == "|+" || valuePart == ">" -> {
                    parseBlockScalar(currentIndent, chomp = valuePart.contains("-"))
                }
                else -> parseScalar(valuePart)
            }

        fun parseList(parentIndent: Int): List<Any?> {
            val result = mutableListOf<Any?>()

            while (index < lines.size) {
                val rawLine = lines[index]
                val trimmed = stripComment(rawLine).trim()

                if (trimmed.isBlank()) {
                    index++
                    continue
                }

                val currentIndent = countIndent(rawLine)
                if (currentIndent <= parentIndent && parentIndent != -1) {
                    break
                }

                if (!trimmed.startsWith("-")) {
                    break
                }

                val itemContent = trimmed.substring(1).trim()
                result.add(parseListItem(itemContent, currentIndent))
            }

            return result
        }

        private fun parseListItem(
            itemContent: String,
            currentIndent: Int
        ): Any? {
            if (itemContent.isEmpty()) {
                index++
                val peekIndent = peekNextIndent()
                return if (peekIndent > currentIndent) parseMap(currentIndent) else null
            }

            val colonIdx = findKeySeparator(itemContent)
            return if (colonIdx != -1) {
                index++
                parseListItemMap(itemContent, colonIdx, currentIndent)
            } else {
                index++
                parseScalar(itemContent)
            }
        }

        private fun parseListItemMap(
            itemContent: String,
            colonIdx: Int,
            currentIndent: Int
        ): Map<String, Any?> {
            val subKey = itemContent.substring(0, colonIdx).trim().trim('"', '\'')
            val subVal = itemContent.substring(colonIdx + 1).trim()
            val itemMap = mutableMapOf<String, Any?>()

            if (subVal.isEmpty()) {
                val peekIndent = peekNextIndent()
                itemMap[subKey] = if (peekIndent > currentIndent) parseMap(currentIndent) else null
            } else {
                itemMap[subKey] = parseScalar(subVal)
            }

            collectSiblingKeys(itemMap, currentIndent)
            return itemMap
        }

        private fun collectSiblingKeys(
            itemMap: MutableMap<String, Any?>,
            currentIndent: Int
        ) {
            while (index < lines.size) {
                val sibRaw = lines[index]
                val sibTrim = stripComment(sibRaw).trim()
                if (sibTrim.isBlank()) {
                    index++
                    continue
                }
                val sibIndent = countIndent(sibRaw)
                if (sibIndent <= currentIndent || sibTrim.startsWith("-")) {
                    break
                }
                val sibColon = findKeySeparator(sibTrim)
                if (sibColon != -1) {
                    val sKey = sibTrim.substring(0, sibColon).trim().trim('"', '\'')
                    val sVal = sibTrim.substring(sibColon + 1).trim()
                    index++
                    itemMap[sKey] = parseScalar(sVal)
                } else {
                    index++
                }
            }
        }

        private fun parseBlockScalar(
            parentIndent: Int,
            chomp: Boolean
        ): String {
            val sb = StringBuilder()
            var blockIndent: Int? = null

            while (index < lines.size) {
                val rawLine = lines[index]
                if (rawLine.isBlank()) {
                    sb.append("\n")
                    index++
                    continue
                }

                val currentIndent = countIndent(rawLine)
                if (blockIndent == null) {
                    if (currentIndent <= parentIndent) {
                        break
                    }
                    blockIndent = currentIndent
                } else if (currentIndent < blockIndent) {
                    break
                }

                val content = if (rawLine.length >= blockIndent) rawLine.substring(blockIndent) else rawLine.trimStart()
                sb.append(content).append("\n")
                index++
            }

            val rawResult = sb.toString()
            return if (chomp) rawResult.trimEnd() else rawResult.trimEnd('\n')
        }

        private fun peekNextIndent(): Int {
            var i = index
            while (i < lines.size) {
                val line = lines[i]
                if (stripComment(line).isNotBlank()) {
                    return countIndent(line)
                }
                i++
            }
            return -1
        }

        private fun peekNextTrimmed(): String {
            var i = index
            while (i < lines.size) {
                val line = lines[i]
                val stripped = stripComment(line)
                if (stripped.isNotBlank()) {
                    return stripped.trim()
                }
                i++
            }
            return ""
        }

        private fun countIndent(line: String): Int {
            var count = 0
            for (ch in line) {
                if (ch == ' ') count++ else break
            }
            return count
        }

        private fun stripComment(line: String): String {
            var inSingleQuote = false
            var inDoubleQuote = false
            for (i in line.indices) {
                val c = line[i]
                if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
                if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
                if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                    return line.substring(0, i).trimEnd()
                }
            }
            return line.trimEnd()
        }

        private fun findKeySeparator(line: String): Int {
            var inSingleQuote = false
            var inDoubleQuote = false
            for (i in line.indices) {
                val c = line[i]
                if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
                if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
                if (c == ':' &&
                    !inSingleQuote &&
                    !inDoubleQuote &&
                    (i == line.length - 1 || line[i + 1].isWhitespace())
                ) {
                    return i
                }
            }
            return -1
        }

        private fun parseScalar(value: String): Any? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed == "null" || trimmed == "~") return null
            if (trimmed.equals("true", ignoreCase = true)) return true
            if (trimmed.equals("false", ignoreCase = true)) return false

            trimmed.toLongOrNull()?.let { return it }
            trimmed.toDoubleOrNull()?.let { return it }

            val isQuoted =
                (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                    (trimmed.startsWith("'") && trimmed.endsWith("'"))
            if (isQuoted && trimmed.length >= 2) {
                val inner = trimmed.substring(1, trimmed.length - 1)
                return inner.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            }

            return trimmed
        }
    }
}
