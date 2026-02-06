package vigil.retry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ExponentialBackoffTest {

    private val noJitterConfig = BackoffConfig(
        initialDelay = 1.seconds,
        maxDelay = 30.seconds,
        multiplier = 2.0,
        jitterFactor = 0.0,
    )

    // --- calculateDelay 直接テスト (jitter なし = 決定論的) ---

    @Test
    fun `attempt 0 returns initialDelay`() {
        val delay = calculateDelay(noJitterConfig, attempt = 0)
        assertEquals(1000.milliseconds, delay)
    }

    @Test
    fun `attempt 1 returns initialDelay times multiplier`() {
        val delay = calculateDelay(noJitterConfig, attempt = 1)
        assertEquals(2000.milliseconds, delay)
    }

    @Test
    fun `attempt 2 returns initialDelay times multiplier squared`() {
        val delay = calculateDelay(noJitterConfig, attempt = 2)
        assertEquals(4000.milliseconds, delay)
    }

    @Test
    fun `delay is capped at maxDelay`() {
        val delay = calculateDelay(noJitterConfig, attempt = 10)
        assertEquals(30.seconds, delay)
    }

    @Test
    fun `jitter stays within expected bounds`() {
        val config = BackoffConfig(
            initialDelay = 10.seconds,
            maxDelay = 30.seconds,
            multiplier = 1.0,
            jitterFactor = 0.1,
        )
        repeat(100) {
            val delay = calculateDelay(config, attempt = 0)
            val ms = delay.inWholeMilliseconds
            assertTrue(
                ms in 9000..11000,
                "Expected delay in [9000, 11000] ms but got $ms",
            )
        }
    }

    // --- retryWithBackoff 統合テスト ---

    @Test
    fun `retryWithBackoff retries on failure then succeeds`() = runTest {
        var attempts = 0
        val testFlow: Flow<Int> = flow {
            attempts++
            if (attempts < 3) throw RuntimeException("fail")
            emit(42)
        }

        val result = testFlow
            .retryWithBackoff(noJitterConfig.copy(maxRetries = 5))
            .toList()

        assertEquals(listOf(42), result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retryWithBackoff stops after maxRetries`() = runTest {
        var attempts = 0
        val testFlow: Flow<Int> = flow {
            attempts++
            throw RuntimeException("always fail")
        }

        var caughtException = false
        try {
            testFlow
                .retryWithBackoff(noJitterConfig.copy(maxRetries = 3))
                .toList()
        } catch (_: RuntimeException) {
            caughtException = true
        }

        assertTrue(caughtException)
        assertEquals(4, attempts) // initial + 3 retries
    }

    @Test
    fun `retryWithBackoff passes through successful flow unchanged`() = runTest {
        val testFlow: Flow<Int> = flow {
            emit(1)
            emit(2)
            emit(3)
        }

        val result = testFlow
            .retryWithBackoff(noJitterConfig)
            .toList()

        assertEquals(listOf(1, 2, 3), result)
    }
}
