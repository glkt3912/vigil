# CI/CD — GitHub Actions と NestJS プロジェクトとの対比

## 概要

GitHub Actions による自動テスト・ビルド・リリースの仕組み。
NestJS プロジェクトでも GitHub Actions は広く使われるが、
KMP プロジェクトでは JDK セットアップやマルチターゲットビルドなど固有の考慮が必要になる。

---

## NestJS での対応概念

NestJS プロジェクトの典型的な CI/CD:

- `npm ci` → `npm test` → `npm run build` の 3 ステップ
- Node.js バージョンは `actions/setup-node` で管理
- E2E テストはステージング環境で実行

### コード例

```yaml
# .github/workflows/ci.yml (NestJS)
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
      - run: npm ci
      - run: npm test
      - run: npm run build
```

### リリース (NestJS)

```yaml
# .github/workflows/release.yml (NestJS)
name: Release

on:
  push:
    tags: ['v*']

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          registry-url: 'https://registry.npmjs.org'
      - run: npm ci
      - run: npm publish
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

NestJS では成果物は **npm パッケージ** として配布するのが一般的。

---

## Kotlin での実装

KMP プロジェクトでは、**実行可能バイナリ** (jar / exe) を GitHub Releases に直接配置する。
npm registry のような中央リポジトリを経由しない。

### CI ワークフロー

```yaml
# .github/workflows/ci.yml (Kotlin/KMP)
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'gradle'
      - run: chmod +x ./gradlew
      - run: ./gradlew allTests --no-daemon
      - run: ./gradlew jvmJar --no-daemon
```

### Release ワークフロー

```yaml
# .github/workflows/release.yml (Kotlin/KMP)
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'gradle'
      - name: Extract version from tag
        id: version
        shell: bash
        run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT
      - run: ./gradlew jvmJar --no-daemon
      - run: ./gradlew linkReleaseExecutableMingwX64 --no-daemon
      - name: Rename artifacts
        shell: bash
        run: |
          cp build/libs/vigil-jvm-*.jar "vigil-${{ steps.version.outputs.VERSION }}-jvm.jar"
          cp build/bin/mingwX64/releaseExecutable/vigil.exe "vigil-${{ steps.version.outputs.VERSION }}-windows.exe"
      - uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            vigil-${{ steps.version.outputs.VERSION }}-jvm.jar
            vigil-${{ steps.version.outputs.VERSION }}-windows.exe
```

---

## 対比表

| 観点 | NestJS (Node.js) | Kotlin (KMP) |
| --- | --- | --- |
| ランタイムセットアップ | `actions/setup-node` | `actions/setup-java` (Temurin JDK 21) |
| パッケージマネージャ | npm (`npm ci`) | Gradle (`./gradlew`) |
| キャッシュ対象 | `node_modules` | Gradle 依存関係 + ビルドキャッシュ |
| テスト実行 | `npm test` | `./gradlew allTests` |
| ビルド | `npm run build` | `./gradlew jvmJar` / `linkReleaseExecutableMingwX64` |
| 成果物の形式 | npm パッケージ (.tgz) | jar (JVM) / exe (Windows Native) |
| 配布先 | npm registry | GitHub Releases |
| 配布トリガー | `npm publish` | `softprops/action-gh-release` |
| CI ランナー | ubuntu-latest | ubuntu-latest (CI) / windows-latest (Release) |
| 認証 | `NPM_TOKEN` (secret) | `GITHUB_TOKEN` (自動付与) |

---

## 補足: Gradle Wrapper (`gradlew`)

NestJS プロジェクトでは `npm` や `npx` がグローバルにインストールされている前提で CI を組む。
Kotlin プロジェクトでは **Gradle Wrapper** (`gradlew`) がリポジトリに同梱されており、
Gradle 自体のインストールは不要。

```bash
# NestJS — npm はグローバルに存在する前提
npm ci

# Kotlin — gradlew がビルドツールのバージョンを固定
./gradlew build
# → gradle/wrapper/gradle-wrapper.properties に指定されたバージョンを自動ダウンロード
```

TypeScript でのイメージ:

```typescript
// package.json の "engines" フィールドに近いが、より強制力がある
// npm のバージョンは .npmrc や volta で管理するのに対し、
// Gradle は wrapper スクリプト自体がバージョンを固定・自動取得する
```

これにより、CI 環境でも開発者のローカルでも**同一バージョンの Gradle** が使われることが保証される。

---

## 補足: `--no-daemon` フラグ

CI 環境では `--no-daemon` を付けて Gradle を実行する。

```bash
./gradlew allTests --no-daemon
```

Gradle はデフォルトで **デーモンプロセス** をバックグラウンドに常駐させ、
2 回目以降のビルドを高速化する（NestJS でいう `tsc --watch` に近い仕組み）。

しかし CI では:
- 各ジョブは使い捨ての環境で実行される
- デーモンの恩恵がない
- デーモンが残るとジョブが終了しない可能性がある

そのため `--no-daemon` で単発実行する。

---

## 補足: `permissions: contents: write`

GitHub Actions のワークフローにはデフォルトで最小限の権限しか付与されない。
GitHub Releases に成果物をアップロードするには `contents: write` 権限が必要。

```yaml
permissions:
  contents: write  # GitHub Release の作成・ファイル添付に必要
```

NestJS で npm publish する場合は `NPM_TOKEN` シークレットを明示的に設定するが、
GitHub Releases への公開では `GITHUB_TOKEN` が自動付与されるため、
シークレットの手動設定は不要。

---

## 補足: マルチターゲットビルドとランナー選択

KMP プロジェクトでは複数のターゲット（JVM, Windows Native 等）をビルドする。
ターゲットによって最適なランナーが異なる:

| ターゲット | ビルドコマンド | 推奨ランナー |
| --- | --- | --- |
| JVM (jar) | `./gradlew jvmJar` | 任意 (ubuntu, windows, macos) |
| Windows Native (exe) | `./gradlew linkReleaseExecutableMingwX64` | windows-latest |

NestJS はシングルプラットフォーム (Node.js) なので `ubuntu-latest` 一択だが、
KMP ではネイティブターゲットに応じてランナーを選ぶ必要がある。

Vigil の Release ワークフローでは **windows-latest** を採用し、
JVM jar と Windows exe の両方を同一ジョブでビルドしている。
