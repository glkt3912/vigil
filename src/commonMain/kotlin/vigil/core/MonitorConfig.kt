package vigil.core

import vigil.retry.BackoffConfig

// NestJS の ConfigService.get() で取得する設定値に相当。
// ただし型安全な data class で定義するため、実行時エラーが起きない。
data class MonitorConfig(
    val watchPath: String,
    val wsUrl: String,
    val reconnect: BackoffConfig = BackoffConfig(),
    val bufferSize: Int = 256,
)
