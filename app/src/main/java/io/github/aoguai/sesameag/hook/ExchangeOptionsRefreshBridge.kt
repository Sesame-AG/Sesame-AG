package io.github.aoguai.sesameag.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Settings UI runs in the module process, but RPC bridge lives in the hooked
 * Alipay process. Use a narrow broadcast round-trip to let the target process
 * refresh exchange option maps and persist them before the settings UI reloads.
 */
object ExchangeOptionsRefreshBridge {
    private const val TAG = "ExchangeOptionsRefreshBridge"
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    const val TARGET_MEMBER_POINT = "member_point"
    const val TARGET_BILL_COIN = "bill_coin"
    const val TARGET_MYBANK_WELFARE = "mybank_welfare"
    const val TARGET_BEAN_RIGHT = "bean_right"
    const val TARGET_FARM_PARADISE = "farm_paradise"
    const val TARGET_FARM_IP_CHOUCHOULE = "farm_ip_chouchoule"
    const val TARGET_SPORTS_ENERGY = "sports_energy"
    const val TARGET_FOREST_VITALITY = "forest_vitality"
    const val TARGET_SESAME_GRAIN = "sesame_grain"
    const val TARGET_GOLDEN_BEAN_MALL = "golden_bean_mall"
    const val TARGET_RESERVE = "reserve"

    data class RefreshResult(
        val success: Boolean,
        val message: String = "",
        val userId: String = "",
        val options: List<ExchangeOptionRow> = emptyList()
    )

    fun requestRefreshOptions(
        target: String,
        userId: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        forceRefresh: Boolean = false
    ): RefreshResult {
        val context = SesameApplication.appContext ?: ApplicationHook.appContext
            ?: return RefreshResult(false, "模块上下文未就绪")
        val appContext = context.applicationContext
        val requestId = "${target}_${Process.myPid()}_${System.currentTimeMillis()}"
        val resultRef = AtomicReference(RefreshResult(false, "未收到目标应用刷新结果"))
        val requestedUserId = userId?.trim().orEmpty()
        val lastAdvanceAt = AtomicLong(SystemClock.elapsedRealtime())
        val lastProgress = AtomicReference("等待目标应用响应")
        val idleTimeoutMs = timeoutMs.coerceAtLeast(500L)
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS_RESULT) {
                    return
                }
                if (intent.getStringExtra("requestId") != requestId) {
                    return
                }
                if (intent.getStringExtra("target") != target) {
                    return
                }
                val responseUserId = intent.getStringExtra("userId").orEmpty()
                if (requestedUserId.isNotEmpty() && responseUserId != requestedUserId) return
                val message = intent.getStringExtra("message").orEmpty()
                if (intent.getBooleanExtra("progress", false)) {
                    lastProgress.set(message)
                    if (intent.getBooleanExtra("advances", false)) {
                        lastAdvanceAt.set(SystemClock.elapsedRealtime())
                    }
                    return
                }
                val resultSuccess = intent.getBooleanExtra("success", false)
                if (message.isNotBlank()) {
                    Log.runtime(TAG, "refresh result[$target]: $message")
                }
                val userId = responseUserId
                if (!resultSuccess) {
                    resultRef.set(RefreshResult(false, message, userId))
                    latch.countDown()
                    return
                }

                val optionsJson = intent.getStringExtra("optionsJson")
                if (optionsJson.isNullOrBlank()) {
                    resultRef.set(RefreshResult(false, "远程刷新未返回结构化列表", userId))
                    latch.countDown()
                    return
                }

                val options = parseOptions(optionsJson)
                resultRef.set(
                    if (options != null) {
                        RefreshResult(true, message, userId, options)
                    } else {
                        RefreshResult(false, "远程刷新结构化列表解析失败", userId)
                    }
                )
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
                setPackage(General.PACKAGE_NAME)
                putExtra("requestId", requestId)
                putExtra("target", target)
                putExtra("userId", userId.orEmpty())
                putExtra("forceRefresh", forceRefresh)
            })
            while (latch.count > 0L) {
                val remaining = idleTimeoutMs - (SystemClock.elapsedRealtime() - lastAdvanceAt.get())
                if (remaining <= 0L) {
                    if (latch.count == 0L) break
                    return RefreshResult(false,
                        "连续 ${TimeUnit.MILLISECONDS.toSeconds(idleTimeoutMs)} 秒无新进展，已结束等待；${lastProgress.get()}。目标应用查询未被取消，已保存页可供后续续查。",
                        requestedUserId)
                }
                // 等待跨进程刷新结果；remaining 是无新进展超时的剩余额度，回包会提前唤醒，不固定等满。
                latch.await(remaining, TimeUnit.MILLISECONDS)
            }
            resultRef.get()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            Log.printStackTrace(TAG, "requestRefresh err:", t)
            RefreshResult(false, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun parseOptions(optionsJson: String): List<ExchangeOptionRow>? {
        return runCatching {
            JsonUtil.parseObject(
                optionsJson,
                object : TypeReference<List<ExchangeOptionRow>>() {}
            ).filter { it.id.isNotBlank() }
        }.onFailure {
            Log.printStackTrace(TAG, "parseOptions err:", it)
        }.getOrNull()
    }
}
