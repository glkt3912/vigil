# データモデル — NestJS の DTO と Kotlin の data class

## 概要

ログイベントを表現するデータモデルを `commonMain` に定義する。
NestJS では DTO (Data Transfer Object) クラスとバリデーションデコレータで行うことを、
Kotlin では `data class` + `@Serializable` で実現する。

---

## LogLevel: enum の対比

### NestJS (TypeScript)

```typescript
export enum LogLevel {
  TRACE = 'TRACE',
  DEBUG = 'DEBUG',
  INFO = 'INFO',
  WARN = 'WARN',
  ERROR = 'ERROR',
  FATAL = 'FATAL',
}
```

### Kotlin

```kotlin
@Serializable
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL
}
```

ほぼ同じ。違いは `@Serializable` アノテーション:

| 観点 | NestJS | Kotlin |
| --- | --- | --- |
| 定義方法 | `enum` | `enum class` |
| JSON 変換 | `class-transformer` が実行時にリフレクション | `@Serializable` がコンパイル時にコード生成 |
| バリデーション | `@IsEnum(LogLevel)` で実行時チェック | コンパイル時に型で保証 |

---

## LogEvent: DTO と data class の対比

### NestJS (TypeScript)

```typescript
export class LogEventDto {
  @IsString()      readonly id: string;
  @IsDateString()  readonly timestamp: string;
  @IsEnum(LogLevel) readonly level: LogLevel;
  @IsString()      readonly source: string;
  @IsString()      readonly message: string;
  @IsOptional()
  @IsObject()      readonly metadata?: Record<string, string>;
}
```

### Kotlin

```kotlin
@Serializable
data class LogEvent(
    val id: String,
    val timestamp: Instant,
    val level: LogLevel,
    val source: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)
```

### 対比表

| 観点 | NestJS | Kotlin |
| --- | --- | --- |
| 不変性 | `readonly` を手動で付ける | `val` がデフォルトで不変 |
| 等価比較 | 手動実装 or ライブラリ | `equals()` / `hashCode()` 自動生成 |
| 文字列表現 | `toString()` を手動実装 | `toString()` 自動生成 |
| コピー | `{ ...obj, field: newVal }` | `obj.copy(field = newVal)` |
| デフォルト値 | `metadata?: Record<...>` + `?? {}` | `metadata: Map<...> = emptyMap()` |
| 日時型 | `string` (ISO 8601) を手動パース | `Instant` 型で型安全 |

---

## val vs readonly

```kotlin
// Kotlin
val id: String        // 再代入不可（readonly と同じ）
var id: String        // 再代入可能（let と同じ）
```

```typescript
// TypeScript
readonly id: string;  // 再代入不可
id: string;           // 再代入可能
```

`data class` では `val` のみ推奨。全フィールドが不変 = イミュータブルなオブジェクト。
NestJS の DTO に `readonly` を付け忘れるリスクがない。

---

## @Serializable の仕組み

NestJS で `class-transformer` と `class-validator` を使って
JSON のバリデーション + 変換を行うが、これは **実行時** の処理:

```typescript
// NestJS: 実行時にリフレクションでプロパティを読み取り変換
const event = plainToInstance(LogEventDto, jsonObject);
const errors = await validate(event);
```

Kotlin の `@Serializable` は **コンパイル時** にシリアライザコードを生成する:

```kotlin
// Kotlin: コンパイル時に生成されたコードで変換（リフレクション不要）
val event = Json.decodeFromString<LogEvent>(jsonString)
val json = Json.encodeToString(event)
```

利点:

- 実行時コストがない（リフレクション不要）
- 型の不整合がコンパイルエラーになる
- KMP で全プラットフォーム共通で動く
