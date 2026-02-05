# JVM 実装 — Java NIO WatchService と callbackFlow

## 概要

`expect class PlatformLogMonitor` の JVM 向け `actual` 実装。
Java NIO の `WatchService` でファイル監視を行い、新しい行を `Flow<LogEvent>` として emit する。

---

## NestJS での対比: chokidar

NestJS でファイル監視をするなら chokidar を使う:

```typescript
import * as chokidar from 'chokidar';
import { Observable } from 'rxjs';

function watchFile(path: string): Observable<LogEvent> {
  return new Observable(subscriber => {
    let lastPosition = 0;

    const watcher = chokidar.watch(path);
    watcher.on('change', async () => {
      const newLines = await readNewLines(path, lastPosition);
      lastPosition = newLines.newPosition;

      for (const line of newLines.lines) {
        subscriber.next(parseLine(line));
      }
    });

    // クリーンアップ
    return () => watcher.close();
  });
}
```

---

## Kotlin (JVM) での実装

```kotlin
actual fun events(): Flow<LogEvent> = callbackFlow {
    val watchService = FileSystems.getDefault().newWatchService()
    directory.register(watchService, ENTRY_MODIFY)

    var lastPosition = file.length()

    launch(Dispatchers.IO) {
        while (isActive) {
            val key = watchService.take()  // ブロッキング待機
            // 新しい行を読み取って trySend()
        }
    }

    awaitClose { watchService.close() }
}
```

---

## callbackFlow とは

`callbackFlow` は「コールバックベースの API を Flow に変換する」ためのビルダー。
NestJS で `new Observable(subscriber => ...)` と書くのと同じパターン。

| NestJS (RxJS) | Kotlin Flow | 説明 |
| --- | --- | --- |
| `new Observable(subscriber =>)` | `callbackFlow { }` | ストリームを作成 |
| `subscriber.next(value)` | `trySend(value)` | 値を送信 |
| `return () => cleanup()` | `awaitClose { cleanup() }` | クリーンアップ |

### なぜ callbackFlow を使うか

`flow { }` ビルダーでは外部からの非同期イベント（ファイル変更通知など）を受け取れない。
`callbackFlow` は内部で Channel を使い、外部イベントを安全に Flow に変換できる。

```kotlin
// これは動かない: flow { } 内では外部イベントを待てない
fun events(): Flow<LogEvent> = flow {
    watcher.onFileChange { line ->
        emit(parseLine(line))  // エラー: emit は flow ブロック内でしか呼べない
    }
}

// これなら動く: callbackFlow は Channel 経由で外部から値を受け取れる
fun events(): Flow<LogEvent> = callbackFlow {
    watcher.onFileChange { line ->
        trySend(parseLine(line))  // OK: Channel 経由で送信
    }
    awaitClose { watcher.close() }
}
```

---

## Java NIO WatchService とは

Java 標準のファイル/ディレクトリ監視 API。Node.js の `fs.watch()` に相当。

```kotlin
// 1. WatchService を取得
val watchService = FileSystems.getDefault().newWatchService()

// 2. ディレクトリを監視登録
directory.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

// 3. イベントを待機（ブロッキング）
val key = watchService.take()

// 4. 発生したイベントを処理
for (event in key.pollEvents()) {
    val changedFile = event.context() as Path
    // ファイル変更を処理
}

// 5. キーをリセット（次のイベントを受け取るため）
key.reset()
```

### Node.js との対比

```javascript
// Node.js: fs.watch (非推奨、プラットフォーム依存)
fs.watch('/var/log', (eventType, filename) => {
  if (eventType === 'change') {
    // 処理
  }
});

// Node.js: chokidar (推奨、クロスプラットフォーム)
chokidar.watch('/var/log/app.log').on('change', (path) => {
  // 処理
});
```

---

## Dispatchers.IO とは

I/O 操作（ファイル読み書き、ネットワーク）用のスレッドプール。
Node.js は I/O がノンブロッキングだが、JVM の I/O API はブロッキングなので専用スレッドで実行する。

```kotlin
// ブロッキング I/O は Dispatchers.IO で実行
launch(Dispatchers.IO) {
    val key = watchService.take()  // ブロッキング、メインスレッドを止めない
}
```

NestJS でいうと:

```typescript
// Node.js: I/O は自動的にノンブロッキング
const data = await fs.promises.readFile(path);  // イベントループをブロックしない

// JVM: 明示的に I/O スレッドで実行しないとメインスレッドがブロックされる
withContext(Dispatchers.IO) {
    file.readLine()  // I/O スレッドで実行
}
```

---

## tail -f の再現

ファイル末尾の位置を記憶し、変更時に新しい部分だけを読み取る:

```kotlin
var lastPosition = file.length()  // 初期位置 = ファイル末尾

// ファイル変更時
file.seek(lastPosition)           // 前回の位置に移動
val newLines = readLines()        // 新しい行を読む
lastPosition = file.filePointer   // 位置を更新
```

これは Unix の `tail -f` と同じ動作:

```bash
tail -f /var/log/app.log
# ファイル末尾を監視し、追記された行だけを出力
```

---

## ログ行のパース

簡易実装として `[LEVEL] message` 形式を想定:

```
[INFO] Application started
[ERROR] Connection failed
[DEBUG] Processing request
```

```kotlin
val levelPattern = Regex("""^\[(\w+)](.*)$""")
val match = levelPattern.find(line)
val level = match?.groupValues?.get(1)  // "INFO", "ERROR", etc.
val message = match?.groupValues?.get(2)  // "Application started", etc.
```

実際の運用では、JSON 形式やカスタムフォーマットに対応する拡張が必要。
