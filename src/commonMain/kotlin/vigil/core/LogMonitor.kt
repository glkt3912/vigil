package vigil.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import vigil.model.LogEvent

// expect 宣言: NestJS の abstract class + @Injectable() に相当。
// 各プラットフォーム (jvmMain, mingwX64Main) が actual で実装を提供する。
// 詳細は docs/03-expect-actual.md 参照。
expect class PlatformLogMonitor(config: MonitorConfig) {
    fun events(): Flow<LogEvent>
    fun start(): Job
    fun stop()
}
