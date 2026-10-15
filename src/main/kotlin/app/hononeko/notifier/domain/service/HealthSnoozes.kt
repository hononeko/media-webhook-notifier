package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.MediaPayload
import app.hononeko.notifier.domain.port.outbound.StateStorePort
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Snoozed Sonarr / Radarr health checks. While a check is snoozed, its issue and "restored" cards are dropped, so a
 * flapping check stays quiet. Entries live in the state store, so they are shared across replicas and, with Valkey,
 * survive restarts.
 */
class HealthSnoozes(
    private val stateStore: StateStorePort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val KEY_PREFIX = "snooze:"
        const val DURATION_SECONDS = 86_400L

        /** One check: source + instance + Servarr check type, or the message when the type is missing. */
        fun checkOf(payload: MediaPayload.ServarrHealth): String =
            listOf(
                payload.source.name,
                payload.instanceName.orEmpty(),
                payload.type?.ifBlank { null } ?: payload.message
            ).joinToString("|") { it.trim() }
    }

    @Serializable
    private data class Entry(
        val by: String,
        val untilEpochMillis: Long
    )

    /** Snoozes [check] for [DURATION_SECONDS] on behalf of [by]; returns when the snooze ends (epoch millis). */
    suspend fun snooze(
        check: String,
        by: String
    ): Long {
        val now = clock()
        val until = now + DURATION_SECONDS * 1000
        stateStore.set(
            KEY_PREFIX + check,
            Json.encodeToString(Entry.serializer(), Entry(by, until)),
            DURATION_SECONDS,
            now
        )
        return until
    }

    suspend fun isSnoozed(check: String): Boolean = stateStore.exists(KEY_PREFIX + check, clock())

    /** Ends the snooze on [check]; returns false when it had already ended. */
    suspend fun unsnooze(check: String): Boolean {
        // Checked first: the in-memory store reports an expired entry as deleted.
        val active = isSnoozed(check)
        stateStore.delete(KEY_PREFIX + check)
        return active
    }
}
