package vigil.plugin.contextmanager

import java.io.File

// Git コマンドを ProcessBuilder 経由で実行し、結果を文字列で返す。
// JVM 専用実装。
class GitCommandExecutor(private val workingDir: File = File(".")) {

    fun diffStat(): String = execute("git", "diff", "--stat")

    fun statusShort(): String = execute("git", "status", "--short")

    fun logOneline(count: Int = 10): String =
        execute("git", "log", "--oneline", "-$count")

    fun branchName(): String =
        execute("git", "rev-parse", "--abbrev-ref", "HEAD").trim()

    private fun execute(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return "error(exit=$exitCode): $output"
        }
        return output
    }
}
