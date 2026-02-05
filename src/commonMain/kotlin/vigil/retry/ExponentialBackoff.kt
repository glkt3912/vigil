package vigil.retry

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

// Flow の拡張関数。RxJS の retryWhen + exponentialBackoff に相当。
// 詳細は docs/04-exponential-backoff.md 参照。
fun <T> Flow<T>.retryWithBackoff(config: BackoffConfig): Flow<T> =
    retryWhen { cause, attempt ->
        if (attempt >= config.maxRetries) {
            false // リトライ上限に達したら終了
        } else {
            val delayDuration = calculateDelay(config, attempt)
            delay(delayDuration)
            true // リトライ続行
        }
    }

// 指数バックオフ + ジッターの遅延時間を計算
private fun calculateDelay(config: BackoffConfig, attempt: Long): Duration {
    // base * multiplier^attempt
    val exponentialMs = config.initialDelay.inWholeMilliseconds *
        config.multiplier.pow(attempt.toInt())

    // 上限を適用
    val cappedMs = minOf(exponentialMs.toLong(), config.maxDelay.inWholeMilliseconds)

    // ジッター: ±jitterFactor の範囲でランダムに揺らす
    val jitterRange = (cappedMs * config.jitterFactor).toLong()
    val jitteredMs = if (jitterRange > 0) {
        cappedMs + Random.nextLong(-jitterRange, jitterRange + 1)
    } else {
        cappedMs
    }

    return Duration.parse("${jitteredMs}ms")
}
