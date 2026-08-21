package app.hononeko.notifier.adapter.outbound.tracker

import app.hononeko.notifier.domain.model.ActiveTrackerSession
import app.hononeko.notifier.domain.model.TorrentProgress
import app.hononeko.notifier.domain.model.TrackerSnapshot
import app.hononeko.notifier.domain.model.TrackerStatus
import app.hononeko.notifier.domain.port.outbound.ActiveTrackerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class InMemoryActiveTrackerStore : ActiveTrackerStore {
    private val sessions = ConcurrentHashMap<String, ActiveTrackerSession>()
    private val _snapshotsFlow = MutableStateFlow<List<TrackerSnapshot>>(emptyList())
    override val snapshotsFlow: StateFlow<List<TrackerSnapshot>> = _snapshotsFlow.asStateFlow()

    override fun register(session: ActiveTrackerSession): Boolean {
        val normalizedHash = session.hash.trim().lowercase()
        val existing = sessions.putIfAbsent(normalizedHash, session)
        if (existing == null) {
            updateFlow()
            return true
        }
        return false
    }

    override fun get(hash: String): ActiveTrackerSession? = sessions[hash.trim().lowercase()]

    override fun updateProgress(
        hash: String,
        progress: TorrentProgress,
        stalledSeconds: Long
    ) {
        sessions[hash.trim().lowercase()]?.let { session ->
            session.lastProgress = progress
            session.stalledDurationSeconds = stalledSeconds
            session.status = if (stalledSeconds > 0) TrackerStatus.STALLED else TrackerStatus.TRACKING
            updateFlow()
        }
    }

    override fun complete(hash: String): ActiveTrackerSession? {
        val session = sessions.remove(hash.trim().lowercase())
        session?.status = TrackerStatus.COMPLETING
        updateFlow()
        return session
    }

    override fun cancel(hash: String): ActiveTrackerSession? {
        val session = sessions.remove(hash.trim().lowercase())
        session?.status = TrackerStatus.CANCELLED
        session?.job?.cancel()
        updateFlow()
        return session
    }

    override fun getAllSnapshots(): List<TrackerSnapshot> = sessions.values.map { it.toSnapshot() }

    override fun activeCount(): Int = sessions.size

    override fun isTracking(hash: String): Boolean = sessions.containsKey(hash.trim().lowercase())

    override fun stopAll() {
        sessions.values.forEach { session ->
            session.status = TrackerStatus.CANCELLED
            session.job.cancel()
        }
        sessions.clear()
        updateFlow()
    }

    private fun updateFlow() {
        _snapshotsFlow.value = sessions.values.map { it.toSnapshot() }
    }
}
