package vigil.plugin

import vigil.core.MonitorConfig

// プラグインがアクセスできる実行コンテキスト。
// 設定参照と、既存 WebSocket パイプライン経由のメッセージ配信を提供する。
interface PluginContext {
    val config: MonitorConfig
    suspend fun publish(message: String)
}
