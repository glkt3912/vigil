package vigil.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import vigil.model.LogEvent

// Windows Native 向け actual 実装（スタブ）。
// 実際の Win32 API 呼び出しは Phase 2 で実装予定。
// 詳細は docs/07-windows-implementation.md 参照。
actual class PlatformLogMonitor actual constructor(
    private val config: MonitorConfig,
) {
    // --- Win32 API 構成案（未実装）---
    // 1. ReadDirectoryChangesW() でディレクトリ変更を監視
    // 2. CreateFile() + ReadFile() でファイル読み取り
    // 3. イベントを Flow に変換して emit

    actual fun events(): Flow<LogEvent> = flow {
        // TODO: Win32 API を使った実装
        // 現時点ではスタブとして空の Flow を返す
    }

    actual fun start(): Job {
        return Job()  // スタブ
    }

    actual fun stop() {
        // スタブ
    }
}
