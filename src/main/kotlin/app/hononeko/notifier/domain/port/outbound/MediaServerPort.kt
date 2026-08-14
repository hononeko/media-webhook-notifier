package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.model.MediaPayload

fun interface MediaServerPort {
    fun resolveDeepLink(payload: MediaPayload): String?
}
