package vigil.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// NestJS の LogEventDto に相当。詳細は docs/02-data-model.md 参照。
@Serializable
data class LogEvent(
    val id: String,
    val timestamp: Instant,
    val level: LogLevel,
    val source: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)
