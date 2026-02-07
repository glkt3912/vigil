package vigil

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import vigil.core.MonitorConfig
import vigil.engine.DefaultPluginContext
import vigil.engine.VigilEngine
import kotlin.time.Duration.Companion.seconds

// NestJS の main.ts に相当するエントリポイント。
// VigilEngine にプラグインを登録し、ライフサイクルを開始する。
//
// プラグインが publish() したメッセージは DefaultPluginContext 内で
// LogEvent (metadata["type"]="context") に変換され、events Flow に流れる。
// この Flow を WebSocketPipeline.connect(context.events) に渡せば
// 既存パイプライン経由で WebSocket 送信される。
fun main() = runBlocking {
    println("Vigil - Log Monitor (JVM)")

    val config = MonitorConfig(
        watchPath = ".",
        wsUrl = "ws://localhost:8080/logs",
    )

    val context = DefaultPluginContext(config)
    val engine = VigilEngine(heartbeatInterval = 30.seconds)

    // プラグイン登録はここに追加:
    // engine.register(ContextManagerPlugin())

    // パイプライン接続例:
    // val pipeline = WebSocketPipeline(config, httpClient)
    // launch { pipeline.connect(context.events) }

    coroutineScope {
        engine.start(this, context)
        println("Vigil engine started. Press Ctrl+C to stop.")
    }
}
