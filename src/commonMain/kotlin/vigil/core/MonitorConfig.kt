package vigil.core

import vigil.retry.BackoffConfig

// NestJS の ConfigService.get() で取得する設定値に相当。
// ただし型安全な data class で定義するため、実行時エラーが起きない。
data class MonitorConfig(
    val watchPath: String,
    val wsUrl: String,
    val reconnect: BackoffConfig = BackoffConfig(),
    val bufferSize: Int = 256,
    // リングバッファのキャパシティ。直近 N 件の LogEvent を保持し、
    // WebSocket 接続時に履歴としてバースト送信する。
    val historySize: Int = 1000,
    // フィードバックループ防止: プラグイン出力先を監視対象から除外する。
    // ContextManagerPlugin が .vigil/ に書き出す → WatchService が検知 → 再スナップショット
    // という無限ループを防ぐため、デフォルトで .vigil を除外。
    val excludePaths: List<String> = listOf(".vigil"),
) {
    init {
        require(historySize > 0) { "historySize must be positive, was $historySize" }
    }
}
