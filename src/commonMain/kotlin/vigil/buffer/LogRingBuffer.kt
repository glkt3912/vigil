package vigil.buffer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vigil.model.LogEvent

// 固定サイズのリングバッファ。直近 N 件の LogEvent を保持する。
// メモリ使用量は capacity で上限が保証される。
// Mutex による排他制御で coroutine-safe。
class LogRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val buffer = arrayOfNulls<LogEvent>(capacity)
    private var head = 0
    private var count = 0
    private val mutex = Mutex()

    // イベントを追加する。満杯の場合、最も古いイベントが上書きされる。
    suspend fun add(event: LogEvent) {
        mutex.withLock {
            buffer[head] = event
            head = (head + 1) % capacity
            if (count < capacity) count++
        }
    }

    // バッファ内の全イベントを古い順に返す（スナップショット）。
    // 返されるリストはコピーなので、呼び出し後のバッファ変更の影響を受けない。
    suspend fun snapshot(): List<LogEvent> {
        mutex.withLock {
            if (count == 0) return emptyList()
            val result = ArrayList<LogEvent>(count)
            val start = if (count < capacity) 0 else head
            for (i in 0 until count) {
                val index = (start + i) % capacity
                result.add(buffer[index]!!)
            }
            return result
        }
    }

    suspend fun size(): Int = mutex.withLock { count }

    suspend fun clear() {
        mutex.withLock {
            buffer.fill(null)
            head = 0
            count = 0
        }
    }
}
