package vigil

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import vigil.core.MonitorConfig
import vigil.engine.VigilEngine
import vigil.plugin.PluginContext
import kotlin.time.Duration.Companion.seconds

// NestJS の main.ts に相当するエントリポイント。
// VigilEngine にプラグインを登録し、ライフサイクルを開始する。
fun main() = runBlocking {
    println("Vigil - Log Monitor (JVM)")

    val config = MonitorConfig(
        watchPath = ".",
        wsUrl = "ws://localhost:8080/logs",
    )

    // デフォルトの PluginContext 実装（ファイル出力 + ログ表示）
    val context = object : PluginContext {
        override val config: MonitorConfig = config
        override suspend fun publish(message: String) {
            println("[vigil:publish] ${message.lines().first()}...")
        }
    }

    val engine = VigilEngine(heartbeatInterval = 30.seconds)

    // プラグイン登録はここに追加:
    // engine.register(ContextManagerPlugin())

    coroutineScope {
        engine.start(this, context)
        println("Vigil engine started. Press Ctrl+C to stop.")
    }
}
