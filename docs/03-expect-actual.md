# expect/actual — NestJS の DI とコンパイル時プラットフォーム解決

## 概要

KMP の `expect/actual` は、同じインターフェースに対してプラットフォームごとに
異なる実装を提供する仕組み。NestJS の DI (Dependency Injection) と目的は同じだが、
解決タイミングが根本的に異なる。

---

## NestJS の DI との対比

### NestJS: 実行時に解決

```typescript
// 1. 抽象クラスを定義
export abstract class LogMonitor {
  abstract events(): Observable<LogEvent>;
  abstract start(): void;
  abstract stop(): void;
}

// 2. 環境ごとの実装
@Injectable()
export class WindowsLogMonitor extends LogMonitor { ... }

@Injectable()
export class LinuxLogMonitor extends LogMonitor { ... }

// 3. Module で DI 登録（実行時に解決）
@Module({
  providers: [
    {
      provide: LogMonitor,
      useClass: process.platform === 'win32'
        ? WindowsLogMonitor
        : LinuxLogMonitor,
    },
  ],
})
export class AppModule {}
```

### KMP: コンパイル時に解決

```kotlin
// 1. commonMain で expect 宣言（抽象）
expect class PlatformLogMonitor(config: MonitorConfig) {
    fun events(): Flow<LogEvent>
    fun start(): Job
    fun stop()
}

// 2. jvmMain で actual 実装
actual class PlatformLogMonitor actual constructor(
    private val config: MonitorConfig,
) {
    actual fun events(): Flow<LogEvent> = ...  // Java NIO WatchService
    actual fun start(): Job = ...
    actual fun stop() { ... }
}

// 3. mingwX64Main で actual 実装
actual class PlatformLogMonitor actual constructor(
    private val config: MonitorConfig,
) {
    actual fun events(): Flow<LogEvent> = ...  // Win32 API
    actual fun start(): Job = ...
    actual fun stop() { ... }
}
```

### 対比表

| 観点 | NestJS (DI) | KMP (expect/actual) |
| --- | --- | --- |
| 解決タイミング | 実行時（DI コンテナ） | コンパイル時（ビルドターゲット） |
| 切り替え方法 | `useClass` / `useFactory` | ソースセットにファイルを配置 |
| 型安全性 | `any` にキャストされるリスクあり | コンパイルエラーで検出 |
| actual 未実装時 | 実行時に `Error: Cannot find module` | コンパイルエラー |
| テスト差し替え | `overrideProvider()` | テスト用ソースセットで actual を提供 |

---

## MonitorConfig

NestJS の `ConfigService` で取得する設定値に相当。

### NestJS

```typescript
// NestJS: 実行時に .env から取得。型は string。
const wsUrl = configService.get<string>('WS_URL');
const bufferSize = configService.get<number>('BUFFER_SIZE') ?? 256;
// → 型が合わない場合は実行時エラー
```

### Kotlin

```kotlin
// Kotlin: コンパイル時に型が確定。デフォルト値も型安全。
data class MonitorConfig(
    val watchPath: String,
    val wsUrl: String,
    val reconnect: BackoffConfig = BackoffConfig(),
    val bufferSize: Int = 256,
)
// → 型が合わない場合はコンパイルエラー
```

| 観点 | NestJS ConfigService | Kotlin data class |
| --- | --- | --- |
| 型安全性 | `get<string>()` でキャスト（実行時） | コンパイル時に保証 |
| デフォルト値 | `?? fallback` で手動設定 | コンストラクタ引数のデフォルト値 |
| バリデーション | `class-validator` で実行時チェック | 型システムで保証 + `init {}` で追加検証可 |

---

## BackoffConfig

再接続時の指数バックオフ設定。NestJS で RxJS の `retryWhen` に渡す設定に相当。

```kotlin
data class BackoffConfig(
    val initialDelay: Duration = 1.seconds,   // 初回待機
    val maxDelay: Duration = 30.seconds,      // 最大待機
    val multiplier: Double = 2.0,             // 倍率
    val jitterFactor: Double = 0.1,           // ±10% のランダム揺れ
    val maxRetries: Int = Int.MAX_VALUE,       // 最大リトライ回数
)
```

`Duration` は Kotlin 標準の型で、`1.seconds` のように書ける。
NestJS では `1000` (ミリ秒) や `'1s'` (文字列) で表現するが、Kotlin は型付きの値。
