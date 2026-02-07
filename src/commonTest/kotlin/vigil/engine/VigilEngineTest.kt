package vigil.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import vigil.core.MonitorConfig
import vigil.plugin.PluginContext
import vigil.plugin.VigilPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VigilEngineTest {

    private fun testContext(): PluginContext = object : PluginContext {
        override val config = MonitorConfig(watchPath = ".", wsUrl = "ws://test")
        override suspend fun publish(message: String) {}
    }

    @Test
    fun `register adds plugin to engine`() {
        val engine = VigilEngine()
        val plugin = RecordingPlugin("test")
        engine.register(plugin)
        assertEquals(1, engine.registeredPlugins().size)
        assertEquals("test", engine.registeredPlugins().first().name)
    }

    @Test
    fun `register returns engine for chaining`() {
        val engine = VigilEngine()
        val result = engine.register(RecordingPlugin("a"))
        assertTrue(result === engine)
    }

    @Test
    fun `start calls onInitialize for all plugins`() = runTest {
        val engine = VigilEngine(heartbeatInterval = 10.seconds)
        val p1 = RecordingPlugin("p1")
        val p2 = RecordingPlugin("p2")
        engine.register(p1).register(p2)

        engine.start(this, testContext())
        assertEquals(1, p1.initCount)
        assertEquals(1, p2.initCount)
        engine.stop()
    }

    @Test
    fun `heartbeat is called periodically`() = runTest {
        val engine = VigilEngine(heartbeatInterval = 5.seconds)
        val plugin = RecordingPlugin("hb")
        engine.register(plugin)

        engine.start(this, testContext())
        assertEquals(0, plugin.heartbeatCount)

        advanceTimeBy(5_001)
        assertEquals(1, plugin.heartbeatCount)

        advanceTimeBy(5_000)
        assertEquals(2, plugin.heartbeatCount)

        engine.stop()
    }

    @Test
    fun `stop calls onShutdown for all plugins`() = runTest {
        val engine = VigilEngine(heartbeatInterval = 10.seconds)
        val p1 = RecordingPlugin("p1")
        val p2 = RecordingPlugin("p2")
        engine.register(p1).register(p2)

        engine.start(this, testContext())
        engine.stop()

        assertEquals(1, p1.shutdownCount)
        assertEquals(1, p2.shutdownCount)
    }

    @Test
    fun `lifecycle order is initialize then heartbeat then shutdown`() = runTest {
        val engine = VigilEngine(heartbeatInterval = 1.seconds)
        val events = mutableListOf<String>()
        val plugin = object : VigilPlugin {
            override val name = "ordered"
            override suspend fun onInitialize(context: PluginContext) { events.add("init") }
            override suspend fun onHeartbeat(context: PluginContext) { events.add("heartbeat") }
            override suspend fun onShutdown() { events.add("shutdown") }
        }
        engine.register(plugin)

        engine.start(this, testContext())
        advanceTimeBy(1_001)
        engine.stop()

        assertEquals(listOf("init", "heartbeat", "shutdown"), events)
    }
}

// テスト用の呼び出し記録プラグイン
private class RecordingPlugin(override val name: String) : VigilPlugin {
    var initCount = 0
    var heartbeatCount = 0
    var shutdownCount = 0

    override suspend fun onInitialize(context: PluginContext) { initCount++ }
    override suspend fun onHeartbeat(context: PluginContext) { heartbeatCount++ }
    override suspend fun onShutdown() { shutdownCount++ }
}
