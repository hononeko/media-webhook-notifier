package app.hononeko.notifier.domain.port.outbound

import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TrackerSnapshot
import kotlinx.coroutines.flow.StateFlow

interface ActiveTrackerStore {
    /**
     * Atomically registers a tracking session.
     * Returns true if registered, or false if a session for this hash already exists.
     */
    fun register(session: ActiveTrackerSession): Boolean

    fun get(hash: String): ActiveTrackerSession?

    fun updateProgress(
        hash: String,
        progress: TorrentProgress,
        stalledSeconds: Long
    )

    fun complete(hash: String): ActiveTrackerSession?

    fun cancel(hash: String): ActiveTrackerSession?

    fun getAllSnapshots(): List<TrackerSnapshot>

    fun activeCount(): Int

    fun isTracking(hash: String): Boolean

    fun stopAll()

    val snapshotsFlow: StateFlow<List<TrackerSnapshot>>
}
