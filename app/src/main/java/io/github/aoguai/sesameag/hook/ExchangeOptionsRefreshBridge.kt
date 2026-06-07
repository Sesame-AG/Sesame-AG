package io.github.aoguai.sesameag.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    const val TARGET_FARM_IP_CHOUCHOULE = "farm_ip_chouchoule"
    const val TARGET_SPORTS_ENERGY = "sports_energy"
    const val TARGET_FOREST_VITALITY = "forest_vitality"
    const val TARGET_SESAME_GRAIN = "sesame_grain"

    data class RefreshResult(
        val success: Boolean,
        val message: String = "",
        val userId: String = "",
        val options: List<ExchangeOptionRow> = emptyList()
    )

    fun requestRefreshOptions(
        target: String,
        userId: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RefreshResult {
        val context = SesameApplication.appContext ?: ApplicationHook.appContext
            ?: return RefreshResult(false, "模块上下文未就绪")
        val appContext = context.applicationContext
        val requestId = "${target}_${Process.myPid()}_${System.currentTimeMillis()}"
        val resultRef = AtomicReference(RefreshResult(false, "未收到目标应用刷新结果"))
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
                val resultSuccess = intent.getBooleanExtra("success", false)
                val message = intent.getStringExtra("message").orEmpty()
                if (message.isNotBlank()) {
                    Log.runtime(TAG, "refresh result[$target]: $message")
                }
                val userId = intent.getStringExtra("userId").orEmpty()
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
            })
            latch.await(timeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
            resultRef.get()
        } catch (t: Throwable) {
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
