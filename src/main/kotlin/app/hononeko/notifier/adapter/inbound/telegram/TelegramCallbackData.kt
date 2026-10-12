package app.hononeko.notifier.adapter.inbound.telegram

/**
 * Wire format of the notifier's inline-button `callback_data`: `mwn1:<operation>:<ref>`.
 * Telegram caps `callback_data` at 64 bytes, so the download itself is referenced through a short state-store ref.
 */
internal object TelegramCallbackData {
    const val PREFIX = "mwn1"
    const val MAX_BYTES = 64

    private val OPERATION = Regex("^[a-z][a-z0-9_-]*$")
    private val REF = Regex("^[A-Za-z0-9]+$")

    data class Decoded(
        val operation: String,
        val ref: String
    )

    /** Returns the payload for a button, or `null` if [operation] or [ref] is invalid or the result exceeds 64 bytes. */
    fun encode(
        operation: String,
        ref: String
    ): String? {
        if (!OPERATION.matches(operation) || !REF.matches(ref)) return null
        val payload = "$PREFIX:$operation:$ref"
        return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    /** Returns `null` for anything that isn't a well-formed `mwn1` payload, including buttons from other bots. */
    fun decode(raw: String?): Decoded? {
        val parts = raw?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val (_, operation, ref) = parts
        return if (OPERATION.matches(operation) && REF.matches(ref)) Decoded(operation, ref) else null
    }
}
