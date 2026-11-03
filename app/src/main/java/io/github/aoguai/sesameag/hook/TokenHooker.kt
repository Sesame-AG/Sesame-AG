package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.VipDataIdMap
import org.json.JSONArray
import org.json.JSONObject

object TokenHooker {

    private const val TAG = "TokenHooker"

    /**
     * 方法名 -> handler
     * 注意：这里不需要改，Handler 仍然只接收 JSONObject，UserId 通过闭包在 start 中传入
     */
    private val rpcHandlerMap: MutableMap<String, (JSONObject) -> Unit> = mutableMapOf()

    /**
     * 初始化监听
     * @param currentUserId 从 ApplicationHook 传入的当前用户ID
     */
    fun start(currentUserId: String) {
        if (currentUserId.isEmpty()) {
            Log.error(TAG, "❌ 启动失败：传入的 UserId 为空")
            return
        }
        // 注册蚂蚁庄园 ReferToken 抓取
        // 这里 paramsJson 是 HookUtil 传来的
        // currentUserId 是 start 方法传进来的（闭包捕获）
        registerRpcHandler("com.alipay.adexchange.ad.facade.xlightPlugin") { paramsJson ->
            handleAntFarmToken(currentUserId, paramsJson)
        }

        Log.record(TAG, "✅ VIP业务监听已启动，当前绑定用户: $currentUserId")
    }

    /** 注册 RPC 回调处理器 */
    fun registerRpcHandler(methodName: String, handler: (JSONObject) -> Unit) {
        rpcHandlerMap[methodName] = handler
    }

    /**
     * 调用 handler
     * HookUtil 调用此方法时，不需要传 userId，因为它已经被 start 方法“记住”了
     */
    fun handleRpc(method: String, paramsJson: JSONObject) {
        rpcHandlerMap[method]?.invoke(paramsJson)
    }

    /**
     * 具体业务逻辑
     */
    private fun handleAntFarmToken(userId: String, paramsJson: JSONObject) {
        try {
            val positionRequest = extractPositionRequest(paramsJson) ?: run {
                Log.error(TAG, "未找到 positionRequest")
                return
            }

            val referInfo = positionRequest.optJSONObject("referInfo")
            val token = referInfo?.optString("referToken").orEmpty()
            if (token.isBlank()) return

            // 保存逻辑
            val vipData = IdMapManager.getInstance(VipDataIdMap::class.java)
            vipData.load(userId)
            vipData.add("AntFarmReferToken", token)

            if (vipData.save(userId)) {
                Log.farm("🎁 捕获到蚂蚁庄园 referToken 并已保存, uid=$userId")
            } else {
                Log.error(TAG, "保存 vipdata.json 失败, uid=$userId")
            }

        } catch (e: Exception) {
            Log.error(TAG, "解析 referToken 异常: ${e.message}")
        }
    }

    /**
     * xlightPlugin 的入参结构在不同版本/场景下会有差异：
     * - 可能是 positionRequest 直接挂在根对象
     * - 也可能在 requestData[0].positionRequest
     * - 模块主动发起 RPC 时 requestData 可能是字符串形式的 JSON 数组/对象
     */
    private fun extractPositionRequest(paramsJson: JSONObject): JSONObject? {
        paramsJson.optJSONObject("positionRequest")?.let { return it }

        val requestData = paramsJson.opt("requestData")
        when (requestData) {
            is JSONObject -> requestData.optJSONObject("positionRequest")?.let { return it }
            is JSONArray -> {
                for (i in 0 until requestData.length()) {
                    val item = requestData.optJSONObject(i) ?: continue
                    item.optJSONObject("positionRequest")?.let { return it }
                }
            }
            is String -> extractPositionRequestFromString(requestData)?.let { return it }
        }
        return null
    }

    private fun extractPositionRequestFromString(requestData: String): JSONObject? {
        val trimmed = requestData.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        item.optJSONObject("positionRequest")?.let { return it }
                    }
                    null
                }
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONObject("positionRequest")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

