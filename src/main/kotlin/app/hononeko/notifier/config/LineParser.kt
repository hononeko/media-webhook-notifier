package app.hononeko.notifier.config

/** Parses indented YAML lines into nested maps, lists and scalars, one block at a time. */
internal class LineParser(
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
            } else {
                val currentIndent = countIndent(rawLine)
                if (currentIndent <= parentIndent && parentIndent != -1) {
                    break
                }

                val colonIdx = findKeySeparator(trimmed)
                if (colonIdx == -1) {
                    index++
                } else {
                    val key = trimmed.substring(0, colonIdx).trim().trim('"', '\'')
                    val valuePart = trimmed.substring(colonIdx + 1).trim()
                    index++

                    result[key] = parseMapValue(currentIndent, valuePart)
                }
            }
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
            } else {
                val currentIndent = countIndent(rawLine)
                val isExiting = (currentIndent <= parentIndent && parentIndent != -1) || !trimmed.startsWith("-")
                if (isExiting) {
                    break
                }

                val itemContent = trimmed.substring(1).trim()
                result.add(parseListItem(itemContent, currentIndent))
            }
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
            } else {
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
    }

    private fun shouldHaltBlockScalar(
        currentIndent: Int,
        parentIndent: Int,
        blockIndent: Int?
    ): Boolean =
        if (blockIndent == null) {
            currentIndent <= parentIndent
        } else {
            currentIndent < blockIndent
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
            } else {
                val currentIndent = countIndent(rawLine)
                if (shouldHaltBlockScalar(currentIndent, parentIndent, blockIndent)) {
                    break
                }
                if (blockIndent == null) {
                    blockIndent = currentIndent
                }

                val content =
                    if (rawLine.length >= blockIndent) {
                        rawLine.substring(blockIndent)
                    } else {
                        rawLine.trimStart()
                    }
                sb.append(content).append("\n")
                index++
            }
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
}

// Line-level text helpers: quote-aware, but independent of the parser's position.

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
        val isQuoted = inSingleQuote || inDoubleQuote
        val isKeyTerminator = i == line.length - 1 || line[i + 1].isWhitespace()
        if (c == ':' && !isQuoted && isKeyTerminator) {
            return i
        }
    }
    return -1
}

private fun parseScalar(value: String): Any? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() || trimmed == "null" || trimmed == "~" -> null
        trimmed.equals("true", ignoreCase = true) -> true
        trimmed.equals("false", ignoreCase = true) -> false
        else -> trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull() ?: unquote(trimmed)
    }
}

/** Strips matching single or double quotes and unescapes `\n`, `\"` and `\\` in a quoted scalar. */
private fun unquote(trimmed: String): String {
    val isQuoted =
        trimmed.length >= 2 &&
            (
                (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                    (trimmed.startsWith("'") && trimmed.endsWith("'"))
            )
    if (!isQuoted) return trimmed
    return trimmed
        .substring(1, trimmed.length - 1)
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
