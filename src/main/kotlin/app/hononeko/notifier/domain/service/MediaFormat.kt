package app.hononeko.notifier.domain.service

import java.util.Locale

/** Number and text formatting shared by the cards: progress bars, durations, sizes, speeds, peers, overviews. */
object MediaFormat {
    /** qBittorrent reports an unknown ETA as 8640000 s (100 days). */
    private const val UNKNOWN_ETA_SECONDS = 8_640_000L

    /** Units above bytes with their number format: one decimal for KB / MB, two for GB / TB. */
    private val SIZE_UNITS = listOf("KB" to "%.1f", "MB" to "%.1f", "GB" to "%.2f", "TB" to "%.2f")

    fun drawProgressBar(
        percent: Double,
        length: Int = 10,
        style: String = "default"
    ): String {
        val clamped = percent.coerceIn(0.0, 100.0)
        return when (style.lowercase(Locale.US)) {
            "minimal" -> {
                val filled = kotlin.math.round((clamped * length) / 100.0).toInt()
                val empty = length - filled
                "[${"=".repeat(filled)}${"-".repeat(empty)}]"
            }
            "circles" -> {
                val filled = kotlin.math.round((clamped * length) / 100.0).toInt()
                val empty = length - filled
                "[${"●".repeat(filled)}${"○".repeat(empty)}]"
            }
            else -> {
                val totalEighths = kotlin.math.round((clamped * length * 8) / 100.0).toInt()
                val fullBlocks = totalEighths / 8
                val remainder = totalEighths % 8
                val subBlocks = charArrayOf(' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉')
                val sb = StringBuilder()
                sb.append("[")
                sb.append("█".repeat(fullBlocks.coerceAtMost(length)))
                if (fullBlocks < length) {
                    if (remainder > 0) {
                        sb.append(subBlocks[remainder])
                        sb.append("░".repeat(length - fullBlocks - 1))
                    } else {
                        sb.append("░".repeat(length - fullBlocks))
                    }
                }
                sb.append("]")
                sb.toString()
            }
        }
    }

    fun drawProgressBar(
        percent: Int,
        length: Int = 10
    ): String = drawProgressBar(percent.toDouble(), length)

    fun formatDuration(seconds: Long): String =
        when {
            seconds < 0 || seconds >= UNKNOWN_ETA_SECONDS -> "∞"
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var size = bytes / 1024.0
        var unit = 0
        while (size >= 1024 && unit < SIZE_UNITS.lastIndex) {
            size /= 1024.0
            unit++
        }
        val (name, format) = SIZE_UNITS[unit]
        return String.format(Locale.US, "$format $name", size)
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    fun formatPeers(
        seedsCount: Int,
        seedsTotal: Int,
        peersCount: Int,
        peersTotal: Int
    ): String =
        when {
            seedsTotal > 0 || peersTotal > 0 -> "$seedsCount ($seedsTotal) seeds • $peersCount ($peersTotal) peers"
            else -> "$seedsCount seeds • $peersCount peers"
        }

    fun truncateOverview(
        overview: String?,
        maxLength: Int = 220
    ): String? {
        if (overview.isNullOrBlank()) return null
        val trimmed = overview.trim()
        if (trimmed.length <= maxLength) return trimmed

        val truncated = trimmed.take(maxLength)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > maxLength / 2) {
            "${truncated.take(lastSpace)}..."
        } else {
            "$truncated..."
        }
    }
}
