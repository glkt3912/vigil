package vigil.plugin.contextmanager

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vigil.core.MonitorConfig
import vigil.engine.DefaultPluginContext
import vigil.engine.VigilEngine
import kotlin.time.Duration.Companion.seconds

// ContextManagerPlugin の動作検証用エントリポイント。
// ./gradlew :plugins:context-manager:jvmRun で実行。
fun main() = runBlocking {
    println("=== ContextManagerPlugin 検証 ===")
    println()

    val config = MonitorConfig(watchPath = ".", wsUrl = "ws://localhost:8080/logs")
    val context = DefaultPluginContext(config)
    val engine = VigilEngine(heartbeatInterval = 30.seconds)

    engine.register(ContextManagerPlugin())

    // publish された LogEvent をコンソールに出力
    launch {
        context.events.collect { event ->
            println("[${event.level}] ${event.source}: ${event.message}")
            val snapshot = event.metadata["snapshot"]
            if (snapshot != null) {
                println()
                println(snapshot)
            }
        }
    }

    // onInitialize のみ実行して終了（heartbeat は待たない）
    engine.start(this, context)
    println("--- .vigil/context_snapshot.md の内容 ---")
    println()
    val file = java.io.File(".vigil/context_snapshot.md")
    if (file.exists()) {
        println(file.readText())
    } else {
        println("(ファイルが生成されませんでした)")
    }

    engine.stop()
}
