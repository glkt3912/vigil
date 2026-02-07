# マルチモジュール — NestJS の Monorepo モードとの対比

## 概要

プラグインを独立モジュールとして分離するためのマルチモジュール構成。
NestJS の Monorepo モード (`nest g app`/`nest g lib`) で
複数アプリ・ライブラリを1リポジトリで管理するのと同じ考え方。

---

## NestJS Monorepo との対比

### NestJS: nest-cli.json で管理

```jsonc
// nest-cli.json
{
  "monorepo": true,
  "root": "apps/api",
  "projects": {
    "api": { "root": "apps/api" },
    "context-manager": { "root": "libs/context-manager" }
  }
}
```

```
my-project/
├── nest-cli.json
├── apps/
│   └── api/               ← メインアプリ
│       └── src/
└── libs/
    └── context-manager/    ← ライブラリ（プラグイン）
        └── src/
```

### Gradle: settings.gradle.kts で管理

```kotlin
// settings.gradle.kts
rootProject.name = "vigil"
include(":plugins:context-manager")
```

```
vigil/
├── settings.gradle.kts
├── build.gradle.kts             ← ルート = コアモジュール
├── src/                         ← コアのソースコード
└── plugins/
    └── context-manager/         ← プラグインモジュール
        ├── build.gradle.kts
        └── src/
```

### 対比表

| 観点 | NestJS Monorepo | Gradle マルチモジュール |
| --- | --- | --- |
| 設定ファイル | `nest-cli.json` | `settings.gradle.kts` |
| モジュール追加 | `nest g lib context-manager` | `include(":plugins:context-manager")` |
| 依存関係宣言 | `tsconfig.paths` + `package.json` | `implementation(project(":"))` |
| ビルド | `nest build context-manager` | `./gradlew :plugins:context-manager:build` |
| テスト | `nest test context-manager` | `./gradlew :plugins:context-manager:jvmTest` |

---

## settings.gradle.kts の変更

```kotlin
rootProject.name = "vigil"
include(":plugins:context-manager")  // ← 追加
```

`include(":plugins:context-manager")` は、
`plugins/context-manager/` ディレクトリを Gradle モジュールとして認識させる宣言。

NestJS でいう `nest-cli.json` の `projects` にエントリを追加するのと同じ。

### パス解決のルール

Gradle はコロン (`:`) をディレクトリ区切りに変換する:

```
:plugins:context-manager  →  plugins/context-manager/
```

NestJS では `tsconfig.paths` で手動設定するが、
Gradle は `include()` だけでパスを自動解決する。

---

## プラグインモジュールの build.gradle.kts

`plugins/context-manager/build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

group = "com.vigil.plugins"
version = "0.1.0"

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":"))  // ルートプロジェクト（コア）に依存
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
```

### NestJS との対比

```typescript
// NestJS: libs/context-manager/package.json
{
  "dependencies": {
    "@my-app/core": "workspace:*"   // モノレポ内のコアに依存
  }
}
```

```kotlin
// Gradle: implementation(project(":")) でルート（コア）に依存
jvmMain.dependencies {
    implementation(project(":"))  // ← @my-app/core と同じ意味
}
```

| NestJS | Gradle | 説明 |
| --- | --- | --- |
| `"workspace:*"` | `project(":")` | モノレポ内の別モジュールを参照 |
| `"@nestjs/core": "^10.0"` | `"org.jetbrains.kotlinx:..."` | 外部パッケージを参照 |

---

## ディレクトリ構成

```
vigil/
├── settings.gradle.kts              ← モジュール一覧
├── build.gradle.kts                 ← ルート（コア）のビルド設定
├── src/
│   ├── commonMain/kotlin/vigil/
│   │   ├── core/                    ← MonitorConfig, PlatformLogMonitor
│   │   ├── model/                   ← LogEvent, LogLevel
│   │   ├── pipeline/                ← WebSocketPipeline, LogFilter
│   │   ├── retry/                   ← BackoffConfig, ExponentialBackoff
│   │   ├── plugin/                  ← VigilPlugin, PluginContext ★ 新規
│   │   └── engine/                  ← VigilEngine ★ 新規
│   ├── commonTest/kotlin/vigil/
│   ├── jvmMain/kotlin/vigil/
│   └── jvmTest/kotlin/vigil/
└── plugins/
    └── context-manager/             ★ 新規モジュール
        ├── build.gradle.kts
        └── src/
            ├── jvmMain/kotlin/vigil/plugin/contextmanager/
            │   ├── ContextManagerPlugin.kt
            │   └── GitCommandExecutor.kt
            └── jvmTest/kotlin/vigil/plugin/contextmanager/
                ├── ContextManagerPluginTest.kt
                └── GitCommandExecutorTest.kt
```

### NestJS での同等構成

```
my-project/
├── nest-cli.json
├── apps/api/src/
│   ├── core/                        ← ConfigService 等
│   ├── models/                      ← DTO
│   ├── gateways/                    ← WebSocket Gateway
│   └── app.module.ts
└── libs/context-manager/src/
    ├── context-manager.service.ts
    ├── git-command.service.ts
    └── context-manager.module.ts
```

---

## ビルドとテストの実行

```bash
# プロジェクト全体をビルド
./gradlew build

# プラグインモジュールのみビルド
./gradlew :plugins:context-manager:build

# プラグインモジュールのテストのみ実行
./gradlew :plugins:context-manager:jvmTest

# ルート（コア）のテストのみ実行
./gradlew jvmTest
```

### NestJS との対比

```bash
# NestJS
nest build                           # 全体ビルド
nest build context-manager           # 特定ライブラリのみ
npm test -- --project context-manager  # 特定ライブラリのテスト
npm test                             # 全テスト
```

---

## 補足: なぜルートをコアのまま残すか

一般的な Gradle マルチモジュールでは `core/` サブディレクトリにコアコードを移動する:

```
vigil/
├── core/src/        ← コード移動が必要
└── plugins/...
```

今回はルートプロジェクトをそのままコアとして使い、プラグインだけを `plugins/` に追加する:

```
vigil/
├── src/             ← 既存コードはそのまま
└── plugins/...      ← 新規追加のみ
```

この方式のメリット:

- 既存コードの移動が不要（`import` パスの変更なし）
- CI/CD の設定変更が最小限
- 将来コアを分離したくなったときに `core/` に移動すればよい

NestJS でも最初はシングルプロジェクトで始めて、
必要になったときに `nest g app` で Monorepo に移行するのと同じ段階的アプローチ。
