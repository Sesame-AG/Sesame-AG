package io.github.aoguai.sesameag.entity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 自定义 RPC 配置项（用于“RPC 调试配置库 + 定时执行”功能）。
 *
 * 数据源固定为模块主目录 `rpcRequest.json`，由 RPC 调试页面保存。
 * 文件内容只支持数组格式：
 * ```json
 * [
 *   {
 *     "name": "查询森林使用道具(示例)",
 *     "methodName": "alipay.antforest.forest.h5.queryMiscInfo",
 *     "requestData": [{"queryBizType":"usingProp","source":"SELF_HOME","version":"20240201"}]
 *   }
 * ]
 * ```
 */
class CustomRpcRequestEntity : MapperEntity() {

    var methodName: String = ""

    /**
     * RPC requestData 的“字符串形态”，通常应为 JSON 数组字符串，例如：`[{"a":1}]`
     */
    var requestData: String = ""

    /**
     * 是否启用“定时执行”
     */
    var scheduleEnabled: Boolean = false

    /**
     * 每日执行次数（仅在 scheduleEnabled=true 时生效）
     */
    var dailyCount: Int = 0

    companion object {
        private const val TAG = "CustomRpcRequestEntity"

        private val mapper: ObjectMapper = JsonUtil.copyMapper()

        @JvmStatic
        fun getList(): List<CustomRpcRequestEntity> {
            return loadList()
        }

        private fun loadList(): List<CustomRpcRequestEntity> {
            val text = readConfigText()
            if (text.isBlank()) return emptyList()

            val root: JsonNode = try {
                mapper.readTree(text)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "解析 rpcRequest.json 失败", t)
                return emptyList()
            }

            if (!root.isArray) {
                Log.runtime(TAG, "rpcRequest.json 仅支持数组格式，已忽略旧格式内容")
                return emptyList()
            }

            val result = ArrayList<CustomRpcRequestEntity>()
            for (node in root.toList()) {
                try {
                    if (node.isObject) {
                        val parsed = parseNodeAsRequest(node)
                        if (parsed != null) result.add(parsed)
                    }
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "解析 RPC 条目失败", t)
                }
            }

            return result.sortedWith { a, b -> a.compareTo(b) }
        }

        private fun readConfigText(): String {
            val rootFile = Files.child(Files.MAIN_DIR, "rpcRequest.json")
            if (rootFile.exists()) {
                val rootText = Files.readFromFile(rootFile).trim()
                if (rootText.isNotBlank()) return rootText
            }

            return ""
        }

        private fun parseNodeAsRequest(node: JsonNode): CustomRpcRequestEntity? {
            val methodName = pickText(node, "methodName", "method", "Method").trim()
            if (methodName.isBlank()) return null

            val displayName = pickText(node, "name", "Name").trim()

            val requestDataNode = pickNode(node, "requestData", "RequestData")
            val requestDataStr = toRequestDataString(requestDataNode)

            val scheduleEnabled = pickBoolean(node, "scheduleEnabled", "scheduleEnable", "enableSchedule", "EnableSchedule")
            val dailyCount = pickInt(node, "dailyCount", "dayCount", "DailyCount")
            val normalizedDailyCount = if (scheduleEnabled) dailyCount.coerceAtLeast(0) else 0

            return CustomRpcRequestEntity().apply {
                this.id = stableId(methodName, requestDataStr)
                this.name = (displayName.ifBlank { methodName }).trim()
                this.methodName = methodName
                this.requestData = requestDataStr
                this.scheduleEnabled = scheduleEnabled
                this.dailyCount = normalizedDailyCount
            }
        }

        private fun pickText(node: JsonNode, vararg keys: String): String {
            for (key in keys) {
                val v = node.get(key) ?: continue
                if (v.isTextual || v.isNumber || v.isBoolean) {
                    return v.asText("")
                }
            }
            return ""
        }

        private fun pickNode(node: JsonNode, vararg keys: String): JsonNode? {
            for (key in keys) {
                if (node.has(key)) return node.get(key)
            }
            return null
        }

        private fun pickBoolean(node: JsonNode, vararg keys: String): Boolean {
            for (key in keys) {
                val v = node.get(key) ?: continue
                if (v.isBoolean) return v.asBoolean(false)
                if (v.isNumber) return v.asInt(0) != 0
                if (v.isTextual) {
                    val s = v.asText("").trim().lowercase()
                    if (s == "true" || s == "1" || s == "yes" || s == "y") return true
                    if (s == "false" || s == "0" || s == "no" || s == "n") return false
                }
            }
            return false
        }

        private fun pickInt(node: JsonNode, vararg keys: String): Int {
            for (key in keys) {
                val v = node.get(key) ?: continue
                if (v.isNumber) return v.asInt(0)
                if (v.isTextual) return v.asText("").trim().toIntOrNull() ?: 0
                if (v.isBoolean) return if (v.asBoolean(false)) 1 else 0
            }
            return 0
        }

        private fun toRequestDataString(requestDataNode: JsonNode?): String {
            return try {
                when {
                    requestDataNode == null || requestDataNode.isNull -> "[{}]"
                    requestDataNode.isTextual -> {
                        val s = requestDataNode.asText("")
                        val t = s.trim()
                        when {
                            t.isBlank() -> "[{}]"
                            t.startsWith("{") && t.endsWith("}") -> "[$t]"
                            else -> s
                        }
                    }
                    requestDataNode.isObject -> {
                        val obj = mapper.writeValueAsString(requestDataNode)
                        if (obj.isBlank()) "[{}]" else "[$obj]"
                    }
                    else -> mapper.writeValueAsString(requestDataNode)
                }.ifBlank { "[{}]" }
            } catch (_: Throwable) {
                "[{}]"
            }
        }

        private fun stableId(methodName: String, requestData: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = (methodName.trim() + "\n" + requestData.trim()).toByteArray(Charsets.UTF_8)
            val digest = md.digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}

