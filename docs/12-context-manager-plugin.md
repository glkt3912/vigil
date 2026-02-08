# ContextManagerPlugin — child_process から ProcessBuilder へ

## 概要

Git 情報と GitHub Issue/PR 情報を自動収集し、意味レベルの要約スナップショットを生成するプラグイン。
AI エージェントがセッション開始時に「開発者が何の Issue に取り組み、どこまで進んだか」を把握するための
構造化された Markdown を出力する。

NestJS で `child_process.execSync()` を使って外部コマンドを実行するのと同じことを、
Kotlin (JVM) では `ProcessBuilder` で実現する。

---

## NestJS での外部コマンド実行

### child_process.execSync

```typescript
import { execSync } from 'child_process';

@Injectable()
export class GitService {
  branchName(): string {
    return execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf-8' }).trim();
  }

  logFromDefault(): string {
    return execSync('git log main..HEAD --pretty=format:"%h %s"', { encoding: 'utf-8' });
  }

  statusShort(): string {
    return execSync('git status --short', { encoding: 'utf-8' });
  }
}

@Injectable()
export class GitHubService {
  currentPR(branch: string): PRInfo | null {
    try {
      const json = execSync(
        `gh pr list --head ${branch} --json number,title,state --limit 1`,
        { encoding: 'utf-8' },
      );
      const arr = JSON.parse(json);
      return arr.length > 0 ? arr[0] : null;
    } catch {
      return null; // gh CLI なし or 未認証
    }
  }
}
```

---

## Kotlin (JVM) での外部コマンド実行

### GitCommandExecutor

`plugins/context-manager/src/jvmMain/kotlin/.../GitCommandExecutor.kt`:

```kotlin
class GitCommandExecutor(private val workingDir: File = File(".")) {

    // プロトタイプでは main 固定。将来は git symbolic-ref refs/remotes/origin/HEAD で自動検出。
    fun defaultBranch(): String = "main"

    fun branchName(): String =
        execute("git", "rev-parse", "--abbrev-ref", "HEAD").trim()

    // デフォルトブランチからの差分コミット（このブランチの作業内容）
    fun logFromDefault(): String =
        execute("git", "log", "${defaultBranch()}..HEAD", "--pretty=format:%h %s")

    // デフォルトブランチとの差分統計
    fun diffStatFromDefault(): String =
        execute("git", "diff", "${defaultBranch()}..HEAD", "--stat")

    fun statusShort(): String = execute("git", "status", "--short")

    internal fun execute(vararg command: String): String {
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

### GitHubExecutor

`plugins/context-manager/src/jvmMain/kotlin/.../GitHubExecutor.kt`:

```kotlin
class GitHubExecutor(private val workingDir: File = File(".")) {

    val available: Boolean by lazy {
        try {
            val process = ProcessBuilder("gh", "auth", "status")
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun currentPR(branch: String): PrInfo? {
        if (!available) return null
        val json = execute("gh", "pr", "list", "--head", branch,
            "--json", "number,title,state", "--limit", "1")
        return parsePR(json)
    }

    fun issueDetail(number: Int): IssueInfo? { /* ... */ }

    // ブランチ名から Issue 番号を推定: feat/22-xxx → 22
    fun parseIssueNumber(branch: String): Int? {
        val slug = branch.substringAfter("/", "")
        if (slug.isEmpty()) return null
        val match = Regex("""(?:issue-)?(\d+)""").find(slug)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    data class PrInfo(val number: Int, val title: String, val state: String)
    data class IssueInfo(val title: String, val body: String, val state: String)
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
| JSON パース | `JSON.parse()` (組込み) | 軽量 Regex パース（依存追加を避ける） |
| フォールバック | `try/catch` | `available` フラグで事前チェック |

### なぜコマンドを配列で渡すか

```typescript
// NestJS: 文字列で渡す → シェルインジェクションのリスク
execSync(`git log --oneline -${userInput}`);
// userInput が "10; rm -rf /" だと危険
```

```kotlin
// Kotlin: 配列で渡す → 各引数が個別にエスケープされる
ProcessBuilder("git", "log", "--oneline", "-$count")
// count が "10; rm -rf /" でも 1 つの引数として扱われるため安全
```

---

## スナップショットの構造

### 旧形式（v1）

Git コマンドの生出力を並べただけ。AI が自力で取得できる情報の複製でしかなかった。

```markdown
# Development Context Snapshot
## Branch
## Status
## Diff (stat)
## Recent Commits
```

### 新形式（v2）— 意味レベルの要約

GitHub Issue/PR 情報を組み合わせ、「何に取り組み、どこまで進んだか」を構造化:

```kotlin
internal fun buildSnapshot(): String = buildString {
    val branch = git.branchName()

    appendLine("# 開発コンテキスト")

    // --- 現在の作業 ---
    appendLine("## 現在の作業")
    appendLine("- **ブランチ:** $branch")

    val issueNumber = github.parseIssueNumber(branch)
    val issueInfo = issueNumber?.let { github.issueDetail(it) }
    if (issueInfo != null) {
        appendLine("- **関連 Issue:** #$issueNumber - ${issueInfo.title} (${issueInfo.state})")
    }

    val prInfo = github.currentPR(branch)
    if (prInfo != null) {
        appendLine("- **関連 PR:** #${prInfo.number} - ${prInfo.title} (${prInfo.state})")
    }

    // --- Issue の進捗（チェックリスト抽出） ---
    if (issueInfo != null && issueInfo.body.contains("- [")) {
        appendLine("## Issue の進捗")
        issueInfo.body.lines()
            .filter { it.trimStart().startsWith("- [") }
            .forEach { appendLine(it.trim()) }
    }

    // --- このブランチでの変更内容 ---
    appendLine("## このブランチでの変更内容")
    // logFromDefault() + diffStatFromDefault()

    // --- 未コミットの変更 ---
    appendLine("## 未コミットの変更")
    // statusShort()
}
```

出力例:

```markdown
# 開発コンテキスト

## 現在の作業
- **ブランチ:** feat/context-snapshot-enrichment
- **関連 Issue:** #22 - スナップショット生成の実用化 (OPEN)
- **関連 PR:** #24 - GitHub Issue/PR 連携によるスナップショット実用化 (OPEN)

## Issue の進捗
- [x] Phase 1: 差分チェック
- [ ] Phase 2: イベント駆動
- [ ] Phase 3: 手動トリガー対応

## このブランチでの変更内容
b56c17c feat(context-manager): redesign buildSnapshot() with semantic structure
f6c0c8d feat(context-manager): add GitHubExecutor for gh CLI integration
1708c1a feat(context-manager): add branch-specific diff methods to GitCommandExecutor

**変更ファイル:**
 .../ContextManagerPlugin.kt | 80 ++++++---
 .../GitCommandExecutor.kt   | 12 ++
 3 files changed, 67 insertions(+), 29 deletions(-)

## 未コミットの変更
(clean)
```

### NestJS での同等実装

```typescript
@Injectable()
export class ContextManagerService implements OnModuleInit {
  constructor(
    private readonly git: GitService,
    private readonly github: GitHubService,
  ) {}

  async buildSnapshot(): Promise<string> {
    const branch = this.git.branchName();
    const lines: string[] = [];

    lines.push('# 開発コンテキスト');
    lines.push('## 現在の作業');
    lines.push(`- **ブランチ:** ${branch}`);

    // Issue 番号をブランチ名から推定
    const issueNumber = this.parseIssueNumber(branch);
    if (issueNumber) {
      const issue = await this.github.issueDetail(issueNumber);
      if (issue) {
        lines.push(`- **関連 Issue:** #${issueNumber} - ${issue.title} (${issue.state})`);
      }
    }

    // PR 情報
    const pr = await this.github.currentPR(branch);
    if (pr) {
      lines.push(`- **関連 PR:** #${pr.number} - ${pr.title} (${pr.state})`);
    }

    // ブランチ固有の変更内容
    lines.push('## このブランチでの変更内容');
    const log = this.git.logFromDefault();
    lines.push(log || '(main と同一)');

    return lines.join('\n');
  }
}
```

---

## フォールバック設計

| 条件 | 動作 |
| --- | --- |
| `gh` CLI あり + 認証済み | 完全なスナップショット（Issue/PR 情報含む） |
| `gh` CLI なし or 未認証 | Git 情報のみ（ブランチ固有コミット + ステータス） |
| Git リポジトリ外 | エラーメッセージのみ |

```typescript
// NestJS: try/catch で分岐
try {
  const pr = execSync('gh pr list ...', { encoding: 'utf-8' });
} catch {
  // gh が無い → Git 情報だけで続行
}
```

```kotlin
// Kotlin: available フラグで事前チェック
val available: Boolean by lazy {
    try {
        ProcessBuilder("gh", "auth", "status").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }
}

fun currentPR(branch: String): PrInfo? {
    if (!available) return null  // gh が無い環境では即 null
    // ...
}
```

| 観点 | NestJS | Kotlin |
| --- | --- | --- |
| チェック方法 | 毎回 `try/catch` | `lazy` で1回だけ確認 |
| コスト | 毎回プロセス起動 | 初回のみ |
| スレッドセーフ | 不要（シングルスレッド） | `lazy` がデフォルトでスレッドセーフ |

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

---

## 補足: `by lazy` — 遅延初期化

```kotlin
private val git by lazy { GitCommandExecutor(File(repoPath)) }
private val github by lazy { GitHubExecutor(File(repoPath)) }
```

`by lazy` はプロパティが最初にアクセスされたときに1回だけ初期化する。
NestJS では `@Inject()` の解決タイミングに近い。

```typescript
// NestJS: Provider はモジュール初期化時に1回だけインスタンス化される
@Injectable()
export class ContextManagerService {
  constructor(
    private git: GitService,       // DI コンテナが1回だけ生成
    private github: GitHubService,
  ) {}
}
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

---

## 補足: KMP とプラットフォーム制限

`ProcessBuilder` は JVM 固有の API のため、
`GitCommandExecutor` と `GitHubExecutor` は `jvmMain` にのみ配置している。

```
plugins/context-manager/src/
├── jvmMain/   ← ProcessBuilder が使える
└── (commonMain は使わない: ProcessBuilder が存在しない)
```

将来 Native (mingwX64) でも Git コマンドを実行したい場合は、
`expect/actual` パターンで抽象化する（docs/03-expect-actual.md 参照）。
