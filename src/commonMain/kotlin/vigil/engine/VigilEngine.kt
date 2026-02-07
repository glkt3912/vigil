package vigil.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vigil.core.MonitorConfig
import vigil.plugin.PluginContext
import vigil.plugin.VigilPlugin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// プラグインのライフサイクルを管理するエンジン。
// NestJS の NestApplication (app.listen()) に相当する。
class VigilEngine(
    private val heartbeatInterval: Duration = 30.seconds,
) {
    private val plugins = mutableListOf<VigilPlugin>()
    private var heartbeatJob: Job? = null

    fun register(plugin: VigilPlugin): VigilEngine {
        plugins.add(plugin)
        return this
    }

    suspend fun start(scope: CoroutineScope, context: PluginContext) {
        plugins.forEach { it.onInitialize(context) }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                plugins.forEach { it.onHeartbeat(context) }
            }
        }
    }

    suspend fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        plugins.forEach { it.onShutdown() }
    }

    fun registeredPlugins(): List<VigilPlugin> = plugins.toList()
}
