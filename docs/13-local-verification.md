# ローカル検証 — npm scripts から Gradle mainRun へ

## 概要

プラグインを実際に動かしてスナップショット生成を確認する方法。
NestJS では `npm run start:dev` や `ts-node` で手軽に検証できるが、
KMP マルチモジュール構成では **循環依存** の制約があるため、
プラグインモジュール内に検証用エントリポイントを配置する。

---

## NestJS での検証

### npm scripts + ts-node

```typescript
// scripts/verify-context.ts
import { ContextManagerService } from '../src/context-manager/context-manager.service';

async function main() {
  const service = new ContextManagerService();
  const snapshot = await service.buildSnapshot();
  console.log(snapshot);
}

main();
```

```bash
# 実行
npx ts-node scripts/verify-context.ts
# または package.json に登録
npm run verify:context
```

NestJS では `ts-node` がトップレベルから全モジュールにアクセスできるため、
どこからでもサービスをインスタンス化して検証できる。

---

## Kotlin (KMP) での検証

### 循環依存の制約

```text
root (vigil-core)
  └─ plugins:context-manager  ← root に依存

root が plugins:context-manager に依存すると循環する:
  root → plugins:context-manager → root → ...
```

NestJS のモノレポでは `ts-node` がパッケージ境界を越えてアクセスできるが、
Gradle のモジュール依存は厳密に一方向でなければならない。
そのため、検証用の `main()` は **プラグインモジュール内** に配置する。

### mainRun 設定

`plugins/context-manager/build.gradle.kts`:

```kotlin
kotlin {
    jvm()

    jvm {
        mainRun {
            mainClass.set("vigil.plugin.contextmanager.VerifySnapshotKt")
        }
    }
    // ...
}
```

### 対比表

| 観点 | NestJS | Kotlin (KMP) |
| --- | --- | --- |
| 実行方法 | `npx ts-node scripts/verify.ts` | `./gradlew :plugins:context-manager:jvmRun` |
| エントリポイント | scripts/ ディレクトリ | プラグインモジュールの `jvmMain` |
| モジュールアクセス | 制約なし（ts-node が解決） | 依存方向に従う（循環不可） |
| 設定 | `package.json` の `scripts` | `build.gradle.kts` の `mainRun` |
| 本番への影響 | scripts/ はビルドに含まれない | `jvmRun` タスク専用、jar には含まれない |

---

## 検証スクリプトの実装

`plugins/context-manager/src/jvmMain/kotlin/.../VerifySnapshot.kt`:

```kotlin
fun main() = runBlocking {
    val config = MonitorConfig(watchPath = ".", wsUrl = "ws://localhost:8080/logs")
    val context = DefaultPluginContext(config)
    val engine = VigilEngine(heartbeatInterval = 30.seconds)

    engine.register(ContextManagerPlugin())

    // publish された LogEvent をコンソールに出力
    launch {
        context.events.collect { event ->
            println("[${event.level}] ${event.source}: ${event.message}")
            val snapshot = event.metadata["snapshot"]
            if (snapshot != null) {
                println()
                println(snapshot)
            }
        }
    }

    // onInitialize を実行してスナップショットを生成
    engine.start(this, context)

    // ファイル出力の確認
    val file = java.io.File(".vigil/context_snapshot.md")
    if (file.exists()) {
        println(file.readText())
    }

    engine.stop()
}
```

### NestJS での同等実装

```typescript
// scripts/verify-context.ts
import { ContextManagerService } from '../src/context-manager/context-manager.service';
import { EventEmitter2 } from '@nestjs/event-emitter';
import * as fs from 'fs';

async function main() {
  const emitter = new EventEmitter2();
  const service = new ContextManagerService(emitter);

  // イベント監視
  emitter.on('context.snapshot', (snapshot: string) => {
    console.log('[INFO] context-manager:', snapshot.split('\n')[0]);
    console.log();
    console.log(snapshot);
  });

  // 初期化（スナップショット生成）
  await service.onModuleInit();

  // ファイル出力の確認
  const content = fs.readFileSync('.vigil/context_snapshot.md', 'utf-8');
  console.log(content);
}

main();
```

---

## 実行手順

### 1. 基本実行

```bash
./gradlew :plugins:context-manager:jvmRun
```

出力例（Issue 番号なしブランチ）:

```markdown
# 開発コンテキスト

## 現在の作業
- **ブランチ:** verify/context-manager-local-run

## このブランチでの変更内容
(main と同一、またはブランチ差分なし)

## 未コミットの変更
(clean)
```

### 2. GitHub Issue 連携の検証

Issue 番号を含むブランチで実行すると、Issue/PR 情報が自動取得される:

```bash
# Issue #25 に紐づくブランチで実行
git checkout -b feat/25-test-branch
./gradlew :plugins:context-manager:jvmRun
```

出力例:

```markdown
# 開発コンテキスト

## 現在の作業
- **ブランチ:** feat/25-test-branch
- **関連 Issue:** #25 - feat: PiiMasker プラグインによるスナップショット出力のマスキング (OPEN)

## Issue の進捗
- [ ] PiiMasker プラグインの実装（ルールベースのマスキング）
- [ ] ContextManagerPlugin との連携（パイプライン or 直接参照）
- [ ] デフォルトルール: AWS キー、Bearer トークン、password/secret パターン
- [ ] テスト: マスク前後の検証、フォールバック（PiiMasker 未登録時はスルー）
- [ ] ドキュメント更新

## このブランチでの変更内容
(main と同一、またはブランチ差分なし)

## 未コミットの変更
(clean)
```

### 3. 生成ファイルの確認

```bash
cat .vigil/context_snapshot.md
```

---

## 補足: `mainRun` と `jvmRun` タスク

```kotlin
jvm {
    mainRun {
        mainClass.set("vigil.plugin.contextmanager.VerifySnapshotKt")
    }
}
```

KMP の `mainRun` は、指定した `mainClass` を `jvmRun` Gradle タスクで実行する設定。
Kotlin ファイル `VerifySnapshot.kt` のトップレベル `fun main()` は、
コンパイル後に `VerifySnapshotKt` クラスの `main` メソッドになる。

```typescript
// NestJS 相当: package.json
{
  "scripts": {
    "verify:context": "ts-node scripts/verify-context.ts"
  }
}
```

| 観点 | NestJS (`ts-node`) | Kotlin (`jvmRun`) |
| --- | --- | --- |
| クラス名の規則 | ファイル名そのまま | `<FileName>Kt` に変換 |
| 実行コスト | 起動は速い（JIT なし） | Gradle デーモン起動後は高速 |
| デバッグ | `--inspect` フラグ | `--debug-jvm` フラグ |
| 環境変数 | `process.env.NODE_ENV` | `System.getenv("KEY")` |

---

## 補足: 循環依存を避けるパターン

将来、ルートの `Main.kt` からプラグインを登録したい場合は、
**サービスロケータ** or **リフレクション** パターンで解決する。
どちらもコード上の `import` を使わずにインスタンスを生成するため、循環依存が発生しない。

### サービスロケータパターン（ServiceLoader）

NestJS で言うと `ModuleRef` で実行時にサービスを名前で取得するのに近い。

```typescript
// NestJS: コンパイル時に型を知らなくても、実行時に名前で取得
const service = moduleRef.get('CONTEXT_MANAGER');
```

```kotlin
// Kotlin: ServiceLoader が META-INF/services を読んで実装クラスを自動検出
val plugins = ServiceLoader.load(VigilPlugin::class.java)
plugins.forEach { engine.register(it) }
// → ContextManagerPlugin のインスタンスが返る（import 不要）
```

仕組み: プラグインモジュール側にテキストファイルを配置するだけで、
JVM がクラス名の文字列からインスタンスを生成する。

```text
plugins/context-manager/src/jvmMain/resources/
  META-INF/services/vigil.plugin.VigilPlugin
  └─ 中身: "vigil.plugin.contextmanager.ContextManagerPlugin"
```

### リフレクションパターン

NestJS で言うと `Reflect.getMetadata()` でデコレータ情報を実行時に読むのに近い。

```typescript
// NestJS: @Injectable() デコレータの情報を実行時に読む
const metadata = Reflect.getMetadata('design:paramtypes', target);
```

```kotlin
// Kotlin: クラス名の文字列から実行時にインスタンスを生成（import 不要）
val clazz = Class.forName("vigil.plugin.contextmanager.ContextManagerPlugin")
val plugin = clazz.getDeclaredConstructor().newInstance() as VigilPlugin
engine.register(plugin)
```

設定ファイル（YAML や JSON）にクラス名を列挙し、起動時に動的ロードする使い方が一般的。

### 対比表

| 観点 | 直接 import | サービスロケータ | リフレクション |
| --- | --- | --- | --- |
| NestJS 相当 | `constructor(private svc: Foo)` | `moduleRef.get('FOO')` | `Reflect.getMetadata()` |
| 循環依存 | 発生する | 発生しない | 発生しない |
| 型安全性 | コンパイル時に保証 | 実行時に検証 | 実行時に検証 |
| 設定 | 不要 | `META-INF/services` ファイル | クラス名の文字列 |
| プラグイン追加時 | コード変更が必要 | ファイル追加のみ | 設定ファイル追記 |

### どちらを選ぶか

```typescript
// NestJS 相当: Dynamic Module（サービスロケータ寄り）
@Module({})
export class PluginModule {
  static register(plugins: Type<any>[]): DynamicModule {
    return { module: PluginModule, providers: plugins };
  }
}
```

Kotlin では **ServiceLoader が推奨**。理由:
- JVM 標準 API（追加依存なし）
- プラグイン側がファイルを1つ追加するだけで自動登録される
- リフレクションより型安全（`ServiceLoader<VigilPlugin>` で型が絞られる）

現時点ではプロトタイプのため、検証スクリプトでの手動実行で十分。
