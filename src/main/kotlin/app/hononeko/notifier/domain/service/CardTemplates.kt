package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.model.NotificationLevel

/** Shared card state: the template engine every builder renders through, and the Acknowledge button. */
object CardTemplates {
    var templateEngine: TemplateEngine = TemplateEngine()

    /** Added to cards that need a human (health issues, blocked imports, stalls, failed requests). */
    val ACKNOWLEDGE = CardInteraction("✅ Acknowledge", AcknowledgeHandler.OPERATION)
}

/** Adds Acknowledge to cards that need a human (health issues, blocked imports, stalls, failed requests). */
internal fun NotificationCard.withAcknowledgeIf(condition: Boolean): NotificationCard =
    if (condition) copy(interactions = interactions + CardTemplates.ACKNOWLEDGE) else this

/**
 * The card for a resolved template. With the user's custom template body, only the common parts are set; otherwise
 * [defaultBody] adds the built-in body (fields, overview, media specs). Every card builder ends this way.
 */
internal inline fun ResolvedTemplateCard.toCard(
    level: NotificationLevel,
    eventType: String,
    artworkUrl: String? = this.artworkUrl,
    artworkBytes: ByteArray? = null,
    defaultBody: NotificationCard.() -> NotificationCard = { this }
): NotificationCard {
    val card =
        NotificationCard(
            title = title,
            subtitle = subtitle,
            level = level,
            customBody = customBody,
            artworkUrl = artworkUrl,
            artworkBytes = artworkBytes,
            actions = actions,
            eventType = eventType,
            silent = silent
        )
    return if (customBody != null) card else card.defaultBody()
}
