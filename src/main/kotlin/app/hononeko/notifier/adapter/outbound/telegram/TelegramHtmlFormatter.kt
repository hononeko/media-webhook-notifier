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
}
