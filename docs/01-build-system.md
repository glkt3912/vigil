# ビルドシステム — NestJS 開発者のための Gradle 入門

## Gradle の設定ファイル構成

NestJS プロジェクトとの対応:

| Gradle ファイル | NestJS 対比 | 役割 |
|---|---|---|
| `settings.gradle.kts` | `.npmrc` + `package.json` の `name` | プロジェクト名・リポジトリ設定 |
| `build.gradle.kts` | `package.json` の `dependencies` + `tsconfig.json` | 依存関係・コンパイル設定 |
| `gradle.properties` | `.env` | JVM フラグ・ビルド設定値 |

---

## リポジトリとは何か

### npm の世界（NestJS）

npm ではパッケージの取得先は基本的に **1箇所だけ**:

```
npmjs.com  ← npm install するとここから取ってくる
```

`npm install @nestjs/core` と打てば、暗黙的に `https://registry.npmjs.org` から取得される。
普段意識する必要がない。

社内限定パッケージを使うときだけ `.npmrc` で private registry を追加する程度。

### JVM の世界（Kotlin / Java）

JVM 圏では歴史的経緯でパッケージの **置き場所が複数に分散** している:

```
mavenCentral()         ← npmjs.com に相当。最大の公開リポジトリ
google()               ← Google が Android/KMP 系ライブラリを置いている場所
gradlePluginPortal()   ← Gradle プラグイン専用の置き場所
```

npm に例えるとこういう状況:

```
// もし npm の世界がこうなっていたら…
npmrc:
  registry=https://registry.npmjs.org     ← mavenCentral()
  @google:registry=https://google.npm.dev  ← google()
  @gradle:registry=https://plugins.gradle.org ← gradlePluginPortal()
```

`repositories { ... }` ブロックは「`npm install` するときにどのレジストリを探すか」を列挙しているだけ。

---

## settings.gradle.kts の2つのブロック

取得するものの種類が違うため、ブロックが分かれている:

```kotlin
pluginManagement {
    repositories { ... }   // ← ビルドツール用（devDependencies 的）
}

dependencyResolution {
    repositories { ... }   // ← アプリコード用（dependencies 的）
}
```

NestJS での対比:

| Gradle | package.json | 何を取得するか |
|---|---|---|
| `pluginManagement.repositories` | `devDependencies` の取得先 | Kotlin コンパイラ、Gradle プラグイン |
| `dependencyResolution.repositories` | `dependencies` の取得先 | Ktor, kotlinx-coroutines 等 |

npm では両方とも同じ `npmjs.com` から取るので区別がないが、
JVM 圏では「ビルドツールのプラグイン」と「アプリが使うライブラリ」で取得先を分けて管理する設計。

---

## gradle.properties

ビルドツール自体の挙動を制御する。NestJS でいう `.env` に近い。

| プロパティ | 意味 | NestJS 対比 |
|---|---|---|
| `kotlin.code.style=official` | Kotlin 公式コードスタイルを適用 | `.prettierrc` の preset 指定 |
| `kotlin.mpp.stability.nowarn=true` | KMP 実験的機能の警告を抑制 | TypeScript の `skipLibCheck: true` に近い |
| `org.gradle.jvmargs=-Xmx2g` | Gradle 自体が使うメモリ上限 | Node.js の `--max-old-space-size` |

---

## build.gradle.kts

NestJS でいう `package.json` + `tsconfig.json` を合わせた、プロジェクトの中核ファイル。

### plugins ブロック

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}
```

「このプロジェクトをどうビルドするか」を宣言する。
NestJS では `@nestjs/cli` が暗黙的にやっていることを、Gradle では明示的にプラグインとして指定する。

| Gradle プラグイン | NestJS 対比 | 役割 |
|---|---|---|
| `kotlin("multiplatform")` | `typescript` + `ts-loader` | KMP 用コンパイラ |
| `kotlin("plugin.serialization")` | `class-transformer` | JSON シリアライズのコード生成（コンパイル時） |

NestJS の `class-transformer` は実行時にデコレータでオブジェクト変換するが、
Kotlin の serialization プラグインはコンパイル時にコードを生成するため、実行時コストがない。

### group / version

```kotlin
group = "com.vigil"    // npm の @scope に相当（@vigil/core 的な名前空間）
version = "0.1.0"      // package.json の "version" と同じ
```

### ターゲット宣言

```kotlin
kotlin {
    jvm()                    // Node.js 的な実行環境（JVM 上で動く）
    mingwX64 {               // Windows Native バイナリ（Node.js 不要の .exe）
        binaries.executable()
    }
}
```

NestJS にはない概念。1つのソースコードから複数のプラットフォーム向けバイナリを生成する。

| ターゲット | 何が生成されるか | NestJS で例えると |
|---|---|---|
| `jvm()` | JVM 上で動く jar ファイル | `nest build` で生成される JS ファイル |
| `mingwX64` | Windows ネイティブ `.exe` | `pkg` で Node.js アプリを `.exe` 化するイメージ |

### sourceSets と依存関係

```kotlin
sourceSets {
    commonMain.dependencies { ... }   // 全プラットフォーム共通
    jvmMain.dependencies { ... }      // JVM 固有
    named("mingwX64Main") { ... }     // Windows Native 固有
}
```

NestJS ではすべてのコードが `src/` 配下に同居するが、
KMP では「共通コード」と「プラットフォーム固有コード」を明確に分離する:

| ソースセット | NestJS 対比 | 置く場所 |
|---|---|---|
| `commonMain` | `src/` 配下の通常コード | `src/commonMain/kotlin/` |
| `jvmMain` | 該当なし（Node.js 固有コードは意識しない） | `src/jvmMain/kotlin/` |
| `mingwX64Main` | 該当なし | `src/mingwX64Main/kotlin/` |

### 依存関係の書き方

```kotlin
implementation("io.ktor:ktor-client-core:3.1.1")
//              ^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^^^^
//              group    artifact         version
//              (@scope) (package名)      (バージョン)
```

npm との対比:

```jsonc
// npm: @ktor/client-core@3.1.1 のような意味
"dependencies": {
    "@ktor/client-core": "3.1.1"
}
```

JVM 圏は `group:artifact:version` の3要素で一意にパッケージを特定する。
