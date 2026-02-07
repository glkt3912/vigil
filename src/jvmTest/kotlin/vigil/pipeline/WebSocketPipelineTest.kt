package vigil.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vigil.core.MonitorConfig
import vigil.model.LogEvent
import vigil.model.LogLevel

class WebSocketPipelineTest {

    private val json = Json { encodeDefaults = true }
    private val fixedTimestamp = Instant.parse("2025-01-15T10:30:00Z")

    private fun sampleEvent(
        level: LogLevel = LogLevel.INFO,
        message: String = "test",
    ) = LogEvent(
        id = "evt-001",
        timestamp = fixedTimestamp,
        level = level,
        source = "test.log",
        message = message,
    )

    @Test
    fun `LogEvent serializes to valid JSON for WebSocket transmission`() {
        val event = sampleEvent()
        val jsonString = json.encodeToString(event)
        val deserialized = json.decodeFromString<LogEvent>(jsonString)
        assertEquals(event, deserialized)

        assertTrue(jsonString.contains("\"id\":\"evt-001\""))
        assertTrue(jsonString.contains("\"level\":\"INFO\""))
    }

    @Test
    fun `pipeline transformation chain produces JSON strings`() = runTest {
        val events = flowOf(
            sampleEvent(level = LogLevel.ERROR, message = "fail"),
            sampleEvent(level = LogLevel.INFO, message = "ok"),
        )

        val config = MonitorConfig(
            watchPath = "/test.log",
            wsUrl = "ws://localhost/ws",
        )

        val jsonStrings = events
            .buffer(config.bufferSize)
            .map { event -> json.encodeToString(event) }
            .toList()

        assertEquals(2, jsonStrings.size)

        val first = json.decodeFromString<LogEvent>(jsonStrings[0])
        assertEquals(LogLevel.ERROR, first.level)
        assertEquals("fail", first.message)

        val second = json.decodeFromString<LogEvent>(jsonStrings[1])
        assertEquals(LogLevel.INFO, second.level)
        assertEquals("ok", second.message)
    }

    @Test
    fun `pipeline handles large flow without backpressure issues`() = runTest {
        val events = (1..1000).map { sampleEvent(message = "event-$it") }

        val config = MonitorConfig(
            watchPath = "/test.log",
            wsUrl = "ws://localhost/ws",
            bufferSize = 128,
        )

        val jsonStrings = flowOf(*events.toTypedArray())
            .buffer(config.bufferSize)
            .map { event -> json.encodeToString(event) }
            .toList()

        assertEquals(1000, jsonStrings.size)
    }
}
