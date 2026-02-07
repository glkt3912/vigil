package vigil.plugin.contextmanager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCommandExecutorTest {

    // テストは実際の Git リポジトリ（このプロジェクト自身）で実行する想定
    private val git = GitCommandExecutor(findRepoRoot())

    @Test
    fun `branchName returns non-empty string`() {
        val branch = git.branchName()
        assertTrue(branch.isNotBlank(), "branch name should not be blank")
        assertFalse(branch.contains("error"), "should not be an error: $branch")
    }

    @Test
    fun `statusShort does not return error`() {
        val status = git.statusShort()
        assertFalse(status.startsWith("error("), "should not be an error: $status")
    }

    @Test
    fun `diffStat does not return error`() {
        val diff = git.diffStat()
        assertFalse(diff.startsWith("error("), "should not be an error: $diff")
    }

    @Test
    fun `logOneline returns commit history`() {
        val log = git.logOneline(5)
        assertFalse(log.startsWith("error("), "should not be an error: $log")
        assertTrue(log.isNotBlank(), "log should contain at least one commit")
    }

    companion object {
        // プロジェクトルート（.git がある場所）を探す
        private fun findRepoRoot(): File {
            var dir = File(System.getProperty("user.dir"))
            while (dir.parentFile != null) {
                if (File(dir, ".git").exists()) return dir
                dir = dir.parentFile
            }
            return File(".")
        }
    }
}
