# 環境構築 — NestJS 開発者のための JVM 環境セットアップ

## NestJS との対比

```text
NestJS:  Node.js をインストール → npm install → npm run build
Kotlin:  JDK をインストール   → ./gradlew build（依存取得+ビルドが一発）
```

npm では `npm install`（依存取得）と `npm run build`（ビルド）が別コマンドだが、
Gradle は `./gradlew build` だけで依存取得からコンパイルまで全て行う。

---

## 必要なもの

| 必要なもの | NestJS 対比 | 説明 |
| --- | --- | --- |
| JDK 21+ | Node.js | Kotlin コンパイラが動く実行環境 |
| Gradle Wrapper | なし（npm は Node に同梱） | プロジェクト内の `gradlew` スクリプトが Gradle 本体を自動取得 |

Gradle 本体のグローバルインストールは不要。
Wrapper があれば `./gradlew build` で自動ダウンロードされる。

---

## JDK のインストール（macOS / Homebrew）

```bash
brew install openjdk@21
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

確認:

```bash
/usr/libexec/java_home -V
# → 21.0.x が表示されれば OK
```

### NestJS での対比

```bash
# NestJS: nvm で Node.js バージョン管理
nvm install 20
nvm use 20
node -v

# Kotlin: Homebrew で JDK バージョン管理
brew install openjdk@21
/usr/libexec/java_home -V
```

---

## Gradle Wrapper とは

NestJS では `npm` が Node.js に同梱されているので別途インストール不要だが、
Gradle はプロジェクトごとに **Wrapper** というブートストラップを持つ:

```text
gradlew              ← Linux/Mac 用シェルスクリプト（npx に近い概念）
gradlew.bat          ← Windows 用バッチファイル
gradle/wrapper/
  gradle-wrapper.jar         ← Gradle を自動ダウンロードするブートストラップ
  gradle-wrapper.properties  ← どのバージョンの Gradle を使うか
```

`./gradlew build` を実行すると:

1. 指定バージョンの Gradle を自動ダウンロード
2. 依存ライブラリを自動ダウンロード（`npm install` 相当）
3. コンパイル・ビルド実行

---

## Gradle のバージョン管理

Gradle は Node.js のような LTS モデルを採用していない。

| ツール | バージョン管理方式 |
| --- | --- |
| Node.js | LTS (偶数: 18, 20, 22) と Current (奇数) を交互リリース |
| Gradle | 最新メジャーバージョンが唯一のサポート対象。旧メジャーはセキュリティ修正のみ |

---

## ビルド確認

```bash
./gradlew build
# → BUILD SUCCESSFUL が出れば OK
```
