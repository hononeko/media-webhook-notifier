package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.CardInteraction
import app.hononeko.notifier.domain.model.NotificationCard

/** Shared card state: the template engine every builder renders through, and the Acknowledge button. */
object CardTemplates {
    var templateEngine: TemplateEngine = TemplateEngine()

    /** Added to cards that need a human (health issues, blocked imports, stalls, failed requests). */
    val ACKNOWLEDGE = CardInteraction("✅ Acknowledge", AcknowledgeHandler.OPERATION)
}

/** Adds Acknowledge to cards that need a human (health issues, blocked imports, stalls, failed requests). */
internal fun NotificationCard.withAcknowledgeIf(condition: Boolean): NotificationCard =
    if (condition) copy(interactions = interactions + CardTemplates.ACKNOWLEDGE) else this
