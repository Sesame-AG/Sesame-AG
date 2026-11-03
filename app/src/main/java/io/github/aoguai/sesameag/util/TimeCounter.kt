package io.github.aoguai.sesameag.util

import java.time.Duration
import java.time.Instant

class TimeCounter(private val name: String) {
    companion object {
        private const val RECORD_THRESHOLD_MS = 100L
    }

    private val start: Instant = Instant.now()
    private var lastCheckpoint: Instant = start
    private var stopped: Boolean = false
    private var unexpectedCount: Int = 0
    private val resultMsg = StringBuilder()

    fun close() {
        if (stopped) {
            return
        }
        if (unexpectedCount > 0) {
            stop()
        }
    }

    fun stop() {
        val end = Instant.now()
        val durationMs = Duration.between(start, end).toMillis()
        val detail = resultMsg.toString().removeSuffix(", ")
        val message = if (detail.isBlank()) {
            "$name 耗时: $durationMs ms"
        } else {
            "$name 耗时: $durationMs ms ($detail)"
        }
        if (durationMs >= RECORD_THRESHOLD_MS || unexpectedCount > 0) {
            Log.record(name, message)
        } else {
            Log.debug(name, message)
        }
        stopped = true
    }

    fun countDebug(msg: String) {
        val now = Instant.now()
        val durationMs = Duration.between(lastCheckpoint, now).toMillis()
        Log.debug(name, "$msg 耗时: $durationMs ms")
        lastCheckpoint = now
    }

    fun count(msg: String) {
        val now = Instant.now()
        val durationMs = Duration.between(lastCheckpoint, now).toMillis()
        resultMsg.append(msg).append(":").append(durationMs).append(" ms, ")
        lastCheckpoint = now
    }

    fun countUnexcept(msg: String, exceptMs: Long) {
        val now = Instant.now()
        val durationMs = Duration.between(lastCheckpoint, now).toMillis()
        if (durationMs > exceptMs) {
            resultMsg.append(msg).append(":").append(durationMs)
                .append(" ms(except:").append(exceptMs).append("ms), ")
            unexpectedCount++
        }
        lastCheckpoint = now
    }
}


