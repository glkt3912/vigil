# ContextManagerPlugin — child_process から ProcessBuilder へ

## 概要

Git の差分・ステータスを定期的に収集し、Markdown 形式の「コンテキスト・スナップショット」を生成するプラグイン。
NestJS で `child_process.execSync()` を使って外部コマンドを実行するのと同じことを、
Kotlin (JVM) では `ProcessBuilder` で実現する。

---

## NestJS での外部コマンド実行

### child_process.execSync

```typescript
import { execSync } from 'child_process';

@Injectable()
export class GitService {
  diffStat(): string {
    return execSync('git diff --stat', { encoding: 'utf-8' });
  }

  statusShort(): string {
    return execSync('git status --short', { encoding: 'utf-8' });
  }

  logOneline(count = 10): string {
    return execSync(`git log --oneline -${count}`, { encoding: 'utf-8' });
  }

  branchName(): string {
    return execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf-8' }).trim();
  }
}
```

---

## Kotlin (JVM) での外部コマンド実行

### ProcessBuilder

`plugins/context-manager/src/jvmMain/kotlin/.../GitCommandExecutor.kt`:

```kotlin
class GitCommandExecutor(private val workingDir: File = File(".")) {

    fun diffStat(): String = execute("git", "diff", "--stat")

    fun statusShort(): String = execute("git", "status", "--short")

    fun logOneline(count: Int = 10): String =
        execute("git", "log", "--oneline", "-$count")

    fun branchName(): String =
        execute("git", "rev-parse", "--abbrev-ref", "HEAD").trim()

    private fun execute(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return "error(exit=$exitCode): $output"
        }
        return output
    }
}
```

### 対比表

| 観点 | NestJS (`child_process`) | Kotlin (`ProcessBuilder`) |
| --- | --- | --- |
| コマンド指定 | 文字列 `'git diff --stat'` | 配列 `"git", "diff", "--stat"` |
| 作業ディレクトリ | `{ cwd: '/path' }` | `.directory(File("/path"))` |
| 標準エラー統合 | `{ stdio: 'pipe' }` + 手動マージ | `.redirectErrorStream(true)` |
| 出力取得 | 戻り値が `string` | `.inputStream.bufferedReader().readText()` |
| 終了コード | 非0で例外スロー | `.waitFor()` で手動チェック |
| 同期/非同期 | `execSync` (同期) / `exec` (非同期) | `waitFor()` (同期) / コルーチンで非同期化可能 |

### なぜコマンドを配列で渡すか

```typescript
// NestJS: 文字列で渡す → シェルインジェクションのリスク
execSync(`git log --oneline -${userInput}`);
// userInput が "10; rm -rf /" だと危険
```

```kotlin
// Kotlin: 配列で渡す → 各引数が個別にエスケープされる
ProcessBuilder("git", "log", "--oneline", "-$count")
// count が "10; rm -rf /" でも "git log --oneline -10; rm -rf /" という
// 1つの引数として扱われるため、シェルインジェクションが起きない
```

---

## ContextManagerPlugin の実装

`plugins/context-manager/src/jvmMain/kotlin/.../ContextManagerPlugin.kt`:

```kotlin
class ContextManagerPlugin(
    private val repoPath: String = ".",
    private val outputPath: String = ".vigil/context_snapshot.md",
) : VigilPlugin {
    override val name = "context-manager"

    private val git by lazy { GitCommandExecutor(File(repoPath)) }

    override suspend fun onInitialize(context: PluginContext) {
        val snapshot = buildSnapshot()
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onHeartbeat(context: PluginContext) {
        val snapshot = buildSnapshot()
        writeToFile(snapshot)
        context.publish(snapshot)
    }

    override suspend fun onShutdown() { /* クリーンアップ不要 */ }
}
```

### NestJS での同等実装

```typescript
@Injectable()
export class ContextManagerService implements OnModuleInit {
  private readonly git = new GitService();

  async onModuleInit() {
    const snapshot = this.buildSnapshot();
    this.writeToFile(snapshot);
    this.eventEmitter.emit('context.snapshot', snapshot);
  }

  @Interval(30000)
  async handleInterval() {
    const snapshot = this.buildSnapshot();
    this.writeToFile(snapshot);
    this.eventEmitter.emit('context.snapshot', snapshot);
  }
}
```

---

## スナップショットの生成

```kotlin
internal fun buildSnapshot(): String = buildString {
    appendLine("# Development Context Snapshot")
    appendLine()
    appendLine("## Branch")
    appendLine("```")
    appendLine(git.branchName())
    appendLine("```")
    appendLine()
    appendLine("## Status")
    appendLine("```")
    val status = git.statusShort()
    appendLine(status.ifBlank { "(clean)" })
    appendLine("```")
    // ... Diff, Recent Commits も同様
}
```

出力例:

```markdown
# Development Context Snapshot

## Branch
\```
feat/plugin-context-manager
\```

## Status
\```
M  settings.gradle.kts
?? plugins/
\```

## Diff (stat)
\```
 settings.gradle.kts | 1 +
 1 file changed, 1 insertion(+)
\```

## Recent Commits
\```
2b33758 Merge pull request #19
dd828e8 docs: add jvmTest vs allTests
\```
```

このファイルを AI エージェント（Claude Code 等）が読み込むことで、
セッション開始時に手動で進捗を説明する必要がなくなる。

---

## 出力先の設計

```text
ContextManagerPlugin
    │
    ├── .vigil/context_snapshot.md   ← ファイル出力（AI が直接読む）
    │
    └── context.publish(snapshot)    ← WebSocket パイプライン経由
         │
         └── 既存の WebSocketPipeline で送信
              （LogEvent と同じ経路）
```

### なぜ 2 系統の出力か

| 出力先 | 用途 | 利点 |
| --- | --- | --- |
| ファイル | AI エージェントがローカルで直接読み込む | WebSocket 接続不要、オフラインで動作 |
| WebSocket | リモートのダッシュボード等にリアルタイム配信 | 既存パイプラインを流用、追加依存なし |

NestJS でいうと:

```typescript
// 1. ファイル出力 ← fs.writeFileSync
fs.writeFileSync('.vigil/context_snapshot.md', snapshot);

// 2. イベント配信 ← EventEmitter でリアルタイム通知
this.eventEmitter.emit('context.snapshot', snapshot);
```

---

## 補足: `by lazy` — 遅延初期化

```kotlin
private val git by lazy { GitCommandExecutor(File(repoPath)) }
```

`by lazy` はプロパティが最初にアクセスされたときに1回だけ初期化する。
NestJS では `@Inject()` の解決タイミングに近い。

```typescript
// NestJS: Provider はモジュール初期化時に1回だけインスタンス化される
@Injectable()
export class ContextManagerService {
  constructor(private git: GitService) {}  // DI コンテナが1回だけ生成
}
```

```kotlin
// Kotlin: 最初のアクセス時に1回だけ生成
private val git by lazy { GitCommandExecutor(File(repoPath)) }
// git.diffStat() を呼んだ瞬間に GitCommandExecutor が生成される
```

| 観点 | NestJS DI | Kotlin `by lazy` |
| --- | --- | --- |
| 初期化タイミング | モジュール初期化時 | 最初のアクセス時 |
| スコープ | Singleton / Request / Transient | 常に Singleton 相当 |
| スレッドセーフ | シングルスレッド (Node.js) | デフォルトでスレッドセーフ |

---

## 補足: `buildString` — StringBuilder のラッパー

```kotlin
val result = buildString {
    appendLine("# Title")
    appendLine("content")
}
```

NestJS/JavaScript では:

```typescript
// テンプレートリテラル
const result = `# Title
content`;

// または配列 + join
const lines = ['# Title', 'content'];
const result = lines.join('\n');
```

`buildString` は内部で `StringBuilder` を使い、
文字列結合の中間オブジェクト生成を避ける。
短い文字列では差がないが、大きなスナップショット生成で効率的。

---

## 補足: KMP とプラットフォーム制限

`ProcessBuilder` は JVM 固有の API のため、
`GitCommandExecutor` は `jvmMain` にのみ配置している。

```
plugins/context-manager/src/
├── jvmMain/   ← ProcessBuilder が使える
└── (commonMain は使わない: ProcessBuilder が存在しない)
```

将来 Native (mingwX64) でも Git コマンドを実行したい場合は、
`expect/actual` パターンで抽象化する（docs/03-expect-actual.md 参照）:

```kotlin
// commonMain
expect class CommandExecutor() {
    fun execute(vararg command: String): String
}

// jvmMain
actual class CommandExecutor {
    actual fun execute(vararg command: String): String {
        return ProcessBuilder(*command).start().inputStream.readText()
    }
}

// mingwX64Main
actual class CommandExecutor {
    actual fun execute(vararg command: String): String {
        // Win32 API: CreateProcess() を使用
    }
}
```

現時点ではプロトタイプのため、JVM 実装のみで十分。
