package vigil.core

import vigil.retry.BackoffConfig

// NestJS の ConfigService.get() で取得する設定値に相当。
// ただし型安全な data class で定義するため、実行時エラーが起きない。
data class MonitorConfig(
    val watchPath: String,
    val wsUrl: String,
    val reconnect: BackoffConfig = BackoffConfig(),
    val bufferSize: Int = 256,
    // フィードバックループ防止: プラグイン出力先を監視対象から除外する。
    // ContextManagerPlugin が .vigil/ に書き出す → WatchService が検知 → 再スナップショット
    // という無限ループを防ぐため、デフォルトで .vigil を除外。
    val excludePaths: List<String> = listOf(".vigil"),
)
