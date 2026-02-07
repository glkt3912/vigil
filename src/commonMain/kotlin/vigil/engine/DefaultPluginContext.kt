package vigil.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import vigil.core.MonitorConfig
import vigil.model.LogEvent
import vigil.model.LogLevel
import vigil.plugin.PluginContext

// PluginContext のデフォルト実装。
// publish() で受け取ったメッセージを LogEvent に変換し、SharedFlow に emit する。
// この Flow を WebSocketPipeline.connect() に渡すことで、既存パイプラインに合流する。
class DefaultPluginContext(
    override val config: MonitorConfig,
) : PluginContext {
    private val _events = MutableSharedFlow<LogEvent>(extraBufferCapacity = 64)
    private var sequence = 0L

    // WebSocketPipeline.connect() に渡す Flow。
    val events: Flow<LogEvent> = _events.asSharedFlow()

    override suspend fun publish(message: String) {
        val event = LogEvent(
            id = "ctx-${++sequence}",
            timestamp = Clock.System.now(),
            level = LogLevel.INFO,
            source = "plugin:context-manager",
            message = message.lines().firstOrNull() ?: "",
            metadata = mapOf(
                "type" to "context",
                "snapshot" to message,
            ),
        )
        _events.emit(event)
    }
}
