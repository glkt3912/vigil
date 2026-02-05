package vigil.pipeline

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vigil.core.MonitorConfig
import vigil.model.LogEvent
import vigil.retry.retryWithBackoff

// WebSocket 送信パイプライン。
// NestJS の @WebSocketGateway() のクライアント版に相当。
// 詳細は docs/05-websocket-pipeline.md 参照。
class WebSocketPipeline(
    private val config: MonitorConfig,
    private val client: HttpClient,
) {
    private val json = Json { encodeDefaults = true }

    // Flow<LogEvent> を受け取り、JSON 化して WebSocket で送信する。
    // buffer() でバックプレッシャー制御、retryWithBackoff() で自動再接続。
    suspend fun connect(events: Flow<LogEvent>) {
        client.webSocket(config.wsUrl) {
            events
                .buffer(config.bufferSize)
                .map { event -> json.encodeToString(event) }
                .retryWithBackoff(config.reconnect)
                .collect { jsonString ->
                    send(Frame.Text(jsonString))
                }
        }
    }
}
