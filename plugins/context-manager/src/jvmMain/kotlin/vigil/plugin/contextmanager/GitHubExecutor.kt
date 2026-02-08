package vigil.plugin.contextmanager

import java.io.File

// gh CLI 経由で GitHub の Issue/PR 情報を取得する。
// gh が未インストール or 未認証の環境では available=false となり、全メソッドが null を返す。
class GitHubExecutor(private val workingDir: File = File(".")) {

    val available: Boolean by lazy {
        try {
            val process = ProcessBuilder("gh", "auth", "status")
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    // 現在のブランチに紐づく PR（タイトルと状態）
    fun currentPR(branch: String): PrInfo? {
        if (!available) return null
        val json = execute("gh", "pr", "list", "--head", branch, "--json", "number,title,state", "--limit", "1")
        return parsePR(json)
    }

    // Issue の詳細（タイトル + 本文）
    fun issueDetail(number: Int): IssueInfo? {
        if (!available) return null
        val json = execute("gh", "issue", "view", number.toString(), "--json", "title,body,state")
        return parseIssue(json)
    }

    // ブランチ名から Issue 番号を推定
    // パターン: feat/issue-22-xxx, fix/22-xxx, improve/22, feat/xxx (番号なし → null)
    fun parseIssueNumber(branch: String): Int? {
        val slug = branch.substringAfter("/", "")
        if (slug.isEmpty()) return null
        // 先頭が数字ならそれを取得、または "issue-N" パターン
        val match = Regex("""(?:issue-)?(\d+)""").find(slug)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun execute(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) return ""
        return output
    }

    internal fun parsePR(json: String): PrInfo? {
        // 軽量パース: [{"number":23,"title":"...","state":"OPEN"}]
        if (json.isBlank() || json.trim() == "[]") return null
        val number = Regex(""""number":(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val title = Regex(""""title":"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: ""
        val state = Regex(""""state":"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: ""
        return PrInfo(number, title, state)
    }

    internal fun parseIssue(json: String): IssueInfo? {
        if (json.isBlank()) return null
        val title = Regex(""""title":"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: return null
        val state = Regex(""""state":"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: ""
        // body はエスケープされた JSON 文字列。改行は \n、引用符は \"
        val bodyMatch = Regex(""""body":"(.*?)(?<!\\)"""").find(json)
        val body = bodyMatch?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: ""
        return IssueInfo(title, body, state)
    }

    data class PrInfo(val number: Int, val title: String, val state: String)
    data class IssueInfo(val title: String, val body: String, val state: String)
}
