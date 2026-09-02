package app.hononeko.notifier.adapter.outbound.telegram

import app.hononeko.notifier.domain.model.MediaSpecs
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.ProgressUpdate
import java.util.Locale

object TelegramHtmlFormatter {
    fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun appendItalicLine(
        sb: StringBuilder,
        text: String,
        prefix: String = ""
    ) {
        val escaped = escapeHtml(text)
        if (escaped.isNotBlank()) {
            sb
                .append(prefix)
                .append("<i>")
                .append(escaped)
                .append("</i>\n")
        }
    }

    fun formatSpecs(specs: MediaSpecs): String {
        val items = mutableListOf<String>()
        specs.resolution?.let { items.add(it) }
        specs.video?.let { items.add(it) }
        specs.audio?.let { items.add(it) }
        specs.score?.let { items.add("⭐ $it") }
        specs.duration?.let { items.add(it) }
        return items.joinToString(" • ")
    }

    fun buildCardHtml(card: NotificationCard): String {
        val sb = StringBuilder()
        sb.append("<b>").append(escapeHtml(card.title)).append("</b>\n")
        if (!card.subtitle.isNullOrBlank()) {
            appendItalicLine(sb, card.subtitle)
        }

        if (!card.customBody.isNullOrBlank()) {
            sb.append("\n").append(card.customBody).append("\n")
        } else {
            if (card.fields.isNotEmpty()) {
                sb.append("\n")
                for (field in card.fields) {
                    sb
                        .append("▪ <b>")
                        .append(escapeHtml(field.name))
                        .append(":</b> ")
                        .append(escapeHtml(field.value))
                        .append("\n")
                }
            }

            card.mediaSpecs?.let { specs ->
                val summary = formatSpecs(specs)
                if (summary.isNotBlank()) {
                    sb.append("▪ <b>Specs:</b> ").append(escapeHtml(summary)).append("\n")
                }
            }

            if (!card.overview.isNullOrBlank()) {
                appendItalicLine(sb, card.overview, prefix = "\n")
            }
        }

        return sb.toString().trimEnd()
    }

    fun buildProgressHtml(update: ProgressUpdate): String {
        val sb = StringBuilder()
        sb.append("<b>").append(escapeHtml(update.title)).append("</b>\n")
        if (!update.subtitle.isNullOrBlank()) {
            appendItalicLine(sb, update.subtitle)
        }

        if (!update.customBody.isNullOrBlank()) {
            sb.append("\n").append(update.customBody)
        } else {
            if (!update.episodeTracks.isNullOrBlank()) {
                sb.append("\n").append(update.episodeTracks).append("\n\n")
                sb
                    .append("▪ <b>Total Progress:</b> <code>")
                    .append(update.progressBar)
                    .append("</code> <b>")
                    .append(String.format(Locale.US, "%.2f", update.percent))
                    .append("%</b>\n")
            } else {
                sb
                    .append("\n<code>")
                    .append(update.progressBar)
                    .append("</code> <b>")
                    .append(String.format(Locale.US, "%.2f", update.percent))
                    .append("%</b>\n\n")
            }

            sb.append("▪ <b>Speed:</b> ").append(escapeHtml(update.speedFormatted))
            if (update.etaFormatted.isNotBlank()) {
                sb.append(" (ETA: ").append(escapeHtml(update.etaFormatted)).append(")")
            }
            sb.append("\n")
            sb.append("▪ <b>Transferred:</b> ").append(escapeHtml(update.sizeFormatted)).append("\n")
            sb.append("▪ <b>Peers:</b> ").append(escapeHtml(update.peersInfo)).append("\n")
            sb.append("▪ <b>Status:</b> ").append(escapeHtml(update.stateText))
        }

        return sb.toString()
    }

    fun truncateHtml(
        html: String,
        maxChars: Int,
        ellipsis: String = "..."
    ): String {
        if (html.length <= maxChars) return html
        if (maxChars <= ellipsis.length) return ellipsis.take(maxChars)
        return HtmlTruncator(html, maxChars, ellipsis).execute()
    }
}

private class HtmlTruncator(
    private val html: String,
    private val maxChars: Int,
    private val ellipsis: String
) {
    private val sb = StringBuilder()
    private val openTags = ArrayDeque<String>()
    private var index = 0

    private val closingTagsLen: Int
        get() = openTags.sumOf { it.length + 3 }

    fun execute(): String {
        while (index < html.length) {
            val token = nextToken()
            if (!canFit(token)) break
            applyToken(token)
            index += token.length
        }
        return finalizeResult()
    }

    private fun nextToken(): Token {
        if (html[index] == '<') {
            val match = TAG_REGEX.matchAt(html, index)
            if (match != null) {
                val isClosing = match.groups[1] != null
                val tagName = match.groups[2]!!.value.lowercase(Locale.ROOT)
                val raw = match.value
                return when {
                    isClosing -> Token.ClosingTag(raw, tagName)
                    raw.endsWith("/>") -> Token.SelfClosingTag(raw)
                    else -> Token.OpeningTag(raw, tagName)
                }
            }
        }

        if (html[index] == '&') {
            val match = ENTITY_REGEX.matchAt(html, index)
            if (match != null) {
                return Token.Entity(match.value)
            }
        }

        val charLen =
            if (Character.isHighSurrogate(html[index]) &&
                index + 1 < html.length &&
                Character.isLowSurrogate(html[index + 1])
            ) {
                2
            } else {
                1
            }
        return Token.Text(html.substring(index, index + charLen))
    }

    private fun canFit(token: Token): Boolean =
        when (token) {
            is Token.OpeningTag -> {
                val newClosingLen = closingTagsLen + token.name.length + 3
                sb.length + token.length + newClosingLen + ellipsis.length <= maxChars
            }
            is Token.ClosingTag -> true
            is Token.SelfClosingTag,
            is Token.Entity,
            is Token.Text -> {
                sb.length + token.length + closingTagsLen + ellipsis.length <= maxChars
            }
        }

    private fun applyToken(token: Token) {
        when (token) {
            is Token.OpeningTag -> {
                sb.append(token.raw)
                openTags.addLast(token.name)
            }
            is Token.ClosingTag -> {
                val tagIndex = openTags.lastIndexOf(token.name)
                if (tagIndex != -1) {
                    while (openTags.size > tagIndex) {
                        val popped = openTags.removeLast()
                        sb.append("</").append(popped).append(">")
                    }
                }
            }
            is Token.SelfClosingTag -> sb.append(token.raw)
            is Token.Entity -> sb.append(token.raw)
            is Token.Text -> sb.append(token.raw)
        }
    }

    private fun finalizeResult(): String {
        if (index < html.length) {
            while (sb.isNotEmpty() && sb.last().isWhitespace()) {
                sb.deleteCharAt(sb.length - 1)
            }
            sb.append(ellipsis)
            while (openTags.isNotEmpty()) {
                sb.append("</").append(openTags.removeLast()).append(">")
            }
        }
        return sb.toString()
    }

    private sealed interface Token {
        val length: Int

        data class OpeningTag(
            val raw: String,
            val name: String
        ) : Token {
            override val length: Int get() = raw.length
        }

        data class ClosingTag(
            val raw: String,
            val name: String
        ) : Token {
            override val length: Int get() = raw.length
        }

        data class SelfClosingTag(
            val raw: String
        ) : Token {
            override val length: Int get() = raw.length
        }

        data class Entity(
            val raw: String
        ) : Token {
            override val length: Int get() = raw.length
        }

        data class Text(
            val raw: String
        ) : Token {
            override val length: Int get() = raw.length
        }
    }

    companion object {
        private val TAG_REGEX = Regex("""<(/)?([a-zA-Z][a-zA-Z0-9_-]*)(?:\s+[^>]*)?>""")
        private val ENTITY_REGEX = Regex("""&([a-zA-Z0-9]+|#[0-9]+|#x[0-9a-fA-F]+);""")
    }
}
