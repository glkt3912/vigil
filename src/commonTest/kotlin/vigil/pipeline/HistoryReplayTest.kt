package vigil.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import vigil.buffer.LogRingBuffer
import vigil.model.LogEvent
import vigil.model.LogLevel

class HistoryReplayTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T10:30:00Z")

    private fun event(id: String, message: String = "test") = LogEvent(
        id = id,
        timestamp = fixedTimestamp,
        level = LogLevel.INFO,
        source = "test.log",
        message = message,
    )

    // --- withHistory ---

    @Test
    fun `withHistory prepends historical events before live events`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)
        buffer.add(event("old-1"))
        buffer.add(event("old-2"))

        val live = flowOf(event("new-1"), event("new-2"))
        val combined = live.withHistory(buffer).toList()

        assertEquals(4, combined.size)
        assertEquals("old-1", combined[0].id)
        assertEquals("old-2", combined[1].id)
        assertEquals("new-1", combined[2].id)
        assertEquals("new-2", combined[3].id)
    }

    @Test
    fun `historical events have historical=true in metadata`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)
        buffer.add(event("old-1"))

        val live = flowOf(event("new-1"))
        val combined = live.withHistory(buffer).toList()

        assertEquals("true", combined[0].metadata["historical"])
        assertEquals("false", combined[1].metadata["historical"])
    }

    @Test
    fun `empty buffer means only live events are emitted`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)
        val live = flowOf(event("new-1"))
        val combined = live.withHistory(buffer).toList()

        assertEquals(1, combined.size)
        assertEquals("false", combined[0].metadata["historical"])
    }

    @Test
    fun `historical flag preserves existing metadata`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)
        val eventWithMeta = event("old-1").copy(metadata = mapOf("env" to "prod"))
        buffer.add(eventWithMeta)

        val live = flowOf<LogEvent>()
        val combined = live.withHistory(buffer).toList()

        assertEquals("prod", combined[0].metadata["env"])
        assertEquals("true", combined[0].metadata["historical"])
    }

    // --- recordTo ---

    @Test
    fun `recordTo stores events in buffer while passing them through`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)
        val events = flowOf(event("a"), event("b"), event("c"))

        val collected = events.recordTo(buffer).toList()

        assertEquals(3, collected.size)
        assertEquals(3, buffer.size())
        assertEquals(listOf("a", "b", "c"), buffer.snapshot().map { it.id })
    }

    @Test
    fun `recordTo respects buffer capacity`() = runTest {
        val buffer = LogRingBuffer(capacity = 2)
        val events = flowOf(event("a"), event("b"), event("c"))

        events.recordTo(buffer).toList()

        assertEquals(2, buffer.size())
        assertEquals(listOf("b", "c"), buffer.snapshot().map { it.id })
    }

    // --- withHistory + recordTo 結合テスト ---

    @Test
    fun `recordTo then withHistory replays recorded events`() = runTest {
        val buffer = LogRingBuffer(capacity = 10)

        // 最初のストリームでバッファに記録
        flowOf(event("first-1"), event("first-2"))
            .recordTo(buffer)
            .toList()

        // 2回目の接続で履歴が再生される
        val secondStream = flowOf(event("second-1"))
        val result = secondStream.withHistory(buffer).toList()

        assertEquals(3, result.size)
        assertEquals("true", result[0].metadata["historical"])
        assertEquals("first-1", result[0].id)
        assertEquals("true", result[1].metadata["historical"])
        assertEquals("first-2", result[1].id)
        assertEquals("false", result[2].metadata["historical"])
        assertEquals("second-1", result[2].id)
    }
}
