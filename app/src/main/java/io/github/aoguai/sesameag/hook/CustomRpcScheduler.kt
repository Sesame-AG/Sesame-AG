package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.CustomRpcRequestEntity
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Log

/**
 * 自定义 RPC（配置文件 + 定时执行）
 *
 * 合并策略：
 * - 复用现有 `RequestManager.requestString()` 作为唯一 RPC 出口（与 RpcDebug/DebugRpc 同源）
 * - 请求内容来自配置文件：`Android/media/.../sesame-AG/rpcRequest.json`（与「RPC 调试」同一份 JSON）
 * - 执行频率由每条 RPC 自身字段控制：`scheduleEnabled` + `dailyCount`
 * - 计数落地在 Status.intFlagMap，自动按天重置
 *
 * 注意：
 * - 该功能高风险，默认关闭；建议只对少量、安全的查询类 RPC 使用
 * - 采用“保守计数”策略：无论 RPC 返回成功与否，都计入一次，避免失败导致高频重试
 */
object CustomRpcScheduler {

    private const val TAG = "CustomRpcScheduler"

    @JvmStatic
    fun runIfEnabled() {
        try {
            if (BaseModel.customRpcScheduleEnable.value != true) return

            val list = CustomRpcRequestEntity.getList()
            if (list.isEmpty()) return

            val scheduled = list.filter { it.scheduleEnabled && it.dailyCount > 0 }
            if (scheduled.isEmpty()) return

            for (req in scheduled) {
                val id = req.id
                val limit = req.dailyCount
                if (id.isBlank() || limit <= 0) continue

                val countKey = StatusFlags.FLAG_CUSTOM_RPC_SCHEDULE_COUNT_PREFIX + id
                val already = Status.getIntFlagToday(countKey) ?: 0
                if (already >= limit) continue

                // 保守策略：无论结果如何都计入一次，避免重复失败导致高频重试
                Status.setIntFlagToday(countKey, already + 1)

                Log.capture(TAG, "自定义RPC定时[${req.name}] 第${already + 1}/$limit | method=${req.methodName}")
                val result = RequestManager.requestString(req.methodName, req.requestData)

                if (result.isBlank()) {
                    Log.capture(TAG, "返回为空")
                } else {
                    val preview = if (result.length > 800) result.substring(0, 800) + "…" else result
                    Log.capture(TAG, "返回: $preview")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "自定义RPC定时执行异常", t)
        }
    }
}

