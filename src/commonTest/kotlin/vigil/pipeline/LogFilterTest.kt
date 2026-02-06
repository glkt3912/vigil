package vigil.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import vigil.model.LogEvent
import vigil.model.LogLevel

class LogFilterTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T10:30:00Z")

    private fun event(
        level: LogLevel = LogLevel.INFO,
        source: String = "app.log",
        message: String = "test message",
    ) = LogEvent(
        id = "test-id",
        timestamp = fixedTimestamp,
        level = level,
        source = source,
        message = message,
    )

    // --- filterByLevel ---

    @Test
    fun `filterByLevel WARN passes WARN ERROR FATAL`() = runTest {
        val events = flowOf(
            event(level = LogLevel.TRACE),
            event(level = LogLevel.DEBUG),
            event(level = LogLevel.INFO),
            event(level = LogLevel.WARN),
            event(level = LogLevel.ERROR),
            event(level = LogLevel.FATAL),
        )

        val result = events.filterByLevel(LogLevel.WARN).toList()

        assertEquals(3, result.size)
        assertEquals(
            listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL),
            result.map { it.level },
        )
    }

    @Test
    fun `filterByLevel TRACE passes all events`() = runTest {
        val events = flowOf(
            event(level = LogLevel.TRACE),
            event(level = LogLevel.FATAL),
        )

        val result = events.filterByLevel(LogLevel.TRACE).toList()
        assertEquals(2, result.size)
    }

    @Test
    fun `filterByLevel FATAL passes only FATAL`() = runTest {
        val events = flowOf(
            event(level = LogLevel.ERROR),
            event(level = LogLevel.FATAL),
        )

        val result = events.filterByLevel(LogLevel.FATAL).toList()
        assertEquals(1, result.size)
        assertEquals(LogLevel.FATAL, result[0].level)
    }

    // --- filterBySource ---

    @Test
    fun `filterBySource matches regex pattern`() = runTest {
        val events = flowOf(
            event(source = "app-server.log"),
            event(source = "db-server.log"),
            event(source = "cache.log"),
        )

        val result = events.filterBySource(Regex(".*server.*")).toList()
        assertEquals(2, result.size)
    }

    @Test
    fun `filterBySource with exact match`() = runTest {
        val events = flowOf(
            event(source = "app.log"),
            event(source = "other.log"),
        )

        val result = events.filterBySource(Regex("app\\.log")).toList()
        assertEquals(1, result.size)
        assertEquals("app.log", result[0].source)
    }

    @Test
    fun `filterBySource with no matches returns empty`() = runTest {
        val events = flowOf(
            event(source = "app.log"),
        )

        val result = events.filterBySource(Regex("nonexistent")).toList()
        assertEquals(0, result.size)
    }

    // --- filterByMessage ---

    @Test
    fun `filterByMessage is case insensitive`() = runTest {
        val events = flowOf(
            event(message = "ERROR occurred in module"),
            event(message = "error occurred in module"),
            event(message = "All good"),
        )

        val result = events.filterByMessage("error").toList()
        assertEquals(2, result.size)
    }

    @Test
    fun `filterByMessage matches substring`() = runTest {
        val events = flowOf(
            event(message = "Connection timeout after 30s"),
            event(message = "Request completed"),
        )

        val result = events.filterByMessage("timeout").toList()
        assertEquals(1, result.size)
    }

    @Test
    fun `filterByMessage with empty keyword matches all`() = runTest {
        val events = flowOf(
            event(message = "any message"),
        )

        val result = events.filterByMessage("").toList()
        assertEquals(1, result.size)
    }

    // --- フィルタチェイン ---

    @Test
    fun `filters can be chained`() = runTest {
        val events = flowOf(
            event(level = LogLevel.ERROR, source = "app.log", message = "disk full"),
            event(level = LogLevel.ERROR, source = "db.log", message = "disk full"),
            event(level = LogLevel.INFO, source = "app.log", message = "disk full"),
            event(level = LogLevel.ERROR, source = "app.log", message = "all ok"),
        )

        val result = events
            .filterByLevel(LogLevel.ERROR)
            .filterBySource(Regex("app\\.log"))
            .filterByMessage("disk")
            .toList()

        assertEquals(1, result.size)
        assertEquals("disk full", result[0].message)
        assertEquals("app.log", result[0].source)
    }
}
