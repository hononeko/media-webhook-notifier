package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationHandle
import app.hononeko.notifier.domain.model.NotificationLevel
import app.hononeko.notifier.domain.port.outbound.NotificationPublisherPort
import app.hononeko.notifier.domain.port.outbound.resolveDestination
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The pinned media server status card: 🟢 online or 🔴 offline, edited in place by [MediaServerStatusMonitor]. */
object MediaServerStatusCards {
    const val ONLINE_EVENT = "media_server.status_online"
    const val OFFLINE_EVENT = "media_server.status_offline"

    private const val ONLINE_BODY =
        "▪ <b>Back up at:</b> {since} (downtime: {downtime})\n" +
            "▪ <b>Last checked:</b> {checked_at}"
    private const val OFFLINE_BODY =
        "▪ <b>Down since:</b> {since}\n" +
            "▪ <b>Downtime:</b> {downtime}\n" +
            "▪ <b>Error:</b> {error}\n" +
            "▪ <b>Last checked:</b> {checked_at}"
    private const val TIME_FORMAT = "HH:mm"

    /** What the card shows; times are epoch millis. */
    data class StatusView(
        val serverName: String,
        val sourceName: String,
        val online: Boolean,
        /** When the current status began: the recovery when online, the first failed check when offline. */
        val since: Long?,
        /** The outage so far when offline; the last outage's length when online (null before any outage). */
        val downtimeMillis: Long?,
        val checkedAt: Long,
        val error: String? = null,
        /** True on a `DOWN -> UP` recovery transition; makes recovery alerts audible by default unless `silent: true`. */
        val recovery: Boolean = false
    )

    fun build(
        view: StatusView,
        zone: ZoneId = ZoneId.systemDefault(),
        engine: TemplateEngine = templateEngine
    ): NotificationCard {
        val eventName = if (view.online) ONLINE_EVENT else OFFLINE_EVENT
        val status = if (view.online) "Online" else "Offline"
        val icon = if (view.online) "🟢" else "🔴"
        val context =
            mapOf(
                "media_server_name" to view.serverName,
                "instance_name" to view.serverName,
                "source_name" to view.sourceName,
                "status" to status,
                "status_icon" to icon,
                // An online card's "since" is only worth showing after an outage.
                "since" to
                    view.since?.takeIf { !view.online || view.downtimeMillis != null }?.let {
                        formatTime(it, view.checkedAt, zone, engine.theme.dateFormat)
                    },
                "downtime" to view.downtimeMillis?.let { MediaFormat.formatDuration(it / 1000) },
                "checked_at" to formatTime(view.checkedAt, view.checkedAt, zone, engine.theme.dateFormat),
                "error" to view.error?.takeUnless { view.online }
            )
        val resolved =
            engine.resolveCard(
                eventName = eventName,
                defaults = DefaultCardSpec(title = "$icon ${view.serverName} — $status", silent = !view.recovery),
                context = context
            )
        // The body is sent as HTML, so its values are escaped: an error message may well contain `<` or `&`. The title
        // is escaped when it's sent.
        val bodyTemplate =
            engine.getEventTemplate(eventName)?.body?.takeIf { it.isNotBlank() }
                ?: if (view.online) ONLINE_BODY else OFFLINE_BODY
        val body = engine.interpolateBody(bodyTemplate, context.mapValues { (_, value) -> value?.let(::escapeHtml) })
        val level = if (view.online) NotificationLevel.SUCCESS else NotificationLevel.ERROR
        return resolved.copy(customBody = body).toCard(level, eventName, artworkUrl = null)
    }

    /** Returns [handle] if it still targets the resolved chat and topic for the current status, else `null`. */
    internal fun matchingHandle(
        handle: NotificationHandle?,
        online: Boolean,
        publisher: NotificationPublisherPort,
        engine: TemplateEngine
    ): NotificationHandle? {
        if (handle == null) return null
        val eventName = if (online) ONLINE_EVENT else OFFLINE_EVENT
        val template = engine.getEventTemplate(eventName)
        val target = publisher.resolveDestination(template?.chatId, template?.topicId)
        return handle.takeIf {
            target.chatId.isBlank() || (it.channelOrChatId == target.chatId && it.topicId == target.topicId)
        }
    }

    private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** `HH:mm` on the same day as [now], the theme's date format otherwise. */
    internal fun formatTime(
        epochMillis: Long,
        now: Long,
        zone: ZoneId,
        dateFormat: String
    ): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val sameDay = time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = if (sameDay) TIME_FORMAT else dateFormat
        val formatter =
            runCatching { DateTimeFormatter.ofPattern(pattern, Locale.US) }
                .getOrElse { DateTimeFormatter.ofPattern(TIME_FORMAT, Locale.US) }
        return formatter.format(time)
    }
}
