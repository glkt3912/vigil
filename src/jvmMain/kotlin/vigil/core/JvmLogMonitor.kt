package vigil.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import vigil.model.LogEvent
import vigil.model.LogLevel
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import kotlin.io.path.Path

// JVM 向け actual 実装。
// NestJS の chokidar ファイル監視に相当。
// 詳細は docs/06-jvm-implementation.md 参照。
actual class PlatformLogMonitor actual constructor(
    private val config: MonitorConfig,
) {
    private var monitorJob: Job? = null

    // ファイル監視を Flow として公開。
    // callbackFlow で Java NIO のブロッキング API を非同期 Flow に変換。
    actual fun events(): Flow<LogEvent> = callbackFlow {
        val path = Path(config.watchPath)
        val directory = path.parent ?: Path(".")
        val fileName = path.fileName.toString()

        // Java NIO WatchService でディレクトリを監視
        val watchService = FileSystems.getDefault().newWatchService()
        directory.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

        // ファイル末尾の位置を記憶（tail -f と同じ）
        var lastPosition = withContext(Dispatchers.IO) {
            RandomAccessFile(path.toFile(), "r").use { it.length() }
        }

        // 監視ループを別コルーチンで実行
        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val key = watchService.take()  // ブロッキング
                    for (event in key.pollEvents()) {
                        val changedFile = event.context() as? Path ?: continue
                        if (changedFile.toString() != fileName) continue

                        // ファイルの新しい部分を読み取る
                        val newLines = readNewLines(path, lastPosition)
                        lastPosition = newLines.second

                        // 各行を LogEvent に変換して送信
                        for (line in newLines.first) {
                            val logEvent = parseLine(line)
                            trySend(logEvent)
                        }
                    }
                    key.reset()
                }
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                // Flow キャンセル時に awaitClose で WatchService が閉じられる。
                // ブロッキング中の take() が ClosedWatchServiceException を投げるので正常終了。
            }
        }

        // Flow がキャンセルされたら WatchService を閉じる
        awaitClose {
            watchService.close()
        }
    }

    actual fun start(): Job {
        // 実際の監視は events().collect() で開始されるため、
        // ここでは Job を返すだけ
        return monitorJob ?: Job().also { monitorJob = it }
    }

    actual fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    // ファイルの指定位置から末尾まで読み取り、新しい行と新しい位置を返す
    internal fun readNewLines(path: Path, fromPosition: Long): Pair<List<String>, Long> {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val currentLength = file.length()
            if (currentLength <= fromPosition) {
                return emptyList<String>() to fromPosition
            }

            file.seek(fromPosition)
            val lines = mutableListOf<String>()
            var line: String? = file.readLine()
            while (line != null) {
                lines.add(line)
                line = file.readLine()
            }
            return lines to file.filePointer
        }
    }

    // 行をパースして LogEvent に変換（簡易実装）
    internal fun parseLine(line: String): LogEvent {
        // 簡易的なパース: [LEVEL] message 形式を想定
        val levelPattern = Regex("""^\[(\w+)](.*)$""")
        val match = levelPattern.find(line.trim())

        val (level, message) = if (match != null) {
            val levelStr = match.groupValues[1].uppercase()
            val lvl = LogLevel.entries.find { it.name == levelStr } ?: LogLevel.INFO
            lvl to match.groupValues[2].trim()
        } else {
            LogLevel.INFO to line
        }

        return LogEvent(
            id = UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            level = level,
            source = config.watchPath,
            message = message,
        )
    }
}
