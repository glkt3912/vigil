package vigil.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import vigil.model.LogEvent
import vigil.model.LogLevel

class LogRingBufferTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T10:30:00Z")

    private fun event(id: Int) = LogEvent(
        id = "evt-$id",
        timestamp = fixedTimestamp,
        level = LogLevel.INFO,
        source = "test.log",
        message = "message-$id",
    )

    @Test
    fun `new buffer is empty`() = runTest {
        val buf = LogRingBuffer(capacity = 10)
        assertEquals(0, buf.size())
        assertEquals(emptyList(), buf.snapshot())
    }

    @Test
    fun `add and snapshot returns events in insertion order`() = runTest {
        val buf = LogRingBuffer(capacity = 5)
        buf.add(event(1))
        buf.add(event(2))
        buf.add(event(3))

        val snap = buf.snapshot()
        assertEquals(3, snap.size)
        assertEquals("evt-1", snap[0].id)
        assertEquals("evt-2", snap[1].id)
        assertEquals("evt-3", snap[2].id)
    }

    @Test
    fun `oldest events are evicted when capacity exceeded`() = runTest {
        val buf = LogRingBuffer(capacity = 3)
        buf.add(event(1))
        buf.add(event(2))
        buf.add(event(3))
        buf.add(event(4))
        buf.add(event(5))

        val snap = buf.snapshot()
        assertEquals(3, snap.size)
        assertEquals("evt-3", snap[0].id)
        assertEquals("evt-4", snap[1].id)
        assertEquals("evt-5", snap[2].id)
    }

    @Test
    fun `size never exceeds capacity`() = runTest {
        val buf = LogRingBuffer(capacity = 3)
        for (i in 1..100) {
            buf.add(event(i))
        }
        assertEquals(3, buf.size())
    }

    @Test
    fun `snapshot returns a copy that is not affected by later adds`() = runTest {
        val buf = LogRingBuffer(capacity = 5)
        buf.add(event(1))
        val snap = buf.snapshot()
        buf.add(event(2))
        assertEquals(1, snap.size)
    }

    @Test
    fun `capacity of 1 keeps only the latest event`() = runTest {
        val buf = LogRingBuffer(capacity = 1)
        buf.add(event(1))
        buf.add(event(2))
        val snap = buf.snapshot()
        assertEquals(1, snap.size)
        assertEquals("evt-2", snap[0].id)
    }

    @Test
    fun `clear empties the buffer`() = runTest {
        val buf = LogRingBuffer(capacity = 5)
        buf.add(event(1))
        buf.add(event(2))
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(emptyList(), buf.snapshot())
    }

    @Test
    fun `capacity of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LogRingBuffer(capacity = 0)
        }
    }

    @Test
    fun `negative capacity is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LogRingBuffer(capacity = -1)
        }
    }

    @Test
    fun `clear then add restores normal operation`() = runTest {
        val buf = LogRingBuffer(capacity = 3)
        buf.add(event(1))
        buf.add(event(2))
        buf.clear()

        buf.add(event(10))
        buf.add(event(11))

        val snap = buf.snapshot()
        assertEquals(2, snap.size)
        assertEquals("evt-10", snap[0].id)
        assertEquals("evt-11", snap[1].id)
    }

    @Test
    fun `exactly at capacity without overflow`() = runTest {
        val buf = LogRingBuffer(capacity = 3)
        buf.add(event(1))
        buf.add(event(2))
        buf.add(event(3))

        assertEquals(3, buf.size())
        val snap = buf.snapshot()
        assertEquals(listOf("evt-1", "evt-2", "evt-3"), snap.map { it.id })
    }

    @Test
    fun `full wrap-around maintains correct order`() = runTest {
        val buf = LogRingBuffer(capacity = 3)
        for (i in 1..10) {
            buf.add(event(i))
        }
        val snap = buf.snapshot()
        assertEquals(listOf("evt-8", "evt-9", "evt-10"), snap.map { it.id })
    }

    @Test
    fun `concurrent adds do not corrupt buffer`() = runTest {
        val buf = LogRingBuffer(capacity = 100)
        val jobs = (1..10).map { batch ->
            launch {
                for (i in 1..100) {
                    buf.add(event(batch * 1000 + i))
                }
            }
        }
        jobs.forEach { it.join() }

        val snap = buf.snapshot()
        assertEquals(100, snap.size)
        // 全要素がユニークで null でないことを検証
        assertEquals(100, snap.map { it.id }.toSet().size)
        assertTrue(snap.all { it.id.startsWith("evt-") })
    }
}
