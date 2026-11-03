package io.github.aoguai.sesameag.hook.rpc

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

/** RPC 每日硬阻塞状态只保存在当前账号的 Status 中。 */
object RpcDailyCircuit {
    private const val TAG = "RpcDailyCircuit"
    const val STOP_REASON = "rpc_daily_risk_stop"

    fun isStopResponse(response: JSONObject): Boolean =
        response.optString("offlineReason") == STOP_REASON ||
            response.optJSONObject("_taskFlowStopObject")?.let(::isStopResponse) == true

    internal fun captureIdentity(): AccountSessionIdentity = TaskExecutionReport.current()?.identity ?: AccountSessionIdentity(
        UserMap.currentUid?.trim().orEmpty(),
        AccountSessionCoordinator.currentSessionEpoch(),
    )

    internal fun isSameSession(identity: AccountSessionIdentity): Boolean =
        !Thread.currentThread().isInterrupted &&
            TaskExecutionReport.current()?.executionJob?.isActive != false &&
            identity.userId == UserMap.currentUid?.trim() &&
            AccountSessionCoordinator.checkSessionIdentity(identity.userId, identity.sessionEpoch) is
                io.github.aoguai.sesameag.hook.AccountSessionCheck.Current

    internal fun isCurrent(identity: AccountSessionIdentity): Boolean =
        isSameSession(identity) &&
            io.github.aoguai.sesameag.hook.AccountSlotRegistry.isExecutableUser(identity.userId)

    private fun flag(method: String): String = StatusFlags.FLAG_RPC_DAILY_RISK_STOP_PREFIX + method

    fun isBlockedToday(userId: String?, method: String?): Boolean = StoreAccess.transaction {
        if (userId.isNullOrBlank() || userId != UserMap.currentUid || method.isNullOrBlank()) return@transaction false
        runCatching {
            Status.load(userId, false)
            Status.hasFlagToday(flag(method))
        }.onFailure { Log.printStackTrace(TAG, "读取 RPC 每日停止标识失败: $method", it) }.getOrDefault(false)
    }

    internal fun markBlockedToday(identity: AccountSessionIdentity, method: String?) = StoreAccess.transaction {
        if (!isCurrent(identity) || method.isNullOrBlank()) return@transaction
        val userId = identity.userId
        runCatching {
            Status.load(userId, false)
            val alreadyBlocked = Status.hasFlagToday(flag(method))
            Status.setFlagTodayWhileOffline(flag(method))
            if (!alreadyBlocked) Log.record(TAG, "RPC 硬阻塞，今日停止自动请求: $method")
        }.onFailure { Log.printStackTrace(TAG, "保存 RPC 每日停止标识失败: $method", it) }
        Unit
    }

    fun clearBlockedToday(userId: String?, method: String?) = StoreAccess.transaction {
        if (userId.isNullOrBlank() || userId != UserMap.currentUid || method.isNullOrBlank()) return@transaction
        runCatching {
            Status.load(userId, false)
            if (Status.hasFlagToday(flag(method))) {
                Status.removeFlag(flag(method))
                Log.record(TAG, "设置页刷新成功，解除 RPC 每日停止标识: $method")
            }
        }.onFailure { Log.printStackTrace(TAG, "清除 RPC 每日停止标识失败: $method", it) }
        Unit
    }
}
