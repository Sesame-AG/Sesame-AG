package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Hook 侧模块状态导出（内存快照 + HTTP /status）
 *
 * 设计目标：
 * - 刷新频率可控（debounce）
 * - 输出字段向后兼容（保留 framework/timestamp/packageName）
 * - 可观测：offline / rpc
 */
object ModuleStatusReporter {

    private const val DEFAULT_DEBOUNCE_MS = 800L
    private val lastWriteAtMs = AtomicLong(0L)

    @Volatile
    private var lastSnapshot: Map<String, Any?>? = null

    @Volatile
    private var lastFramework: String? = null

    @Volatile
    private var lastPackageName: String? = null

    fun setBaseInfo(framework: String?, packageName: String?) {
        val normalizedFramework = framework?.trim()
        if (!normalizedFramework.isNullOrBlank()) {
            lastFramework = normalizedFramework
        }
        val normalizedPackageName = packageName?.trim()
        if (!normalizedPackageName.isNullOrBlank()) {
            lastPackageName = normalizedPackageName
        }
    }

    fun requestUpdate(reason: String? = null, debounceMs: Long = DEFAULT_DEBOUNCE_MS) {
        ApplicationHookConstants.submitEntryDebounced("module_status_report", debounceMs) {
            updateNow(reason = reason)
        }
    }

    fun updateNow(
        framework: String? = null,
        packageName: String? = null,
        reason: String? = null
    ): Map<String, Any?> {
        setBaseInfo(framework, packageName)

        val snapshot = buildStatusSnapshot(reason)
        lastSnapshot = snapshot
        lastWriteAtMs.set(snapshot["timestamp"] as? Long ?: System.currentTimeMillis())
        return snapshot
    }

    fun getStatusSnapshot(refresh: Boolean = false, reason: String? = null): Map<String, Any?> {
        if (refresh) {
            return updateNow(reason = reason)
        }
        return lastSnapshot ?: buildStatusSnapshot(reason)
    }

    private fun buildStatusSnapshot(reason: String?): Map<String, Any?> {
        val nowMs = System.currentTimeMillis()
        val framework = lastFramework ?: "Unknown"
        val packageName = lastPackageName ?: ""

        val offlineEnabled = ApplicationHookConstants.offline
        val offlineUntilMs = ApplicationHookConstants.offlineUntilMs
        val offlineRemainMs = if (!offlineEnabled) {
            0L
        } else if (offlineUntilMs <= 0L) {
            -1L
        } else {
            max(0L, offlineUntilMs - nowMs)
        }

        val offlineEvents = ApplicationHookConstants.getOfflineEventsSnapshot()
            .takeLast(12)
            .map { e ->
                linkedMapOf(
                    "type" to e.type.name,
                    "atMs" to e.atMs,
                    "cooldownMs" to e.cooldownMs,
                    "untilMs" to e.untilMs,
                    "reason" to e.reason,
                    "detail" to e.detail
                )
            }

        val offlineSnapshot = linkedMapOf<String, Any?>(
            "enabled" to offlineEnabled,
            "offline" to offlineEnabled,
            "untilMs" to offlineUntilMs,
            "remainMs" to offlineRemainMs,
            "reason" to ApplicationHookConstants.offlineReason,
            "detail" to ApplicationHookConstants.offlineReasonDetail,
            "enterCount" to ApplicationHookConstants.offlineEnterCount.get(),
            "exitCount" to ApplicationHookConstants.offlineExitCount.get(),
            "lastEnterAtMs" to ApplicationHookConstants.lastOfflineEnterAtMs,
            "lastExitAtMs" to ApplicationHookConstants.lastOfflineExitAtMs,
            "events" to offlineEvents
        )

        val rpcSnapshot = RequestManager.getMetricsSnapshot()

        return linkedMapOf(
            "framework" to framework,
            "timestamp" to nowMs,
            "packageName" to packageName,
            "reason" to reason,
            "trigger" to reason,
            "process" to ApplicationHook.finalProcessName,
            "offline" to offlineSnapshot,
            "rpc" to rpcSnapshot,
            "permissions" to buildPermissionSnapshot(),
            "meta" to linkedMapOf(
                "process" to ApplicationHook.finalProcessName,
                "lastWriteAtMs" to lastWriteAtMs.get()
            )
        )
    }

    private fun buildPermissionSnapshot(): Map<String, Any?> {
        val context = ApplicationHook.appContext ?: return linkedMapOf(
            "available" to false,
            "reason" to "appContext is null"
        )

        return linkedMapOf(
            "available" to true,
            "contextPackage" to context.packageName,
            "modulePackage" to General.MODULE_PACKAGE_NAME,
            "targetPackage" to General.PACKAGE_NAME,
            "moduleBatteryIgnored" to PermissionUtil.checkBatteryPermissions(context, General.MODULE_PACKAGE_NAME),
            "targetBatteryIgnored" to PermissionUtil.checkBatteryPermissions(context, General.PACKAGE_NAME),
            "moduleExactAlarmAllowed" to if (context.packageName == General.MODULE_PACKAGE_NAME) {
                PermissionUtil.checkExactAlarmPermissions(context, General.MODULE_PACKAGE_NAME)
            } else {
                null
            },
            "targetExactAlarmAllowed" to if (context.packageName == General.PACKAGE_NAME) {
                PermissionUtil.checkExactAlarmPermissions(context, General.PACKAGE_NAME)
            } else {
                null
            }
        )
    }
}
