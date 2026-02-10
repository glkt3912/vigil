# リングバッファと履歴再生 — NestJS キャッシュのストリーム版

## 概要

WebSocket 接続時に直近のログ履歴をバースト送信し、接続直後から文脈が見える仕組み。
NestJS でいうと `@nestjs/cache-manager` の固定サイズキャッシュ + `socket.emit()` の初回バルク送信に相当する。

---

## 課題: 接続したら真っ白

```text
エラー発生 → 3秒後に接続 → 画面に何も表示されない

┌──────────┐     ┌──────────┐
│ ERROR!   │     │          │  ← 接続前のログは見えない
│ ERROR!   │     │ (empty)  │
│ WARN     │ ... │          │
└──────────┘     └──────────┘
  サーバー側        クライアント
```

**解決策**: メモリ上にリングバッファで直近 N 件を保持し、接続時に履歴として送信する。

---

## NestJS でのアプローチ（参考）

```typescript
// NestJS: 接続時に過去ログを送信する Gateway
@WebSocketGateway()
export class LogGateway implements OnGatewayConnection {
  private history: LogEvent[] = [];
  private readonly MAX_HISTORY = 1000;

  handleConnection(client: Socket) {
    // 1. 接続時に履歴を一括送信
    client.emit('history', this.history);
  }

  @SubscribeMessage('log')
  handleLog(@MessageBody() event: LogEvent) {
    // 2. リングバッファ: 古いものから押し出す
    this.history.push(event);
    if (this.history.length > this.MAX_HISTORY) {
      this.history.shift();
    }
  }
}
```

**問題点**: 配列 + `shift()` は O(n) で非効率。固定サイズのリングバッファが適切。

---

## Kotlin でのリングバッファ実装

### LogRingBuffer

```kotlin
class LogRingBuffer(val capacity: Int) {
    private val buffer = arrayOfNulls<LogEvent>(capacity)
    private var head = 0   // 次の書き込み位置
    private var count = 0  // 現在の要素数
    private val mutex = Mutex()  // coroutine-safe な排他制御

    suspend fun add(event: LogEvent) { ... }        // O(1) で追加
    suspend fun snapshot(): List<LogEvent> { ... }   // 古い順のコピーを返す
}
```

### NestJS との対比

| 観点 | NestJS (TypeScript) | Kotlin (Vigil) |
| --- | --- | --- |
| データ構造 | `Array` + `push/shift` | 固定サイズ配列 + ポインタ |
| 追加の計算量 | O(n) (`shift` が全要素移動) | O(1) (ポインタを進めるだけ) |
| メモリ上限 | 手動チェック (`if > MAX`) | 固定サイズで自動保証 |
| スレッド安全性 | 不要 (シングルスレッド) | `Mutex` で coroutine-safe |
| 取得 | `this.history` (参照) | `snapshot()` (コピー) |

### なぜ Mutex か

```kotlin
// ❌ synchronized — JVM 専用、commonMain で使えない
synchronized(lock) { buffer[head] = event }

// ✅ Mutex — Kotlin Multiplatform 対応、ノンブロッキング
mutex.withLock { buffer[head] = event }
```

NestJS は JavaScript のシングルスレッドモデルなので排他制御が不要だが、
Kotlin の coroutine は複数スレッドで並行実行されるため、共有データへの排他制御が必要。

---

## 履歴再生: Flow 拡張関数

### パイプラインの変化

```text
【Before】
events → buffer → JSON → retry → send

【After】
events → recordTo(buffer) → withHistory(buffer) → buffer → JSON → retry → send
         ↑ バッファに蓄積    ↑ 接続時に履歴を先行送信
```

### recordTo — イベントを記録しながら通過

```kotlin
fun Flow<LogEvent>.recordTo(buffer: LogRingBuffer): Flow<LogEvent> =
    onEach { event -> buffer.add(event) }
```

NestJS (RxJS) の `tap()` に相当:

```typescript
// RxJS
events$.pipe(
  tap(event => history.push(event)),
);

// Kotlin Flow
events.recordTo(buffer)
```

### withHistory — 接続時に履歴を先行送信

```kotlin
fun Flow<LogEvent>.withHistory(buffer: LogRingBuffer): Flow<LogEvent> = flow {
    // 1. 履歴を先に送信（historical: true）
    for (event in buffer.snapshot()) {
        emit(event.withHistoricalFlag(true))
    }
    // 2. ライブイベントをストリーミング（historical: false）
    collect { event ->
        emit(event.withHistoricalFlag(false))
    }
}
```

NestJS での同等処理:

```typescript
// NestJS: 接続時に concat で履歴 → ライブを結合
import { concat, from } from 'rxjs';

function withHistory(live$: Observable<LogEvent>, history: LogEvent[]) {
  const history$ = from(history).pipe(
    map(e => ({ ...e, metadata: { ...e.metadata, historical: 'true' } })),
  );
  const tagged$ = live$.pipe(
    map(e => ({ ...e, metadata: { ...e.metadata, historical: 'false' } })),
  );
  return concat(history$, tagged$);
}
```

| 観点 | RxJS (NestJS) | Kotlin Flow |
| --- | --- | --- |
| 結合方法 | `concat(history$, live$)` | `flow { emit(history); collect(live) }` |
| 副作用 (記録) | `tap()` | `onEach()` |
| フラグ付与 | `map(e => ({...e, ...}))` | `copy(metadata = ...)` |

---

## historical フラグ

履歴ログとライブログを区別するために、既存の `metadata` フィールドを活用する。

```kotlin
// LogEvent の metadata に historical フラグを追加
internal fun LogEvent.withHistoricalFlag(historical: Boolean): LogEvent =
    copy(metadata = metadata + ("historical" to historical.toString()))
```

### 送信される JSON

```json
// 履歴ログ
{
  "id": "evt-001",
  "timestamp": "2025-01-15T10:30:00Z",
  "level": "ERROR",
  "message": "Connection timeout",
  "metadata": { "historical": "true" }
}

// ライブログ
{
  "id": "evt-042",
  "timestamp": "2025-01-15T10:30:05Z",
  "level": "INFO",
  "message": "Request completed",
  "metadata": { "historical": "false" }
}
```

クライアント側で `metadata.historical === "true"` を見てスタイルを変える（薄い色で表示する等）。

---

## 設定

`MonitorConfig` の `historySize` で容量を制御する:

```kotlin
val config = MonitorConfig(
    watchPath = "/var/log/app.log",
    wsUrl = "ws://localhost:8080/logs",
    historySize = 1000,  // 直近 1000 件を保持（デフォルト）
)
```

| パラメータ | 既存の `bufferSize` | 新規の `historySize` |
| --- | --- | --- |
| 目的 | Flow バックプレッシャー制御 | 履歴再生用の保持件数 |
| 対象 | 送信待ちキュー | 過去ログキャッシュ |
| デフォルト | 256 | 1000 |
| メモリ目安 | 〜128KB | 〜500KB |

---

## WebSocketPipeline への組み込み

```kotlin
class WebSocketPipeline(
    private val config: MonitorConfig,
    private val client: HttpClient,
    private val historyBuffer: LogRingBuffer? = null,  // オプショナル
) {
    suspend fun connect(events: Flow<LogEvent>) {
        // recordTo は webSocket ブロック外 → 切断中もバッファに蓄積
        val recorded = if (historyBuffer != null) {
            events.recordTo(historyBuffer)
        } else {
            events
        }

        client.webSocket(config.wsUrl) {
            // withHistory は webSocket ブロック内 → 再接続のたびにスナップショット
            val pipeline = if (historyBuffer != null) {
                recorded.withHistory(historyBuffer)
            } else {
                recorded
            }

            pipeline
                .buffer(config.bufferSize)
                .map { event -> json.encodeToString(event) }
                .retryWithBackoff(config.reconnect)
                .collect { jsonString -> send(Frame.Text(jsonString)) }
        }
    }
}
```

**ポイント**: `recordTo` と `withHistory` の配置が異なる。

```text
recordTo: webSocket ブロック外
  → WebSocket が切断されていてもイベントをバッファに蓄積し続ける

withHistory: webSocket ブロック内
  → 接続のたびに最新のスナップショットを取得して送信
```

---

## 送信フロー

```text
1. クライアント接続
   ┌──────────────────────────────┐
   │  snapshot() で 1000件取得     │
   │  → historical: true で送信   │
   │  [evt-001] [evt-002] ...     │
   └──────────────────────────────┘

2. 履歴送信完了

3. ライブストリーミング開始
   ┌──────────────────────────────┐
   │  新規イベントを逐次送信      │
   │  → historical: false で送信  │
   │  [evt-1001] [evt-1002] ...   │
   └──────────────────────────────┘
```

---

## テスト

```kotlin
@Test
fun `withHistory prepends historical events before live events`() = runTest {
    val buffer = LogRingBuffer(capacity = 10)
    buffer.add(event("old-1"))
    buffer.add(event("old-2"))

    val live = flowOf(event("new-1"), event("new-2"))
    val combined = live.withHistory(buffer).toList()

    assertEquals(4, combined.size)
    assertEquals("old-1", combined[0].id)   // 履歴が先
    assertEquals("old-2", combined[1].id)
    assertEquals("new-1", combined[2].id)   // ライブが後
    assertEquals("new-2", combined[3].id)

    assertEquals("true", combined[0].metadata["historical"])
    assertEquals("false", combined[2].metadata["historical"])
}
```

NestJS (Jest) での同等テスト:

```typescript
it('should prepend history before live events', async () => {
  const history = [event('old-1'), event('old-2')];
  const live$ = from([event('new-1'), event('new-2')]);

  const result = await lastValueFrom(
    withHistory(live$, history).pipe(toArray()),
  );

  expect(result).toHaveLength(4);
  expect(result[0].id).toBe('old-1');
  expect(result[0].metadata.historical).toBe('true');
  expect(result[2].id).toBe('new-1');
  expect(result[2].metadata.historical).toBe('false');
});
```
