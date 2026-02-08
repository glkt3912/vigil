package vigil.plugin.contextmanager

import vigil.plugin.PluginContext
import vigil.plugin.VigilPlugin
import java.io.File

// 開発コンテキストを自動収集し、意味レベルの要約スナップショットを生成するプラグイン。
// Git 情報 + GitHub Issue/PR 情報を組み合わせ、AI がセッション開始時に読み込むための
// 構造化された Markdown を出力する。
// 出力先: ファイル (.vigil/context_snapshot.md) + WebSocket パイプライン。
class ContextManagerPlugin(
    private val repoPath: String = ".",
    private val outputPath: String = ".vigil/context_snapshot.md",
) : VigilPlugin {
    override val name = "context-manager"

    private val git by lazy { GitCommandExecutor(File(repoPath)) }
    private val github by lazy { GitHubExecutor(File(repoPath)) }
    private var lastSnapshot: String? = null

    override suspend fun onInitialize(context: PluginContext) {
        val snapshot = buildSnapshot()
        lastSnapshot = snapshot
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onHeartbeat(context: PluginContext) {
        val snapshot = buildSnapshot()
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onShutdown() {
        // クリーンアップ不要
    }

    internal fun buildSnapshot(): String = buildString {
        val branch = git.branchName()

        appendLine("# 開発コンテキスト")
        appendLine()

        // --- 現在の作業 ---
        appendLine("## 現在の作業")
        appendLine("- **ブランチ:** $branch")

        val issueNumber = github.parseIssueNumber(branch)
        val issueInfo = issueNumber?.let { github.issueDetail(it) }
        if (issueInfo != null) {
            appendLine("- **関連 Issue:** #$issueNumber - ${issueInfo.title} (${issueInfo.state})")
        }

        val prInfo = github.currentPR(branch)
        if (prInfo != null) {
            appendLine("- **関連 PR:** #${prInfo.number} - ${prInfo.title} (${prInfo.state})")
        }
        appendLine()

        // --- Issue の進捗（チェックリスト抽出） ---
        if (issueInfo != null && issueInfo.body.contains("- [")) {
            appendLine("## Issue の進捗")
            issueInfo.body.lines()
                .filter { it.trimStart().startsWith("- [") }
                .forEach { appendLine(it.trim()) }
            appendLine()
        }

        // --- このブランチでの変更内容 ---
        appendLine("## このブランチでの変更内容")
        val branchLog = git.logFromDefault()
        if (branchLog.isBlank() || branchLog.startsWith("error(")) {
            appendLine("(${git.defaultBranch()} と同一、またはブランチ差分なし)")
        } else {
            appendLine("```")
            appendLine(branchLog)
            appendLine("```")
            val branchDiff = git.diffStatFromDefault()
            if (branchDiff.isNotBlank() && !branchDiff.startsWith("error(")) {
                appendLine()
                appendLine("**変更ファイル:**")
                appendLine("```")
                appendLine(branchDiff)
                appendLine("```")
            }
        }
        appendLine()

        // --- 未コミットの変更 ---
        appendLine("## 未コミットの変更")
        val status = git.statusShort()
        if (status.isBlank()) {
            appendLine("(clean)")
        } else {
            appendLine("```")
            appendLine(status)
            appendLine("```")
        }
    }

    private fun writeToFile(content: String) {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
