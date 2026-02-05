# Vigil

Kotlin Multiplatform (KMP) で構築するリアルタイム・ログ監視ツール。

JVM と Windows Native (mingwX64) の両ターゲットに対応し、ファイル監視 → フィルタリング → WebSocket 送信のパイプラインを提供する。

## 特徴

- **マルチプラットフォーム**: 1つのコードベースから JVM (.jar) と Windows Native (.exe) を生成
- **リアクティブストリーム**: Kotlin Flow による非同期パイプライン処理
- **自動再接続**: 指数バックオフ + ジッターによる堅牢な WebSocket 接続
- **型安全**: コンパイル時に型エラーを検出、実行時エラーを最小化

## 技術スタック

| ライブラリ | バージョン | 用途 |
|---|---|---|
| Kotlin | 2.1.10 | 言語 |
| Ktor Client | 3.1.1 | WebSocket 通信 |
| kotlinx-coroutines | 1.10.2 | 非同期処理 / Flow |
| kotlinx-serialization | 1.8.0 | JSON シリアライズ |
| kotlinx-datetime | 0.6.2 | タイムスタンプ処理 |

## 必要環境

- JDK 21+
- Gradle 9.x (Wrapper 同梱)

## ビルド

```bash
# JVM ビルド
./gradlew jvmJar

# Windows Native ビルド (クロスコンパイル)
./gradlew linkReleaseExecutableMingwX64

# 全ターゲットビルド
./gradlew build
```

## プロジェクト構成

```
src/
├── commonMain/          # 全プラットフォーム共通コード
│   └── kotlin/vigil/
│       ├── model/       # LogEvent, LogLevel
│       ├── core/        # MonitorConfig, PlatformLogMonitor (expect)
│       ├── pipeline/    # WebSocketPipeline, LogFilter
│       └── retry/       # BackoffConfig, ExponentialBackoff
├── jvmMain/             # JVM 固有コード
│   └── kotlin/vigil/
│       └── core/        # JvmLogMonitor (actual)
└── mingwX64Main/        # Windows Native 固有コード
    └── kotlin/vigil/
        └── core/        # WindowsLogMonitor (actual stub)
```

## ドキュメント

NestJS 開発者向けに Kotlin/KMP の概念を解説したドキュメントを `docs/` に用意:

| ファイル | 内容 |
|---|---|
| [00-setup.md](docs/00-setup.md) | 環境構築 |
| [01-build-system.md](docs/01-build-system.md) | Gradle ビルドシステム |
| [02-data-model.md](docs/02-data-model.md) | data class と DTO |
| [03-expect-actual.md](docs/03-expect-actual.md) | expect/actual と DI |
| [04-exponential-backoff.md](docs/04-exponential-backoff.md) | 指数バックオフと RxJS |
| [05-websocket-pipeline.md](docs/05-websocket-pipeline.md) | WebSocket パイプライン |
| [06-jvm-implementation.md](docs/06-jvm-implementation.md) | JVM 実装と WatchService |
| [07-windows-implementation.md](docs/07-windows-implementation.md) | Windows Native と Win32 API |

## ロードマップ

### Phase 1: コアプロトタイプ (完了)

- [x] ビルド基盤 (KMP + Gradle)
- [x] データモデル (LogEvent, LogLevel)
- [x] expect/actual インターフェース
- [x] 指数バックオフ再接続
- [x] WebSocket 送信パイプライン
- [x] JVM 実装 (WatchService)
- [x] Windows Native スタブ

### Phase 2: 通知トレイ常駐型ツール (予定)

- [ ] デスクトップ通知 (JVM: SystemTray / Windows: Shell_NotifyIcon)
- [ ] フィルタリング強化 (重複排除、時間ウィンドウ)
- [ ] 設定ファイル対応
- [ ] Windows Native 実装 (ReadDirectoryChangesW)

## ライセンス

MIT
