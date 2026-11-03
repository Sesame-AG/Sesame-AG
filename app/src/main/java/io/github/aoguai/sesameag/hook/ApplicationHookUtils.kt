package io.github.aoguai.sesameag.hook

import android.content.Intent
import io.github.aoguai.sesameag.util.Log
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

object ApplicationHookUtils {
    private const val TAG = "ApplicationHook"

    private val lastReLoginBroadcastAt = AtomicLong(0L)
    private val lastReLoginBroadcastSkipLogAt = AtomicLong(0L)

    private const val RELOGIN_BROADCAST_MIN_INTERVAL_MS = 10_000L

    fun resetToMidnight(calendar: Calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
    }

    /**
     * 通过广播发送重新登录指令（带最小间隔限制，避免广播风暴）
     */
    @JvmStatic
    fun reLoginByBroadcast() {
        try {
            val now = System.currentTimeMillis()
            val previous = lastReLoginBroadcastAt.getAndUpdate { last ->
                if (now - last >= RELOGIN_BROADCAST_MIN_INTERVAL_MS) now else last
            }
            val shouldSend = now - previous >= RELOGIN_BROADCAST_MIN_INTERVAL_MS

            if (!shouldSend) {
                val nowSkip = System.currentTimeMillis()
                val previousSkip = lastReLoginBroadcastSkipLogAt.getAndUpdate { last ->
                    if (nowSkip - last >= RELOGIN_BROADCAST_MIN_INTERVAL_MS) nowSkip else last
                }
                if (nowSkip - previousSkip >= RELOGIN_BROADCAST_MIN_INTERVAL_MS) {
                    Log.runtime(TAG, "reLogin 广播发送过于频繁，已跳过")
                }
                return
            }

            val context = ApplicationHook.appContext ?: return
            context.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.RE_LOGIN))
        } catch (th: Throwable) {
            Log.runtime(TAG, "sendBroadcast reLogin err:")
            Log.printStackTrace(TAG, th)
        }
    }

}

