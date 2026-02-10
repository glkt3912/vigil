package vigil.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import vigil.buffer.LogRingBuffer
import vigil.model.LogEvent

// Flow 拡張関数: 接続時にリングバッファの履歴を先に送信し、
// その後ライブイベントをストリーミングする。
// 履歴イベントには metadata["historical"] = "true" を付与し、
// ライブイベントには metadata["historical"] = "false" を付与する。
fun Flow<LogEvent>.withHistory(buffer: LogRingBuffer): Flow<LogEvent> = flow {
    val history = buffer.snapshot()
    for (event in history) {
        emit(event.withHistoricalFlag(true))
    }

    collect { event ->
        emit(event.withHistoricalFlag(false))
    }
}

// Flow 拡張関数: 各イベントをリングバッファに記録しながら通過させる。
fun Flow<LogEvent>.recordTo(buffer: LogRingBuffer): Flow<LogEvent> =
    onEach { event -> buffer.add(event) }

// LogEvent に historical フラグを付与するヘルパー。
// 既存の metadata を保持しつつ、"historical" キーを追加/上書きする。
internal fun LogEvent.withHistoricalFlag(historical: Boolean): LogEvent =
    copy(metadata = metadata + ("historical" to historical.toString()))
