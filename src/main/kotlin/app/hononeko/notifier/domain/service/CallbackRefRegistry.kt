package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.CallbackTarget
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Maps short random refs to [CallbackTarget]s (the card a button was sent on) so button payloads stay within
 * Telegram's 64-byte `callback_data` limit.
 *
 * Refs only need to be unique, not unguessable: the inbound adapter rejects non-allowlisted users before any lookup.
 */
class CallbackRefRegistry(
    private val stateStore: StateStorePort,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val REF_LENGTH = 10
        const val MAX_ATTEMPTS = 5
        const val KEY_PREFIX = "cb:"
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val REF_PATTERN = Regex("^[A-Za-z0-9]{$REF_LENGTH}$")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Stores [target] under a fresh ref for [ttlSeconds]. Returns `null` if no free ref was found or the store refused. */
    suspend fun register(
        target: CallbackTarget,
        ttlSeconds: Long
    ): String? {
        val payload = json.encodeToString(CallbackTarget.serializer(), target)
        repeat(MAX_ATTEMPTS) {
            val ref = newRef()
            if (stateStore.tryAcquire(KEY_PREFIX + ref, ttlSeconds, payload, nowMillis = clock())) {
                return ref
            }
        }
        return null
    }

    /** Returns the target behind [ref], or `null` if the ref is malformed, unknown, expired or holds unreadable data. */
    suspend fun resolve(ref: String): CallbackTarget? {
        if (!REF_PATTERN.matches(ref)) return null
        val raw = stateStore.get(KEY_PREFIX + ref, nowMillis = clock()) ?: return null
        return runCatching { json.decodeFromString(CallbackTarget.serializer(), raw) }.getOrNull()
    }

    /** Forgets [ref], so later presses of the same button resolve to nothing. Malformed refs are ignored. */
    suspend fun release(ref: String) {
        if (REF_PATTERN.matches(ref)) stateStore.delete(KEY_PREFIX + ref)
    }

    private fun newRef(): String =
        buildString(REF_LENGTH) {
            repeat(REF_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
}
