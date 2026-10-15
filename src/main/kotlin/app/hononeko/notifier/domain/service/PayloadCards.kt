package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.model.NotificationCard
import app.hononeko.notifier.domain.port.outbound.MediaServerPort
import app.hononeko.notifier.domain.service.CardTemplates.templateEngine
import app.hononeko.notifier.domain.service.DownloadCards.buildGrabInitialCard
import app.hononeko.notifier.domain.service.MediaServerCards.buildJellyfinCard
import app.hononeko.notifier.domain.service.MediaServerCards.buildPlexCard
import app.hononeko.notifier.domain.service.SeerrCards.buildSeerrCard
import app.hononeko.notifier.domain.service.ServarrCards.buildHealthCard
import app.hononeko.notifier.domain.service.ServarrCards.buildImportCard
import app.hononeko.notifier.domain.service.ServarrCards.buildManualInteractionCard

/**
 * The card for any payload, dispatched to the builder for its type. Used for available media and template previews.
 */
object PayloadCards {
    fun buildAvailableCard(
        payload: MediaPayload,
        mediaServerPort: MediaServerPort? = null,
        engine: TemplateEngine = templateEngine
    ): NotificationCard =
        when (payload) {
            is MediaPayload.PlexLibraryNew -> buildPlexCard(payload, mediaServerPort, engine)
            is MediaPayload.JellyfinItemAdded -> buildJellyfinCard(payload, mediaServerPort, engine)
            is MediaPayload.ArrDownload -> buildImportCard(payload, engine)
            is MediaPayload.ArrGrab -> buildGrabInitialCard(payload, null, engine)
            is MediaPayload.ServarrHealth -> buildHealthCard(payload, engine)
            is MediaPayload.ServarrManualInteraction -> buildManualInteractionCard(payload, engine)
            is MediaPayload.SeerrEvent -> buildSeerrCard(payload, engine)
        }
}
