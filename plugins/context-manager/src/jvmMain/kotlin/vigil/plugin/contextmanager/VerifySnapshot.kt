package vigil.plugin.contextmanager

import vigil.core.MonitorConfig
import vigil.plugin.PluginContext

// ContextManagerPlugin の動作検証用エントリポイント。
// ./gradlew :plugins:context-manager:jvmRun で実行。
// VigilEngine を使わず直接プラグインを呼び、ワンショットで終了する。
fun main() {
    println("=== ContextManagerPlugin 検証 ===")
    println()

    val plugin = ContextManagerPlugin()
    val snapshot = plugin.buildSnapshot()

    // ファイル出力
    val file = java.io.File(".vigil/context_snapshot.md")
    file.parentFile.mkdirs()
    file.writeText(snapshot)

    println(snapshot)
    println("---")
    println("出力先: ${file.absolutePath}")
}
