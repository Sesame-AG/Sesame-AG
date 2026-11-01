package io.github.aoguai.sesameag.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 复用好友中心的目标应用就绪判定。
 * 优先使用当前进程状态；若不可判定，则通过 HOOK_READY 广播握手做跨进程确认。
 */
object HookReadyChecker {
    private const val TAG = "HookReadyChecker"
    private const val DEFAULT_TIMEOUT_MS = 1_200L

    @JvmStatic
    fun isTargetAppReadyForRpc(
        expectedUserId: String? = UserMap.currentUid,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val normalizedExpectedUserId = expectedUserId?.trim().orEmpty()
        if (isReadyInCurrentProcess(normalizedExpectedUserId)) {
            return true
        }
        val context = SesameApplication.appContext ?: ApplicationHook.appContext ?: return false
        return probe(context, normalizedExpectedUserId, timeoutMs).ready
    }

    @JvmStatic
    fun isCurrentProcessReadyForRpc(expectedUserId: String? = UserMap.currentUid): Boolean {
        return isReadyInCurrentProcess(expectedUserId?.trim().orEmpty())
    }

    private fun isReadyInCurrentProcess(expectedUserId: String): Boolean {
        if (ApplicationHookConstants.shouldBlockRpc() || ApplicationHook.rpcBridge == null) {
            return false
        }
        val loader = ApplicationHook.classLoader ?: return false
        val currentUserId = HookUtil.getUserId(loader)?.trim().orEmpty()
        if (currentUserId.isEmpty()) {
            return false
        }
        return expectedUserId.isEmpty() || expectedUserId == currentUserId
    }

    data class ReadinessResult(
        val replied: Boolean = false,
        val ready: Boolean = false,
        val workflowReady: Boolean = false,
        val currentUserId: String = "",
        val sessionEpoch: Long = 0L,
        val message: String = "no_reply",
    )

    fun probe(
        context: Context,
        expectedUserId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ReadinessResult {
        val appContext = context.applicationContext ?: context
        val requestToken = UUID.randomUUID().toString()
        val result = AtomicReference(ReadinessResult())
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ApplicationHookConstants.BroadcastActions.HOOK_READY_RESULT ||
                    intent.getStringExtra("requestToken") != requestToken
                ) return
                result.set(ReadinessResult(
                    replied = true,
                    ready = intent.getBooleanExtra("ready", false),
                    workflowReady = intent.getBooleanExtra("workflowReady", false),
                    currentUserId = intent.getStringExtra("currentUserId").orEmpty().trim(),
                    sessionEpoch = intent.getLongExtra("sessionEpoch", 0L),
                    message = intent.getStringExtra("message").orEmpty(),
                ))
                latch.countDown()
            }
        }
        return try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ApplicationHookConstants.BroadcastActions.HOOK_READY_RESULT),
                ContextCompat.RECEIVER_EXPORTED
            )
            appContext.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.HOOK_READY).apply {
                setPackage(General.PACKAGE_NAME)
                putExtra("userId", expectedUserId)
                putExtra("requestToken", requestToken)
                putExtra("replyPackage", appContext.packageName)
            })
            // 等待宿主 Hook 就绪广播的关联回包；超时是上限，收到响应后立即结束等待。
            latch.await(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)
            result.get()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "HookReady probe failed", t)
            result.get()
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
}
