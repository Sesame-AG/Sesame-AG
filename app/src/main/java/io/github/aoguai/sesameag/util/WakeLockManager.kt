package io.github.aoguai.sesameag.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为一次后台工作提供独立的 CPU 唤醒锁。
 *
 * AlarmManager 只负责把进程唤醒到广播入口；每个 Receiver 或实际任务必须持有并关闭自己的 lease，
 * 避免并发执行时一个调用方错误释放另一个调用方的唤醒锁。
 */
object WakeLockManager {
    private const val TAG = "WakeLockManager"

    class WakeLockLease internal constructor(
        private val wakeLock: PowerManager.WakeLock?,
        private val source: String,
        private val scheduleId: String?,
        private val acquiredAtElapsedMs: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val heldMs = (SystemClock.elapsedRealtime() - acquiredAtElapsedMs).coerceAtLeast(0L)
            runCatching {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            }.onFailure { error ->
                Log.printStackTrace(TAG, "释放唤醒锁失败[source=$source schedule=${scheduleId.orEmpty()}]", error)
            }
            Log.record(
                TAG,
                "🔑 唤醒锁已释放[source=$source schedule=${scheduleId.orEmpty()} held=${heldMs}ms]",
            )
        }
    }

    @SuppressLint("WakelockTimeout")
    fun acquire(
        context: Context,
        timeoutMs: Long,
        source: String,
        scheduleId: String? = null,
    ): WakeLockLease {
        val acquiredAt = SystemClock.elapsedRealtime()
        val wakeLock =
            runCatching {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame::TaskWakeLock:$source")
                    .apply {
                        setReferenceCounted(false)
                        acquire(timeoutMs)
                    }
            }.onFailure { error ->
                Log.printStackTrace(TAG, "获取唤醒锁失败[source=$source schedule=${scheduleId.orEmpty()}]", error)
            }.getOrNull()

        if (wakeLock != null) {
            Log.record(
                TAG,
                "🔒 唤醒锁已获取[source=$source schedule=${scheduleId.orEmpty()} timeout=${timeoutMs}ms]",
            )
        }
        return WakeLockLease(wakeLock, source, scheduleId, acquiredAt)
    }
}
