package vigil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import vigil.retry.BackoffConfig

class MonitorConfigTest {

    @Test
    fun `default values are applied`() {
        val config = MonitorConfig(
            watchPath = "/var/log/app.log",
            wsUrl = "ws://localhost:8080/logs",
        )
        assertEquals(BackoffConfig(), config.reconnect)
        assertEquals(256, config.bufferSize)
    }

    @Test
    fun `custom values override defaults`() {
        val customBackoff = BackoffConfig(maxRetries = 5)
        val config = MonitorConfig(
            watchPath = "/tmp/test.log",
            wsUrl = "ws://example.com/ws",
            reconnect = customBackoff,
            bufferSize = 1024,
        )
        assertEquals(5, config.reconnect.maxRetries)
        assertEquals(1024, config.bufferSize)
    }
}
