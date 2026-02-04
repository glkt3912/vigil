package vigil.model

import kotlinx.serialization.Serializable

// TypeScript の enum とほぼ同じ。詳細は docs/02-data-model.md 参照。
@Serializable
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
}
