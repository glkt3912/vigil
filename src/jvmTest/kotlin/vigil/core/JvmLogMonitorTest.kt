package vigil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vigil.model.LogLevel
import java.io.File

class JvmLogMonitorTest {

    private fun monitor(path: String = "/tmp/test.log") =
        PlatformLogMonitor(
            MonitorConfig(
                watchPath = path,
                wsUrl = "ws://localhost:8080/test",
            ),
        )

    // --- parseLine ---

    @Test
    fun `parseLine extracts level and message from bracketed format`() {
        val m = monitor()
        val event = m.parseLine("[ERROR] Something went wrong")
        assertEquals(LogLevel.ERROR, event.level)
        assertEquals("Something went wrong", event.message)
    }

    @Test
    fun `parseLine handles all log levels`() {
        val m = monitor()
        for (level in LogLevel.entries) {
            val event = m.parseLine("[${level.name}] test")
            assertEquals(level, event.level)
        }
    }

    @Test
    fun `parseLine defaults to INFO for unrecognized format`() {
        val m = monitor()
        val event = m.parseLine("just a plain message")
        assertEquals(LogLevel.INFO, event.level)
        assertEquals("just a plain message", event.message)
    }

    @Test
    fun `parseLine defaults to INFO for unknown level`() {
        val m = monitor()
        val event = m.parseLine("[UNKNOWN] some message")
        assertEquals(LogLevel.INFO, event.level)
        assertEquals("some message", event.message)
    }

    @Test
    fun `parseLine sets source from config watchPath`() {
        val m = monitor(path = "/var/log/myapp.log")
        val event = m.parseLine("[INFO] test")
        assertEquals("/var/log/myapp.log", event.source)
    }

    @Test
    fun `parseLine generates unique IDs`() {
        val m = monitor()
        val event1 = m.parseLine("[INFO] msg1")
        val event2 = m.parseLine("[INFO] msg2")
        assertTrue(event1.id != event2.id)
    }

    // --- readNewLines ---

    @Test
    fun `readNewLines reads from specified position`() {
        val tmpFile = File.createTempFile("vigil-test-", ".log")
        try {
            tmpFile.writeText("line1\nline2\nline3\n")
            val m = monitor(path = tmpFile.absolutePath)

            val (allLines, pos) = m.readNewLines(tmpFile.toPath(), 0L)
            assertEquals(3, allLines.size)
            assertEquals("line1", allLines[0])
            assertTrue(pos > 0)

            tmpFile.appendText("line4\n")
            val (newLines, newPos) = m.readNewLines(tmpFile.toPath(), pos)
            assertEquals(1, newLines.size)
            assertEquals("line4", newLines[0])
            assertTrue(newPos > pos)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `readNewLines returns empty when no new content`() {
        val tmpFile = File.createTempFile("vigil-test-", ".log")
        try {
            tmpFile.writeText("existing content\n")
            val m = monitor(path = tmpFile.absolutePath)
            val fileLength = tmpFile.length()

            val (lines, pos) = m.readNewLines(tmpFile.toPath(), fileLength)
            assertEquals(0, lines.size)
            assertEquals(fileLength, pos)
        } finally {
            tmpFile.delete()
        }
    }

    // --- events() 統合テスト ---

    @Test
    fun `events emits LogEvent when file is modified`(): Unit = runBlocking {
        val tmpFile = File.createTempFile("vigil-test-", ".log")
        try {
            tmpFile.writeText("")
            val m = PlatformLogMonitor(
                MonitorConfig(
                    watchPath = tmpFile.absolutePath,
                    wsUrl = "ws://localhost:8080/test",
                ),
            )

            val job = launch(Dispatchers.Default) {
                withTimeout(15_000) {
                    val event = m.events().first()
                    assertEquals(LogLevel.ERROR, event.level)
                    assertEquals("disk full", event.message)
                }
            }

            delay(1_000)
            tmpFile.appendText("[ERROR] disk full\n")

            job.join()
        } finally {
            tmpFile.delete()
        }
    }
}
