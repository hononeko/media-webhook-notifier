package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.model.DownloadItemProgress
import app.hononeko.notifier.domain.model.DownloadState
import app.hononeko.notifier.domain.model.SwarmStats
import app.hononeko.notifier.domain.model.UsenetStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadProgressAggregatorTest {
    private fun torrent(
        name: String,
        ratio: Double,
        totalSize: Long,
        state: DownloadState = DownloadState.DOWNLOADING,
        tags: List<String> = emptyList()
    ) = DownloadItemProgress(
        id = name.lowercase(),
        name = name,
        progressPercent = ratio * 100.0,
        progressRatio = ratio,
        downloadSpeedBytesPerSec = 100,
        uploadSpeedBytesPerSec = 10,
        etaSeconds = (totalSize * (1 - ratio)).toLong(),
        totalSizeBytes = totalSize,
        downloadedBytes = (totalSize * ratio).toLong(),
        swarm = SwarmStats(seedsCount = name.length),
        state = state,
        tags = tags
    )

    @Test
    fun `should combine sizes, speeds and tags and sort child items by episode`() {
        val aggregate =
            DownloadProgressAggregator.aggregate(
                id = "e3|e1",
                items =
                    listOf(
                        torrent("Show.S01E03", ratio = 0.5, totalSize = 1000, tags = listOf("mwn_msg:1")),
                        torrent("Show.S01E01", ratio = 1.0, totalSize = 3000, tags = listOf("mwn_msg:1", "tv"))
                    )
            )

        assertEquals("e3|e1", aggregate.id)
        assertEquals("Show.S01E01", aggregate.name)
        assertEquals(4000L, aggregate.totalSizeBytes)
        assertEquals(3500L, aggregate.downloadedBytes)
        assertEquals(87.5, aggregate.progressPercent, 0.001)
        assertEquals(200L, aggregate.downloadSpeedBytesPerSec)
        assertEquals(20L, aggregate.uploadSpeedBytesPerSec)
        assertEquals(500L, aggregate.etaSeconds)
        assertEquals(listOf("Show.S01E01", "Show.S01E03"), aggregate.items.map { it.name })
        assertEquals(listOf("mwn_msg:1", "tv"), aggregate.tags)
    }

    @Test
    fun `should fall back to average ratio when sizes are unknown`() {
        val aggregate =
            DownloadProgressAggregator.aggregate(
                id = "a|b",
                items = listOf(torrent("A", ratio = 0.2, totalSize = 0), torrent("B", ratio = 0.6, totalSize = 0))
            )

        assertEquals(40.0, aggregate.progressPercent, 0.001)
    }

    @Test
    fun `should only aggregate the protocol stats the items report`() {
        val usenetItems =
            listOf("E2" to "Repairing", "E1" to null).map { (episode, stage) ->
                torrent("Show.S01$episode", ratio = 0.5, totalSize = 10).copy(
                    swarm = null,
                    usenet = UsenetStats(stage = stage, missingBlocks = 3)
                )
            }

        val usenet = DownloadProgressAggregator.aggregate(id = "a|b", items = usenetItems)
        assertNull(usenet.swarm)
        assertEquals(UsenetStats(stage = "Repairing", missingBlocks = 6), usenet.usenet)

        val swarm =
            DownloadProgressAggregator.aggregate(
                id = "a|b",
                items = listOf(torrent("A", ratio = 0.2, totalSize = 10), torrent("BBB", ratio = 0.6, totalSize = 10))
            )
        assertEquals(3, swarm.swarm?.seedsCount)
        assertNull(swarm.usenet)
    }

    @Test
    fun `should resolve aggregate state from child states`() {
        fun stateOf(vararg states: DownloadState): DownloadState =
            DownloadProgressAggregator
                .aggregate(
                    id = "multi",
                    items = states.mapIndexed { i, s -> torrent("T$i", ratio = 0.5, totalSize = 10, state = s) }
                ).state

        assertEquals(DownloadState.COMPLETED, stateOf(DownloadState.COMPLETED, DownloadState.COMPLETED))
        assertEquals(DownloadState.DOWNLOADING, stateOf(DownloadState.COMPLETED, DownloadState.DOWNLOADING))
        assertEquals(
            DownloadState.ALLOCATING_METADATA,
            stateOf(DownloadState.ALLOCATING_METADATA, DownloadState.QUEUED)
        )
        assertEquals(DownloadState.CHECKING, stateOf(DownloadState.CHECKING, DownloadState.PAUSED))
        assertEquals(DownloadState.STALLED, stateOf(DownloadState.STALLED, DownloadState.STALLED))
        assertEquals(DownloadState.PAUSED, stateOf(DownloadState.PAUSED, DownloadState.PAUSED))
        assertEquals(DownloadState.QUEUED, stateOf(DownloadState.QUEUED, DownloadState.QUEUED))
        assertEquals(DownloadState.DOWNLOADING, stateOf(DownloadState.STALLED, DownloadState.PAUSED))
    }
}
