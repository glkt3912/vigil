# Windows Native 実装 — Win32 API スタブ

## 概要

`expect class PlatformLogMonitor` の mingwX64 向け `actual` 実装。
現時点ではスタブで、実際の Win32 API 呼び出しは Phase 2 で実装予定。

---

## なぜ Windows Native が必要か

JVM 版は Java がインストールされた環境でしか動作しない。
Windows Native (mingwX64) で `.exe` を生成すれば:

- Java 不要でインストール・配布が簡単
- システムトレイ常駐アプリとして動作可能
- メモリ使用量が少ない

---

## Win32 API 構成案

JVM では Java NIO `WatchService` を使ったが、
Windows Native では Win32 API を直接呼び出す必要がある。

### ファイル監視

```
ReadDirectoryChangesW()  ← ディレクトリ内のファイル変更を監視
WaitForSingleObject()    ← イベントを待機
```

### ファイル読み取り

```
CreateFile()             ← ファイルハンドルを取得
SetFilePointer()         ← 読み取り位置を設定（tail -f）
ReadFile()               ← ファイル内容を読み取り
CloseHandle()            ← ハンドルを閉じる
```

### イベントログ監視（オプション）

Windows イベントログを監視する場合:

```
OpenEventLog()           ← イベントログハンドルを取得
NotifyChangeEventLog()   ← 変更通知を設定
ReadEventLog()           ← イベントレコードを読み取り
```

---

## Kotlin/Native での Win32 API 呼び出し

Kotlin/Native は C ライブラリを直接呼び出せる。
Win32 API は `platform.windows.*` パッケージで提供される。

```kotlin
import platform.windows.*

// ファイルを開く
val handle = CreateFileW(
    path,
    GENERIC_READ,
    FILE_SHARE_READ or FILE_SHARE_WRITE,
    null,
    OPEN_EXISTING,
    FILE_ATTRIBUTE_NORMAL,
    null
)

// ファイルポインタを末尾に移動
SetFilePointer(handle, 0, null, FILE_END)

// ファイルを読み取る
val buffer = ByteArray(4096)
val bytesRead = alloc<DWORDVar>()
ReadFile(handle, buffer.refTo(0), buffer.size.toUInt(), bytesRead.ptr, null)
```

---

## 現在の実装（スタブ）

```kotlin
actual class PlatformLogMonitor actual constructor(
    private val config: MonitorConfig,
) {
    actual fun events(): Flow<LogEvent> = flow {
        // TODO: Win32 API を使った実装
    }

    actual fun start(): Job = Job()
    actual fun stop() { }
}
```

Phase 2 で以下を実装予定:

1. `ReadDirectoryChangesW` によるファイル変更検知
2. `ReadFile` による増分読み取り
3. デスクトップ通知 (`Shell_NotifyIcon`)
4. システムトレイ常駐

---

## JVM vs Windows Native

| 観点 | JVM (jvmMain) | Windows Native (mingwX64Main) |
| --- | --- | --- |
| ファイル監視 | Java NIO WatchService | Win32 ReadDirectoryChangesW |
| ファイル読み取り | RandomAccessFile | Win32 CreateFile + ReadFile |
| 非同期処理 | Coroutines + Dispatchers.IO | Coroutines + nativeHeap |
| 実行環境 | JVM 必須 | .exe 単体で動作 |
| バイナリサイズ | jar (小) + JVM (大) | .exe (中) |
