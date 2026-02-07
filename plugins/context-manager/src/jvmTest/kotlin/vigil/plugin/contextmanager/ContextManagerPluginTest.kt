package vigil.plugin.contextmanager

import kotlinx.coroutines.test.runTest
import vigil.core.MonitorConfig
import vigil.plugin.PluginContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ContextManagerPluginTest {

    private val testOutputDir = File(System.getProperty("java.io.tmpdir"), "vigil-test-${System.nanoTime()}")
    private val testOutputPath = File(testOutputDir, "context_snapshot.md").absolutePath

    private fun testContext(published: MutableList<String> = mutableListOf()): PluginContext =
        object : PluginContext {
            override val config = MonitorConfig(watchPath = ".", wsUrl = "ws://test")
            override suspend fun publish(message: String) { published.add(message) }
        }

    private fun findRepoRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, ".git").exists()) return dir.absolutePath
            dir = dir.parentFile
        }
        return "."
    }

    @Test
    fun `buildSnapshot contains expected sections`() {
        val plugin = ContextManagerPlugin(repoPath = findRepoRoot(), outputPath = testOutputPath)
        val snapshot = plugin.buildSnapshot()

        assertContains(snapshot, "# Development Context Snapshot")
        assertContains(snapshot, "## Branch")
        assertContains(snapshot, "## Status")
        assertContains(snapshot, "## Diff (stat)")
        assertContains(snapshot, "## Recent Commits")
    }

    @Test
    fun `onInitialize writes file and publishes`() = runTest {
        val published = mutableListOf<String>()
        val plugin = ContextManagerPlugin(repoPath = findRepoRoot(), outputPath = testOutputPath)

        plugin.onInitialize(testContext(published))

        val file = File(testOutputPath)
        assertTrue(file.exists(), "snapshot file should be created")
        assertContains(file.readText(), "# Development Context Snapshot")
        assertTrue(published.isNotEmpty(), "should have published a message")

        // cleanup
        testOutputDir.deleteRecursively()
    }

    @Test
    fun `onHeartbeat updates file`() = runTest {
        val plugin = ContextManagerPlugin(repoPath = findRepoRoot(), outputPath = testOutputPath)

        plugin.onInitialize(testContext())
        val firstContent = File(testOutputPath).readText()

        plugin.onHeartbeat(testContext())
        val secondContent = File(testOutputPath).readText()

        // 内容は同じ形式（ヘッダーが同一）
        assertContains(secondContent, "# Development Context Snapshot")
        assertTrue(firstContent.isNotBlank())

        // cleanup
        testOutputDir.deleteRecursively()
    }
}
