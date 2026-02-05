package vigil.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import vigil.model.LogEvent
import vigil.model.LogLevel

// Flow の拡張関数でフィルタリング演算子を提供。
// NestJS の Pipe (ValidationPipe 等) に相当するが、データ変換ではなくストリームフィルタ。
// 詳細は docs/05-websocket-pipeline.md 参照。

// 指定レベル以上のログのみ通す
fun Flow<LogEvent>.filterByLevel(minLevel: LogLevel): Flow<LogEvent> =
    filter { it.level.ordinal >= minLevel.ordinal }

// ソース名が正規表現にマッチするログのみ通す
fun Flow<LogEvent>.filterBySource(pattern: Regex): Flow<LogEvent> =
    filter { pattern.matches(it.source) }

// メッセージに特定の文字列を含むログのみ通す
fun Flow<LogEvent>.filterByMessage(keyword: String): Flow<LogEvent> =
    filter { it.message.contains(keyword, ignoreCase = true) }
