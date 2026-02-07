package vigil.engine

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import vigil.core.MonitorConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultPluginContextTest {

    private fun createContext() = DefaultPluginContext(
        config = MonitorConfig(watchPath = ".", wsUrl = "ws://test"),
    )

    @Test
    fun `publish emits LogEvent with context metadata`() = runTest {
        val context = createContext()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            val event = context.events.first()
            assertEquals("context", event.metadata["type"])
            assertTrue(event.metadata["snapshot"]!!.contains("test snapshot"))
            assertEquals("plugin:context-manager", event.source)
            assertTrue(event.id.startsWith("ctx-"))
        }

        context.publish("test snapshot\nline 2")
        job.join()
    }

    @Test
    fun `publish uses first line as message`() = runTest {
        val context = createContext()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            val event = context.events.first()
            assertEquals("# Development Context Snapshot", event.message)
        }

        context.publish("# Development Context Snapshot\n## Branch\nmain")
        job.join()
    }

    @Test
    fun `publish increments sequence id`() = runTest {
        val context = createContext()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            val first = context.events.first()
            assertContains(first.id, "ctx-1")
        }

        context.publish("first")
        job.join()

        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            val second = context.events.first()
            assertContains(second.id, "ctx-2")
        }

        context.publish("second")
        job2.join()
    }
}
