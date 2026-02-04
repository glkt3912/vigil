package vigil.retry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// NestJS の RetryConfig 的な設定。詳細は docs/03-expect-actual.md 参照。
data class BackoffConfig(
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.1,
    val maxRetries: Int = Int.MAX_VALUE,
)
