# プラグインシステム — NestJS の Module/Provider ライフサイクルとの対比

## 概要

Vigil にプラグイン（拡張機能）を差し込める仕組み。
NestJS では `@Module()` と Provider のライフサイクルフック (`OnModuleInit`, `OnModuleDestroy`) で
アプリケーションの起動・停止時の処理を定義するが、
Vigil では `VigilPlugin` インターフェースと `VigilEngine` で同等のことを実現する。

---

## NestJS のライフサイクルとの対比

### NestJS: Module + Provider + ライフサイクルフック

```typescript
// 1. Provider としてサービスを定義
@Injectable()
export class ContextManagerService
  implements OnModuleInit, OnModuleDestroy
{
  onModuleInit() {
    console.log('初期化: Git 監視を開始');
  }

  @Cron('*/30 * * * * *')  // 30秒ごとに実行
  handleCron() {
    console.log('定期実行: スナップショット更新');
  }

  onModuleDestroy() {
    console.log('終了: クリーンアップ');
  }
}

// 2. Module に登録
@Module({
  providers: [ContextManagerService],
})
export class ContextManagerModule {}

// 3. AppModule で import
@Module({
  imports: [ContextManagerModule],
})
export class AppModule {}

// 4. アプリ起動
const app = await NestFactory.create(AppModule);
await app.listen(3000);
```

### Kotlin: VigilPlugin + VigilEngine

```kotlin
// 1. VigilPlugin を実装
class ContextManagerPlugin : VigilPlugin {
    override val name = "context-manager"

    override suspend fun onInitialize(context: PluginContext) {
        println("初期化: Git 監視を開始")
    }

    override suspend fun onHeartbeat(context: PluginContext) {
        println("定期実行: スナップショット更新")
    }

    override suspend fun onShutdown() {
        println("終了: クリーンアップ")
    }
}

// 2. VigilEngine に登録して起動
fun main() = runBlocking {
    val engine = VigilEngine(heartbeatInterval = 30.seconds)
    engine.register(ContextManagerPlugin())

    coroutineScope {
        engine.start(this, context)
    }
}
```

### 対比表

| 観点 | NestJS | Vigil (Kotlin) |
| --- | --- | --- |
| プラグイン定義 | `@Injectable()` + `implements OnModuleInit` | `class ... : VigilPlugin` |
| 登録方式 | `@Module({ providers: [...] })` | `engine.register(plugin)` |
| 初期化フック | `onModuleInit()` | `onInitialize(context)` |
| 定期実行 | `@Cron()` デコレータ | `onHeartbeat(context)` |
| 終了フック | `onModuleDestroy()` | `onShutdown()` |
| アプリ起動 | `NestFactory.create(AppModule)` | `VigilEngine().start(scope, context)` |
| DI コンテナ | NestJS IoC コンテナが自動注入 | `PluginContext` を明示的に渡す |

---

## VigilPlugin インターフェース

`src/commonMain/kotlin/vigil/plugin/VigilPlugin.kt`:

```kotlin
interface VigilPlugin {
    val name: String
    suspend fun onInitialize(context: PluginContext)
    suspend fun onHeartbeat(context: PluginContext)
    suspend fun onShutdown()
}
```

NestJS では複数のライフサイクルインターフェース (`OnModuleInit`, `OnApplicationBootstrap` 等) を
組み合わせるが、Vigil では `VigilPlugin` 1つに集約している。

| VigilPlugin | NestJS ライフサイクル | いつ呼ばれるか |
| --- | --- | --- |
| `onInitialize` | `OnModuleInit` | エンジン起動時、1回だけ |
| `onHeartbeat` | `@Cron()` / `@Interval()` | 一定間隔で繰り返し |
| `onShutdown` | `OnModuleDestroy` | エンジン停止時、1回だけ |

### なぜ全メソッドが suspend か

NestJS のライフサイクルフックは `async` にできる:

```typescript
async onModuleInit() {
    await this.loadInitialData();  // 非同期OK
}
```

Kotlin でも同様に、初期化や定期処理で非同期操作（ファイルI/O、ネットワーク等）が
必要になるため、全フックを `suspend` にしている。

---

## PluginContext — プラグインが使える機能

`src/commonMain/kotlin/vigil/plugin/PluginContext.kt`:

```kotlin
interface PluginContext {
    val config: MonitorConfig
    suspend fun publish(message: String)
}
```

NestJS では DI コンテナが依存を自動注入するが、
Vigil では `PluginContext` をフックの引数として明示的に渡す。

| PluginContext | NestJS 対比 | 説明 |
| --- | --- | --- |
| `config` | `ConfigService` | 設定値へのアクセス |
| `publish(message)` | `EventEmitter2.emit()` / `socket.emit()` | 既存 WebSocket パイプラインへの送信 |

### NestJS との設計差分

```typescript
// NestJS: DI で自動注入（暗黙的）
@Injectable()
export class MyService {
  constructor(
    private config: ConfigService,        // 自動注入
    private eventEmitter: EventEmitter2,  // 自動注入
  ) {}
}
```

```kotlin
// Vigil: 引数で明示的に渡す
override suspend fun onInitialize(context: PluginContext) {
    val wsUrl = context.config.wsUrl        // 明示的にアクセス
    context.publish("Hello from plugin")     // 明示的に呼び出し
}
```

DI コンテナがない分シンプルだが、プラグインが使える機能は `PluginContext` に限定される。
必要に応じて `PluginContext` にメソッドを追加していく設計。

---

## VigilEngine — ライフサイクル管理

`src/commonMain/kotlin/vigil/engine/VigilEngine.kt`:

```kotlin
class VigilEngine(
    private val heartbeatInterval: Duration = 30.seconds,
) {
    private val plugins = mutableListOf<VigilPlugin>()
    private var heartbeatJob: Job? = null

    fun register(plugin: VigilPlugin): VigilEngine {
        plugins.add(plugin)
        return this  // メソッドチェーン対応
    }

    suspend fun start(scope: CoroutineScope, context: PluginContext) {
        plugins.forEach { it.onInitialize(context) }       // 全プラグインを初期化
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                plugins.forEach { it.onHeartbeat(context) } // 定期実行
            }
        }
    }

    suspend fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        plugins.forEach { it.onShutdown() }                 // 全プラグインを終了
    }
}
```

### NestJS の NestApplication との対比

```typescript
// NestJS
const app = await NestFactory.create(AppModule);  // Module ツリーを解決、Provider を初期化
await app.listen(3000);                           // サーバー起動
await app.close();                                // onModuleDestroy が呼ばれる
```

```kotlin
// Vigil
val engine = VigilEngine()
engine.register(ContextManagerPlugin())  // プラグイン登録
engine.start(scope, context)             // onInitialize → heartbeat ループ開始
engine.stop()                            // heartbeat 停止 → onShutdown
```

| 観点 | NestJS `NestApplication` | Vigil `VigilEngine` |
| --- | --- | --- |
| 生成 | `NestFactory.create()` | `VigilEngine()` コンストラクタ |
| プラグイン登録 | `@Module({ imports })` で宣言的 | `register()` で命令的 |
| 起動 | `app.listen()` | `engine.start(scope, context)` |
| 停止 | `app.close()` | `engine.stop()` |
| 定期実行 | `@nestjs/schedule` の `@Cron()` | `delay()` ループ |

---

## ライフサイクルの流れ

```text
engine.start()
    │
    ├── Plugin A: onInitialize()
    ├── Plugin B: onInitialize()
    │
    │   ┌── delay(30s) ──┐
    │   │                │
    │   ├── Plugin A: onHeartbeat()
    │   ├── Plugin B: onHeartbeat()
    │   │                │
    │   └── delay(30s) ──┘  ← ループ
    │
engine.stop()
    │
    ├── heartbeat ループをキャンセル
    ├── Plugin A: onShutdown()
    └── Plugin B: onShutdown()
```

NestJS では:

```text
NestFactory.create(AppModule)
    │
    ├── Provider A: onModuleInit()
    ├── Provider B: onModuleInit()
    │
    ├── Provider A: onApplicationBootstrap()
    ├── Provider B: onApplicationBootstrap()
    │
app.listen()  ← リクエスト受付開始
    │
app.close()
    │
    ├── Provider A: onModuleDestroy()
    └── Provider B: onModuleDestroy()
```

---

## 補足: メソッドチェーン

`register()` が `this` を返すため、NestJS の `app.use().use()` のように
プラグインを連続で登録できる:

```kotlin
engine
    .register(ContextManagerPlugin())
    .register(AnotherPlugin())
```

NestJS では Module の `imports` 配列で宣言的に行うことを、
Kotlin ではビルダーパターンで命令的に行う。

---

## 補足: コルーチンと heartbeat

NestJS の `@nestjs/schedule` は cron 式やインターバルで定期実行を宣言する:

```typescript
@Cron('*/30 * * * * *')
handleCron() { /* 30秒ごと */ }

@Interval(30000)
handleInterval() { /* 30秒ごと */ }
```

Kotlin ではコルーチンの `delay()` ループで同等の機能を実現する:

```kotlin
scope.launch {
    while (isActive) {           // キャンセルされるまで繰り返し
        delay(heartbeatInterval) // 次の heartbeat まで待機
        plugins.forEach { it.onHeartbeat(context) }
    }
}
```

`delay()` はスレッドをブロックしない（docs/04-exponential-backoff.md 参照）。
`isActive` はコルーチンがキャンセルされると `false` になり、ループが終了する。
