package io.github.aoguai.sesameag.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings UI runs in the module process, but RPC bridge lives in the hooked
 * Alipay process. Use a narrow broadcast round-trip to let the target process
 * refresh exchange option maps and persist them before the settings UI reloads.
 */
object ExchangeOptionsRefreshBridge {
    private const val TAG = "ExchangeOptionsRefreshBridge"
    private const val DEFAULT_TIMEOUT_MS = 12_000L

    const val TARGET_MEMBER_POINT = "member_point"
    const val TARGET_BEAN_RIGHT = "bean_right"
    const val TARGET_FARM_PARADISE = "farm_paradise"
    const val TARGET_SPORTS_ENERGY = "sports_energy"
    const val TARGET_FOREST_VITALITY = "forest_vitality"
    const val TARGET_SESAME_GRAIN = "sesame_grain"

    fun requestRefresh(
        target: String,
        userId: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val context = SesameApplication.appContext ?: ApplicationHook.appContext ?: return false
        val appContext = context.applicationContext
        val requestId = "${target}_${Process.myPid()}_${System.currentTimeMillis()}"
        val success = AtomicBoolean(false)
        val received = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS_RESULT) {
                    return
                }
                if (intent.getStringExtra("requestId") != requestId) {
                    return
                }
                received.set(true)
                success.set(intent.getBooleanExtra("success", false))
                val message = intent.getStringExtra("message").orEmpty()
                if (message.isNotBlank()) {
                    Log.runtime(TAG, "refresh result[$target]: $message")
                }
                latch.countDown()
            }
        }

        return try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS_RESULT),
                ContextCompat.RECEIVER_EXPORTED
            )
            appContext.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS).apply {
                putExtra("requestId", requestId)
                putExtra("target", target)
                putExtra("userId", userId.orEmpty())
            })
            latch.await(timeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
            received.get() && success.get()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "requestRefresh err:", t)
            false
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
}
