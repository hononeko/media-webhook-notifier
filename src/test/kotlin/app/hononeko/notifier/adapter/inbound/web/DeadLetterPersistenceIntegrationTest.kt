package app.hononeko.notifier.adapter.inbound.web

import app.hononeko.notifier.adapter.outbound.state.ValkeyStateStore
import app.hononeko.notifier.config.StateConfig
import app.hononeko.notifier.domain.model.AppSource
import app.hononeko.notifier.domain.model.MediaPayload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

@Tag("integration")
class DeadLetterPersistenceIntegrationTest {
    companion object {
        private class KGenericContainer(
            image: String
        ) : GenericContainer<KGenericContainer>(DockerImageName.parse(image))

        private val valkeyContainer =
            KGenericContainer("valkey/valkey:8-alpine")
                .withExposedPorts(6379)

        private lateinit var connectionUrl: String

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            valkeyContainer.start()
            connectionUrl = "valkey://${valkeyContainer.host}:${valkeyContainer.getMappedPort(6379)}"
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            valkeyContainer.stop()
        }
    }

    @Test
    fun `should survive a service restart when backed by Valkey`() =
        runTest {
            val config = StateConfig(url = connectionUrl, keyPrefix = "mwn:dlq-it:")
            val payload =
                MediaPayload.ServarrHealth(
                    source = AppSource.RADARR,
                    level = "error",
                    message = "Indexers unavailable",
                    type = "IndexerStatusCheck"
                )

            val firstStore = ValkeyStateStore(config)
            val firstBuffer = DeadLetterRingBuffer()
            val recorded = firstBuffer.record(payload, "Telegram unreachable", stackTrace = "java.io.IOException")
            try {
                DeadLetterPersistence(firstBuffer, firstStore).flush()
            } finally {
                firstStore.close()
            }

            val secondStore = ValkeyStateStore(config)
            try {
                val restartedBuffer = DeadLetterRingBuffer()
                DeadLetterPersistence(restartedBuffer, secondStore).restore()

                assertEquals(listOf(recorded), restartedBuffer.getEntries())
            } finally {
                secondStore.clear()
                secondStore.close()
            }
        }
}
