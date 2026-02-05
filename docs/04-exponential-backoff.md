# 指数バックオフ再接続 — RxJS retryWhen と Kotlin Flow

## 概要

WebSocket 切断時に即座に再接続するとサーバーに負荷がかかる。
指数バックオフで徐々に待機時間を延ばし、ジッターで複数クライアントの同時再接続を避ける。

---

## RxJS の pipe() + retryWhen() とは

### pipe() — データの流れに処理を挟む

NestJS で WebSocket や HTTP 通信を扱うとき、`Observable`（時間とともに流れてくるデータの列）を使う。
`pipe()` は「データの流れに処理を挟む」メソッド:

```typescript
// NestJS でよく見るパターン
this.httpService.get('/users').pipe(
  map(response => response.data),   // レスポンスから data を取り出す
  catchError(err => of([])),        // エラー時は空配列を返す
);
```

### retryWhen() — エラー時の再試行ロジック

`retryWhen()` は「エラーが起きたとき、どう再試行するか」を定義する:

```typescript
// エラーが起きたら 1秒待って再試行
source$.pipe(
  retryWhen(errors => errors.pipe(
    delay(1000)
  ))
);
```

### 使用シーン

**WebSocket の自動再接続:**

```typescript
@WebSocketGateway()
export class EventsGateway {
  connectToExternalService() {
    webSocket('ws://external-service/events').pipe(
      retryWhen(errors => errors.pipe(
        tap(err => console.log('接続切断、再接続します...')),
        delay(3000),
      ))
    ).subscribe(event => this.handleEvent(event));
  }
}
```

**HTTP リクエストのリトライ:**

```typescript
this.httpService.get('/api/unstable-endpoint').pipe(
  retryWhen(errors => errors.pipe(
    scan((retryCount, err) => {
      if (retryCount >= 3) throw err;  // 3回失敗したら諦める
      return retryCount + 1;
    }, 0),
    delay(1000),
  ))
);
```

---

## なぜ指数バックオフが必要か

即座に再接続すると Thundering Herd 問題が起きる:

```
クライアント A: 切断 → 即再接続
クライアント B: 切断 → 即再接続
クライアント C: 切断 → 即再接続
↓
サーバー: 同時に大量の接続要求 → パンク
```

指数バックオフ + ジッターで分散:

```
クライアント A: 切断 → 1.1秒後に再接続
クライアント B: 切断 → 0.9秒後に再接続
クライアント C: 切断 → 1.2秒後に再接続
↓
サーバー: 接続要求が分散 → 安定
```

---

## NestJS (RxJS) での実装

```typescript
import { retryWhen, scan, delayWhen, timer } from 'rxjs';

source$.pipe(
  retryWhen(errors =>
    errors.pipe(
      scan((count) => count + 1, 0),
      delayWhen(count => {
        const delayMs = Math.min(1000 * Math.pow(2, count), 30000);
        const jitter = delayMs * 0.1 * (Math.random() * 2 - 1);
        return timer(delayMs + jitter);
      }),
    )
  )
);
```

---

## Kotlin Flow での実装

```kotlin
events()
    .retryWithBackoff(config.reconnect)
    .collect { event -> ... }
```

`retryWithBackoff` は `Flow` の拡張関数として定義:

```kotlin
fun <T> Flow<T>.retryWithBackoff(config: BackoffConfig): Flow<T> =
    retryWhen { cause, attempt ->
        if (attempt >= config.maxRetries) {
            false
        } else {
            delay(calculateDelay(config, attempt))
            true
        }
    }
```

---

## 対比表

| 観点 | RxJS (NestJS) | Kotlin Flow |
| --- | --- | --- |
| リトライ演算子 | `retryWhen(errors => ...)` | `retryWhen { cause, attempt -> }` |
| 試行回数の管理 | `scan()` で累積 | `attempt` パラメータで自動提供 |
| 待機 | `delayWhen(timer(...))` | `delay(Duration)` |
| 条件終了 | `takeWhile()` など | `false` を返す |

---

## 指数バックオフの計算

```
delay = min(initialDelay * multiplier^attempt, maxDelay)
```

例 (initialDelay=1s, multiplier=2, maxDelay=30s):

| attempt | 計算 | 結果 |
| --- | --- | --- |
| 0 | 1 * 2^0 | 1s |
| 1 | 1 * 2^1 | 2s |
| 2 | 1 * 2^2 | 4s |
| 3 | 1 * 2^3 | 8s |
| 4 | 1 * 2^4 | 16s |
| 5 | 1 * 2^5 = 32 → cap | 30s |
| 6+ | cap | 30s |

---

## ジッター (Jitter)

待機時間を ±10% 揺らすことで同時接続を分散する:

```kotlin
val jitterRange = (cappedMs * config.jitterFactor).toLong()
val jitteredMs = cappedMs + Random.nextLong(-jitterRange, jitterRange + 1)
```

例 (delay=8s, jitterFactor=0.1):
- 範囲: 8000 ± 800ms
- 実際の待機: 7200ms 〜 8800ms のランダム

---

## BackoffConfig

```kotlin
data class BackoffConfig(
    val initialDelay: Duration = 1.seconds,   // 初回待機
    val maxDelay: Duration = 30.seconds,      // 上限
    val multiplier: Double = 2.0,             // 倍率
    val jitterFactor: Double = 0.1,           // ±10%
    val maxRetries: Int = Int.MAX_VALUE,      // 無限リトライ
)
```

---

## 補足: Observable と Flow

### Observable (RxJS) とは

RxJS の `Observable` は「時間とともに流れてくるデータの列」を表す。
配列が「今ここにある複数のデータ」なら、Observable は「これから届く複数のデータ」。

```typescript
// 配列: 今すぐ全データがある
const users = [user1, user2, user3];
users.forEach(u => console.log(u));  // 即座に3回出力

// Observable: データが時間差で届く
const users$ = fetchUsersStream();
users$.subscribe(u => console.log(u));  // user1...（1秒後）user2...（2秒後）user3
```

WebSocket やイベントリスナーなど「いつ届くかわからないデータ」を扱うのに適している。

### Flow (Kotlin) とは

Kotlin の `Flow` は RxJS の `Observable` に相当する。
「時間とともに流れてくるデータの列」を非同期で処理する。

```kotlin
// Flow: データが時間差で届く
val users: Flow<User> = fetchUsersStream()
users.collect { u -> println(u) }  // user1...（1秒後）user2...（2秒後）user3
```

| RxJS | Kotlin Flow | 説明 |
| --- | --- | --- |
| `Observable<T>` | `Flow<T>` | データストリームの型 |
| `.subscribe(callback)` | `.collect { callback }` | データを受け取って処理 |
| `.pipe(operator)` | `.operator()` | 演算子を適用 |

---

## 補足: 拡張関数とは

Kotlin の「拡張関数」は、既存のクラスにメソッドを追加する機能。
TypeScript には無い概念だが、イメージとしては prototype 拡張に近い（ただしより安全）。

```kotlin
// Flow に retryWithBackoff メソッドを「追加」する
fun <T> Flow<T>.retryWithBackoff(config: BackoffConfig): Flow<T> = ...

// 使う側
events().retryWithBackoff(config)  // あたかも Flow の標準メソッドのように呼べる
```

TypeScript で同じことをやるなら:

```typescript
// TypeScript: prototype 拡張（非推奨だが概念として）
declare global {
  interface Observable<T> {
    retryWithBackoff(config: BackoffConfig): Observable<T>;
  }
}

Observable.prototype.retryWithBackoff = function(config) { ... };

// 使う側
events().retryWithBackoff(config);
```

Kotlin の拡張関数は:
- 元のクラスを変更しない（安全）
- コンパイル時に静的に解決される（パフォーマンスに影響なし）
- IDE の補完が効く
