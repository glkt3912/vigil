package vigil.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LogLevelTest {

    @Test
    fun `enum has exactly six levels in expected order`() {
        val levels = LogLevel.entries
        assertEquals(6, levels.size)
        assertEquals(
            listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"),
            levels.map { it.name },
        )
    }

    @Test
    fun `ordinal values are ascending for severity comparison`() {
        assertTrue(LogLevel.TRACE.ordinal < LogLevel.DEBUG.ordinal)
        assertTrue(LogLevel.DEBUG.ordinal < LogLevel.INFO.ordinal)
        assertTrue(LogLevel.INFO.ordinal < LogLevel.WARN.ordinal)
        assertTrue(LogLevel.WARN.ordinal < LogLevel.ERROR.ordinal)
        assertTrue(LogLevel.ERROR.ordinal < LogLevel.FATAL.ordinal)
    }

    @Test
    fun `serializes to JSON string`() {
        val json = Json.encodeToString(LogLevel.ERROR)
        assertEquals("\"ERROR\"", json)
    }

    @Test
    fun `deserializes from JSON string`() {
        val level = Json.decodeFromString<LogLevel>("\"WARN\"")
        assertEquals(LogLevel.WARN, level)
    }
}
