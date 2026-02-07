package vigil.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LogEventTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T10:30:00Z")
    private val json = Json { encodeDefaults = true }

    private fun sampleEvent(
        metadata: Map<String, String> = emptyMap(),
    ) = LogEvent(
        id = "evt-001",
        timestamp = fixedTimestamp,
        level = LogLevel.INFO,
        source = "/var/log/app.log",
        message = "Server started",
        metadata = metadata,
    )

    @Test
    fun `metadata defaults to empty map`() {
        val event = sampleEvent()
        assertEquals(emptyMap(), event.metadata)
    }

    @Test
    fun `data class equality works on all fields`() {
        val a = sampleEvent()
        val b = sampleEvent()
        assertEquals(a, b)
    }

    @Test
    fun `data class equality detects field differences`() {
        val a = sampleEvent()
        val b = a.copy(level = LogLevel.ERROR)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = sampleEvent(metadata = mapOf("env" to "prod"))
        val copied = original.copy(message = "Updated")
        assertEquals("Updated", copied.message)
        assertEquals("evt-001", copied.id)
        assertEquals(mapOf("env" to "prod"), copied.metadata)
    }

    @Test
    fun `serializes to JSON and back round-trip`() {
        val event = sampleEvent(metadata = mapOf("key" to "value"))
        val jsonString = json.encodeToString(event)
        val deserialized = json.decodeFromString<LogEvent>(jsonString)
        assertEquals(event, deserialized)
    }

    @Test
    fun `JSON contains expected field names`() {
        val event = sampleEvent()
        val jsonString = json.encodeToString(event)
        assertTrue(jsonString.contains("\"id\""))
        assertTrue(jsonString.contains("\"timestamp\""))
        assertTrue(jsonString.contains("\"level\""))
        assertTrue(jsonString.contains("\"source\""))
        assertTrue(jsonString.contains("\"message\""))
        assertTrue(jsonString.contains("\"metadata\""))
    }
}
