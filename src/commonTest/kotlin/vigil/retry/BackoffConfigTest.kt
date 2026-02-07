package vigil.retry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class BackoffConfigTest {

    @Test
    fun `default values match specification`() {
        val config = BackoffConfig()
        assertEquals(1.seconds, config.initialDelay)
        assertEquals(30.seconds, config.maxDelay)
        assertEquals(2.0, config.multiplier)
        assertEquals(0.1, config.jitterFactor)
        assertEquals(Int.MAX_VALUE, config.maxRetries)
    }

    @Test
    fun `custom values are preserved`() {
        val config = BackoffConfig(
            initialDelay = 2.seconds,
            maxDelay = 60.seconds,
            multiplier = 3.0,
            jitterFactor = 0.2,
            maxRetries = 10,
        )
        assertEquals(2.seconds, config.initialDelay)
        assertEquals(60.seconds, config.maxDelay)
        assertEquals(3.0, config.multiplier)
        assertEquals(0.2, config.jitterFactor)
        assertEquals(10, config.maxRetries)
    }

    @Test
    fun `data class equality works`() {
        assertEquals(BackoffConfig(), BackoffConfig())
    }
}
