# WebSocket パイプライン — NestJS Gateway のクライアント版

## 概要

ログ監視で取得した `Flow<LogEvent>` を WebSocket でサーバーに送信するパイプライン。
NestJS でいうと `@WebSocketGateway()` のクライアント版に相当する。

---

## NestJS の WebSocket 構成（参考）

NestJS ではサーバー側で WebSocket Gateway を作る:

```typescript
// サーバー側: ログを受け取る
@WebSocketGateway()
export class LogGateway {
  @SubscribeMessage('log')
  handleLog(@MessageBody() event: LogEventDto) {
    console.log('Received:', event);
  }
}
```

今回作るのは **クライアント側**:

```text
[ファイル監視] → Flow<LogEvent> → [フィルタ] → [JSON変換] → [WebSocket送信] → サーバー
```

---

## パイプラインの構成

```kotlin
class WebSocketPipeline(config: MonitorConfig, client: HttpClient) {
    suspend fun connect(events: Flow<LogEvent>) {
        client.webSocket(config.wsUrl) {
            events
                .buffer(config.bufferSize)           // 1. バックプレッシャー
                .map { Json.encodeToString(it) }     // 2. JSON 変換
                .retryWithBackoff(config.reconnect)  // 3. 自動再接続
                .collect { json ->
                    send(Frame.Text(json))           // 4. WebSocket 送信
                }
        }
    }
}
```

### 各ステップの説明

| ステップ | NestJS 対比 | 説明 |
| --- | --- | --- |
| `buffer()` | RxJS `bufferCount` | 消費が遅いときにデータを溜める |
| `map { Json.encodeToString }` | `class-transformer` | LogEvent → JSON 文字列 |
| `retryWithBackoff()` | `retryWhen(exponential)` | 切断時に自動再接続 |
| `send(Frame.Text)` | `socket.emit()` | WebSocket フレームを送信 |

---

## バックプレッシャーとは

データの生産速度 > 消費速度 のとき、どう対処するかの問題。

```text
[ファイル監視] 1000件/秒 → [WebSocket] 100件/秒しか送れない
                              ↓
                         差分 900件 をどうする？
```

`buffer(256)` は「256件まで溜めて、それ以上は待機」という戦略。
NestJS (RxJS) の `bufferCount()` や `bufferTime()` と同じ概念。

---

## LogFilter — フィルタリング演算子

パイプラインに挟んで不要なログを除外する。
NestJS の Pipe (ValidationPipe 等) に近いが、データ変換ではなくストリームフィルタ。

```kotlin
// ERROR 以上のログのみ送信
events
    .filterByLevel(LogLevel.ERROR)
    .collect { ... }

// 特定ソースのみ
events
    .filterBySource(Regex("app\\..*"))
    .collect { ... }

// 組み合わせ
events
    .filterByLevel(LogLevel.WARN)
    .filterBySource(Regex("payment\\..*"))
    .filterByMessage("timeout")
    .collect { ... }
```

### NestJS での対比

```typescript
// NestJS (RxJS)
events$.pipe(
  filter(e => e.level >= LogLevel.ERROR),
  filter(e => /app\..*/.test(e.source)),
);

// Kotlin Flow
events
    .filterByLevel(LogLevel.ERROR)
    .filterBySource(Regex("app\\..*"))
```

---

## Ktor Client とは

Kotlin 製の HTTP/WebSocket クライアントライブラリ。
NestJS でいう `@nestjs/axios` (HTTP) + `socket.io-client` (WebSocket) に相当。

```kotlin
// Ktor: WebSocket 接続
val client = HttpClient {
    install(WebSockets)
}

client.webSocket("ws://localhost:8080/logs") {
    send(Frame.Text("Hello"))
    val response = incoming.receive() as Frame.Text
    println(response.readText())
}
```

```typescript
// NestJS: socket.io-client での同等処理
const socket = io('ws://localhost:8080');
socket.emit('message', 'Hello');
socket.on('message', (data) => console.log(data));
```

| 観点 | Ktor Client | socket.io-client |
| --- | --- | --- |
| 接続方式 | 標準 WebSocket | Socket.IO プロトコル |
| フレーム送信 | `send(Frame.Text(...))` | `emit('event', data)` |
| フレーム受信 | `incoming.receive()` | `on('event', callback)` |

---

## 全体の流れ

```text
┌─────────────────┐
│  ファイル監視    │  ← PlatformLogMonitor (Step 6, 7 で実装)
│  (OS 依存)      │
└────────┬────────┘
         │ Flow<LogEvent>
         ▼
┌─────────────────┐
│  フィルタリング  │  ← LogFilter
│  (共通)         │
└────────┬────────┘
         │ Flow<LogEvent> (filtered)
         ▼
┌─────────────────┐
│  WebSocket 送信 │  ← WebSocketPipeline
│  (共通)         │
└────────┬────────┘
         │ JSON over WebSocket
         ▼
┌─────────────────┐
│  サーバー       │  ← NestJS @WebSocketGateway() など
└─────────────────┘
```
