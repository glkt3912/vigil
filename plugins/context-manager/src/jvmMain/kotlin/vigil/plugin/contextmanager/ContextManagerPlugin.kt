package vigil.plugin.contextmanager

import vigil.plugin.PluginContext
import vigil.plugin.VigilPlugin
import java.io.File

// Git の差分・ステータスを定期的に収集し、
// Markdown 形式のコンテキストスナップショットを生成するプラグイン。
// 出力先: ファイル (.vigil/context_snapshot.md) + WebSocket パイプライン。
class ContextManagerPlugin(
    private val repoPath: String = ".",
    private val outputPath: String = ".vigil/context_snapshot.md",
) : VigilPlugin {
    override val name = "context-manager"

    private val git by lazy { GitCommandExecutor(File(repoPath)) }

    override suspend fun onInitialize(context: PluginContext) {
        val snapshot = buildSnapshot()
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onHeartbeat(context: PluginContext) {
        val snapshot = buildSnapshot()
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onShutdown() {
        // クリーンアップ不要
    }

    internal fun buildSnapshot(): String = buildString {
        appendLine("# Development Context Snapshot")
        appendLine()
        appendLine("## Branch")
        appendLine("```")
        appendLine(git.branchName())
        appendLine("```")
        appendLine()
        appendLine("## Status")
        appendLine("```")
        val status = git.statusShort()
        appendLine(status.ifBlank { "(clean)" })
        appendLine("```")
        appendLine()
        appendLine("## Diff (stat)")
        appendLine("```")
        val diff = git.diffStat()
        appendLine(diff.ifBlank { "(no changes)" })
        appendLine("```")
        appendLine()
        appendLine("## Recent Commits")
        appendLine("```")
        appendLine(git.logOneline())
        appendLine("```")
    }

    private fun writeToFile(content: String) {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
