# テスト — Jest/NestJS Testing と Kotlin Test の対比

## 概要

KMP プロジェクトにおけるテストの構成と実行方法。
NestJS では Jest + Testing Module を使うが、
Kotlin では kotlin-test + kotlinx-coroutines-test で同等のことを実現する。

---

## テストフレームワークの対比

| 観点 | NestJS (Jest) | Kotlin (kotlin-test) |
| --- | --- | --- |
| テストランナー | Jest | JUnit (JVM) / Native Runner |
| アサーション | `expect(x).toBe(y)` | `assertEquals(y, x)` |
| テスト関数 | `it('...', () => {})` | `@Test fun \`...\`() {}` |
| セットアップ | `beforeEach(() => {})` | `@BeforeTest fun setup() {}` |
| モック | `jest.mock()` / `jest.fn()` | 手動実装 / interface 差し替え |
| 非同期テスト | `async/await` | `runTest { }` |

---

## ディレクトリ構造

### NestJS — テストファイルはソースと同居

```
src/
  users/
    users.service.ts
    users.service.spec.ts     ← テストが隣に置かれる
    users.controller.ts
    users.controller.spec.ts
  app.module.ts
test/
  app.e2e-spec.ts             ← E2E テストは別ディレクトリ
```

### KMP — テストは専用ディレクトリに分離

```
src/
  commonMain/kotlin/vigil/     ← 全プラットフォーム共通コード
  commonTest/kotlin/vigil/     ← 共通テスト（JVM + Native 両方で実行）
  jvmMain/kotlin/vigil/        ← JVM 固有コード
  jvmTest/kotlin/vigil/        ← JVM 固有テスト
```

**なぜ分離するのか:**

NestJS はシングルプラットフォーム (Node.js) なので `.spec.ts` をソースの隣に置ける。
KMP は複数プラットフォームがあるため、共通テスト (`commonTest`) と
プラットフォーム固有テスト (`jvmTest` 等) を分ける必要がある。

`commonTest` のテストは **全ターゲット** で実行される。
`jvmTest` のテストは JVM でのみ実行される。

---

## テストの書き方

### 基本的なアサーション

**Jest (NestJS):**

```typescript
describe('LogLevel', () => {
  it('should have 6 levels', () => {
    const levels = Object.values(LogLevel);
    expect(levels).toHaveLength(6);
  });

  it('TRACE should be less than DEBUG', () => {
    expect(LogLevel.TRACE).toBeLessThan(LogLevel.DEBUG);
  });
});
```

**kotlin-test:**

```kotlin
class LogLevelTest {
    @Test
    fun `enum has exactly six levels in expected order`() {
        val levels = LogLevel.entries
        assertEquals(6, levels.size)
    }

    @Test
    fun `ordinal values are ascending for severity comparison`() {
        assertTrue(LogLevel.TRACE.ordinal < LogLevel.DEBUG.ordinal)
    }
}
```

**対比ポイント:**

| Jest | kotlin-test | 説明 |
| --- | --- | --- |
| `describe('...')` | `class ...Test` | テストのグルーピング |
| `it('should ...')` | `@Test fun \`should ...\`()` | 個別テスト。Kotlin はバッククォートで自然文を関数名に |
| `expect(x).toBe(y)` | `assertEquals(y, x)` | 引数の順序が逆（期待値が第1引数） |
| `expect(x).toBeTruthy()` | `assertTrue(x)` | 真偽値アサーション |
| `expect(x).not.toBe(y)` | `assertNotEquals(y, x)` | 否定アサーション |

---

## data class のテスト

### NestJS — DTO のテスト

```typescript
describe('LogEventDto', () => {
  it('should create with default metadata', () => {
    const event = new LogEventDto({ id: '1', /* ... */ });
    expect(event.metadata).toEqual({});
  });

  it('should serialize to JSON', () => {
    const event = new LogEventDto({ /* ... */ });
    const json = JSON.stringify(instanceToPlain(event));
    const parsed = plainToInstance(LogEventDto, JSON.parse(json));
    expect(parsed).toEqual(event);
  });
});
```

### Kotlin — data class のテスト

```kotlin
class LogEventTest {
    @Test
    fun `metadata defaults to empty map`() {
        val event = LogEvent(id = "1", /* ... */)
        assertEquals(emptyMap(), event.metadata)
    }

    @Test
    fun `serializes to JSON and back (round-trip)`() {
        val event = LogEvent(/* ... */)
        val json = Json.encodeToString(event)
        val deserialized = Json.decodeFromString<LogEvent>(json)
        assertEquals(event, deserialized)
    }
}
```

**Kotlin の data class は `equals()` を自動生成するため、
`JSON.stringify` → `JSON.parse` のような変換なしに直接比較できる。**

---

## 非同期テストと Flow

### Jest — async/await + Observable

```typescript
it('should filter events', async () => {
  const events$ = of(
    { level: 'INFO', message: 'ok' },
    { level: 'ERROR', message: 'fail' },
  );

  const result = await lastValueFrom(
    events$.pipe(
      filter(e => e.level === 'ERROR'),
      toArray(),
    )
  );

  expect(result).toHaveLength(1);
});
```

### Kotlin — runTest + Flow

```kotlin
@Test
fun `filterByLevel WARN passes WARN ERROR FATAL`() = runTest {
    val events = flowOf(
        event(level = LogLevel.INFO),
        event(level = LogLevel.WARN),
        event(level = LogLevel.ERROR),
    )

    val result = events.filterByLevel(LogLevel.WARN).toList()

    assertEquals(2, result.size)
}
```

**対比ポイント:**

| Jest | kotlin-test | 説明 |
| --- | --- | --- |
| `async () => {}` | `= runTest { }` | 非同期テストブロック |
| `lastValueFrom(obs$)` | `flow.toList()` | ストリームを値に変換 |
| `of(a, b, c)` | `flowOf(a, b, c)` | テスト用ストリーム生成 |
| `.pipe(filter(...))` | `.filterByLevel(...)` | フィルタ適用 |

---

## runTest と仮想時間

### 問題: delay を含むテスト

指数バックオフのテストでは `delay(1.seconds)`, `delay(2.seconds)` が呼ばれる。
実際に待つとテストが遅くなる。

### Jest — Fake Timers

```typescript
beforeEach(() => {
  jest.useFakeTimers();
});

it('should retry with backoff', async () => {
  const promise = retryWithBackoff(failingFn);
  jest.advanceTimersByTime(1000);  // 1秒進める
  jest.advanceTimersByTime(2000);  // さらに2秒進める
  await promise;
});
```

### Kotlin — runTest の自動時間スキップ

```kotlin
@Test
fun `retryWithBackoff retries on failure then succeeds`() = runTest {
    // runTest が delay() を自動でスキップ → テストは即座に完了
    val result = failingFlow
        .retryWithBackoff(config)
        .toList()

    assertEquals(listOf(42), result)
}
```

`runTest` は `delay()` を仮想的に進めるため、
Jest の `jest.advanceTimersByTime()` を手動で呼ぶ必要がない。
テスト内の全ての `delay()` は即座にスキップされる。

| Jest | kotlin runTest | 説明 |
| --- | --- | --- |
| `jest.useFakeTimers()` | 不要（runTest がデフォルトで仮想時間） | 有効化 |
| `jest.advanceTimersByTime(ms)` | 自動 | 時間を進める |
| `jest.useRealTimers()` | `runBlocking { }` を使う | 実時間に戻す |

---

## モックとテストダブル

### Jest — jest.mock()

```typescript
// モジュール全体をモック
jest.mock('./websocket.service');

// 個別関数をモック
const mockSend = jest.fn();
```

### Kotlin — interface 差し替え / internal 可視性

Kotlin にはフレームワーク組み込みのモック機構がない。
代わりに2つの主要なアプローチがある:

**1. private → internal にしてテストから直接呼ぶ:**

```kotlin
// プロダクションコード
internal fun calculateDelay(config: BackoffConfig, attempt: Long): Duration { ... }

// テストコード（同一モジュール内なので internal にアクセス可能）
@Test
fun `attempt 0 returns initialDelay`() {
    val delay = calculateDelay(config, attempt = 0)
    assertEquals(1000.milliseconds, delay)
}
```

**2. テスト用の設定で副作用を排除:**

```kotlin
// ジッターを 0 にすることで Random を排除 → 決定論的テスト
val noJitterConfig = BackoffConfig(jitterFactor = 0.0)
```

| Jest | Kotlin | 説明 |
| --- | --- | --- |
| `jest.mock()` | interface + テスト実装 | モジュールレベルモック |
| `jest.fn()` | ラムダ / MutableList で記録 | 関数レベルモック |
| `jest.spyOn()` | `internal` 可視性で直接テスト | 内部実装のテスト |

---

## テストの実行

```bash
# 全テスト実行（commonTest + jvmTest + mingwX64Test）
./gradlew allTests

# JVM テストのみ（commonTest の JVM 実行 + jvmTest）
./gradlew jvmTest

# 特定テストクラスのみ
./gradlew jvmTest --tests "vigil.retry.ExponentialBackoffTest"

# テスト結果レポート
open build/reports/tests/jvmTest/index.html
```

### NestJS との対比

```bash
# NestJS
npm test                         # Jest 全テスト
npm test -- --grep "LogLevel"    # パターンマッチ
npm run test:e2e                 # E2E テスト

# Kotlin
./gradlew jvmTest                # 全テスト
./gradlew jvmTest --tests "*.LogLevelTest"  # パターンマッチ
```

---

## 補足: internal 可視性

Kotlin の `internal` は「同一モジュール内で可視」という意味。
テストソースセット (`commonTest`, `jvmTest`) はメインソースセットと同一モジュールなので、
`internal` な関数やクラスにアクセスできる。

```kotlin
// src/commonMain/ — プロダクションコード
internal fun calculateDelay(...): Duration { ... }
//       ↑ モジュール外からは見えない

// src/commonTest/ — テストコード（同一モジュール）
@Test fun test() {
    calculateDelay(...)  // ← アクセス可能
}
```

TypeScript には直接の対応概念はないが、イメージとしては:

```typescript
// TypeScript の場合、テスト用に export するしかない
export function calculateDelay(...) { ... }

// または @VisibleForTesting アノテーション的な規約
/** @internal */
export function calculateDelay(...) { ... }
```

Kotlin の `internal` は**コンパイラが強制する**ため、
JSDoc の `@internal` のような「お願い」ベースではなく、
モジュール外から呼ぶとコンパイルエラーになる。

---

## 補足: テストでのバッククォート関数名

Kotlin ではテスト関数名にバッククォートを使って自然言語の文を書ける:

```kotlin
@Test
fun `filterByLevel WARN passes WARN ERROR FATAL`() { ... }
```

これは Jest の `it('filterByLevel WARN passes WARN ERROR FATAL', ...)` に相当する。
通常のコードでは使わないが、テストでは可読性のために広く使われている。

---

## 補足: SharedFlow のテストと UnconfinedTestDispatcher

### SharedFlow (Hot Flow) とは

`SharedFlow` は Kotlin Coroutines の「熱い (Hot) Flow」。
NestJS でいうと `EventEmitter` や RxJS の `Subject` に相当する。

```kotlin
// Kotlin: SharedFlow で複数の受信者にイベントを放送
val _events = MutableSharedFlow<LogEvent>(extraBufferCapacity = 64)
val events: Flow<LogEvent> = _events.asSharedFlow()

// 送信側
_events.emit(event)

// 受信側（複数可）
events.collect { event -> println(event) }
```

```typescript
// NestJS: EventEmitter2 で同等のことをする
eventEmitter.emit('log.event', event);

// 受信側（複数可）
eventEmitter.on('log.event', (event) => console.log(event));
```

| 観点 | SharedFlow | RxJS Subject | EventEmitter |
| --- | --- | --- | --- |
| 複数リスナー | `collect` を複数起動 | `.subscribe()` を複数 | `.on()` を複数 |
| 値の保持 | デフォルトで保持しない（replay=0） | 保持しない | 保持しない |
| バッファ | `extraBufferCapacity` で設定 | なし | なし |

**重要:** `replay = 0`（デフォルト）の場合、`emit` した瞬間に誰も `collect` していなければ値は消失する。

### テストで発生する問題

```kotlin
// このテストは失敗する可能性がある
@Test
fun `broken test`() = runTest {
    val context = DefaultPluginContext(config)

    val job = launch {                    // ← StandardTestDispatcher: キューに入るだけ
        val event = context.events.first() // ← まだ実行されていない
    }

    context.publish("hello")              // ← emit するが、collector がまだ待機していない
    job.join()                            // ← 永遠に完了しない or 値を取りこぼす
}
```

`runTest` のデフォルトディスパッチャ (`StandardTestDispatcher`) は、
`launch` されたコルーチンを即座に実行せず、キューに溜める。
そのため `publish()` が呼ばれた時点で collector がまだ起動しておらず、
SharedFlow に emit された値が誰にも届かない。

### UnconfinedTestDispatcher で解決

```kotlin
@Test
fun `working test`() = runTest {
    val context = DefaultPluginContext(config)

    val job = launch(UnconfinedTestDispatcher(testScheduler)) { // ← 即座に実行開始
        val event = context.events.first()                       // ← すぐに待機状態に入る
    }

    context.publish("hello")  // ← collector が待機中なので確実に届く
    job.join()                // ← 正常に完了
}
```

`UnconfinedTestDispatcher` は `launch` した瞬間にコルーチンを実行する。
これにより collector が先に待機状態に入り、その後の `emit` を確実にキャッチできる。

### 2 つのテストディスパッチャの対比

| 観点 | StandardTestDispatcher | UnconfinedTestDispatcher |
| --- | --- | --- |
| 実行タイミング | キューに溜める（`runCurrent()` で明示的に進める） | `launch` した瞬間に即座に実行 |
| NestJS 対比 | `jest.useFakeTimers()` + `jest.runAllTicks()` | デフォルトの `async/await` |
| 用途 | 実行順序を厳密に制御したいテスト | SharedFlow など即時性が必要なテスト |
| デフォルト | `runTest { }` のデフォルト | 明示的に指定が必要 |

```text
StandardTestDispatcher:
  launch { collect }  →  キューに入る  →  publish()  →  emit、でも collector 未起動  →  値が消失
                                                         ↓
                                                    runCurrent() で初めて collector 起動

UnconfinedTestDispatcher:
  launch { collect }  →  即座に collector 起動・待機  →  publish()  →  emit → collector がキャッチ
```

Jest では `async/await` がデフォルトで「即座に」動くため、この問題は起きにくい。
Kotlin のテストでは「仮想時間の制御」と「即時実行」を明示的に使い分ける必要がある。
