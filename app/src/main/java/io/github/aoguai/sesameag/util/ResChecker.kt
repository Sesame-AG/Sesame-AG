package io.github.aoguai.sesameag.util

import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap

/**
 * 响应检查工具类
 * 用于检查JSON响应是否表示成功
 */
object ResChecker {
    
    private val TAG = ResChecker::class.java.simpleName
    private val silentFailureCodes = setOf(
        "400000012",
        "2600000014",
        "NEED_UPGRADE_VILLAGE",
        "FAMILY12",
        "391",
        "G03",
        "600000010",
        "600000031",
        "B000000008",
        "NO_ACTIVE_PATROL",
        "CAN_CLEAN_STATUS_CHANGE",
        "HELP_CLEAN_ALL_FRIEND_LIMIT",
        "PROMISE_TODAY_FINISH_TIMES_LIMIT",
        "ROB_EXPAND_CARD_IN_USE",
        "FRIEND_COLLECTED_LIMIT"
    )
    private val silentFailureKeywords = listOf(
        "权益获取次数超过上限",
        "抽奖活动已结束",
        "垃圾刚被别人收走了",
        "清理次数已达20次上限",
        "保护地“休养生息”中",
        "你现在已经在用收好友能量翻倍卡了",
        "有人抢在你"
    )
    private data class FailureInfo(
        val resultDesc: String,
        val memo: String,
        val resultCode: String,
        val code: String,
        val desc: String
    )
    private data class CheckFailedWindowStat(
        var windowStartMs: Long,
        var count: Int
    )

    private val checkFailedWindowStats = ConcurrentHashMap<String, CheckFailedWindowStat>()
    private const val CHECK_FAILED_SUMMARY_WINDOW_MS = 10 * 60_000L

    private fun formatFailureMessage(context: String?, message: String): String {
        val prefix = context?.trim()?.takeIf { it.isNotEmpty() } ?: return message
        val separator = if (prefix.endsWith(":") || prefix.endsWith("：")) " " else ": "
        return "$prefix$separator$message"
    }
    
    /**
     * 核心检查逻辑
     */
    private fun extractFailureInfo(jo: JSONObject): FailureInfo {
        val resultDesc = jo.optString("resultDesc", "")
        val memo = jo.optString("memo", "")
        val resultCode = jo.optString("resultCode", "")
        val code = jo.optString("code").ifBlank {
            jo.optString("errorCode").ifBlank { resultCode }
        }
        val desc = jo.optString("desc").ifBlank {
            jo.optString("errorMsg").ifBlank { resultDesc }
        }
        return FailureInfo(
            resultDesc = resultDesc,
            memo = memo,
            resultCode = resultCode,
            code = code,
            desc = desc
        )
    }

    private fun isSilentFailure(info: FailureInfo): Boolean {
        if (silentFailureCodes.contains(info.code) || silentFailureKeywords.any { keyword ->
                info.desc.contains(keyword)
            }) {
            return true
        }

        return info.resultDesc.contains("当前参与人数过多") || info.resultDesc.contains("请稍后再试") ||
            info.resultDesc.contains("手速太快") || info.resultDesc.contains("频繁") ||
            info.resultDesc.contains("操作过于频繁") ||
            info.memo.contains("我的小鸡在睡觉中") ||
            info.memo.contains("小鸡在睡觉") ||
            info.memo.contains("无法操作") ||
            info.memo.contains("手速太快") ||
            info.memo.contains("有人抢在你") ||
            info.memo.contains("饲料槽已满") ||
            info.memo.contains("当日达到上限") ||
            info.memo.contains("适可而止") ||
            info.memo.contains("庄园的小鸡太多了") ||
            info.memo.contains("任务已完成") ||
            "I07" == info.resultCode ||
            "FAMILY48" == info.resultCode
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ReturnCount"
    )
    private fun core(tag: String, context: String?, jo: JSONObject): Boolean {
        return try {
            // 检查 success 或 isSuccess 字段为 true
            if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
                return true
            }
            
            // 检查 resultCode
            val resCode = jo.opt("resultCode")
            if (resCode != null) {
                when (resCode) {
                    is Number -> if (resCode.toInt() == 100 || resCode.toInt() == 200) return true
                    is String -> if (Pattern.matches("(?i)SUCCESS|100|200", resCode)) return true
                }
            }
            
            // 检查 memo 字段
            if ("SUCCESS".equals(jo.optString("memo", ""), ignoreCase = true)) {
                return true
            }
            
            // 特殊情况：如果是"人数过多"或"小鸡睡觉"等系统状态，我们认为这不是一个需要记录的"失败"
            val failureInfo = extractFailureInfo(jo)
            RpcOfflineRisk.enterOfflineIfNeeded(tag, jo)
            if (isSilentFailure(failureInfo)) {
                return false // 返回false，但不打印错误日志
            }
            
            // 获取调用栈信息以确定错误来源
            val stackTrace = Thread.currentThread().stackTrace
            val callerInfo = getString(stackTrace)
            val key = "$tag|${context.orEmpty().trim()}|${failureInfo.code}"
            val now = System.currentTimeMillis()
            val stat = checkFailedWindowStats.computeIfAbsent(key) {
                CheckFailedWindowStat(windowStartMs = now, count = 0)
            }

            var summaryLog: String? = null
            var shouldLogDetail = false
            synchronized(stat) {
                if (now - stat.windowStartMs >= CHECK_FAILED_SUMMARY_WINDOW_MS) {
                    summaryLog = buildString {
                        append("Check failed summary: code=")
                        append(failureInfo.code)
                        append(" count=")
                        append(stat.count)
                        append(" windowMs=")
                        append(CHECK_FAILED_SUMMARY_WINDOW_MS)
                    }
                    stat.windowStartMs = now
                    stat.count = 0
                    shouldLogDetail = true
                } else if (stat.count == 0) {
                    shouldLogDetail = true
                }
                stat.count++
            }
            summaryLog?.let { Log.error(tag, formatFailureMessage(context, it)) }
            if (shouldLogDetail) {
                Log.error(tag, formatFailureMessage(context, "Check failed: [来源: $callerInfo] $jo"))
            }
            false
        } catch (t: Throwable) {
            Log.printStackTrace(tag, formatFailureMessage(context, "Error checking JSON success:"), t)
            false
        }
    }
    
    /**
     * 获取调用栈字符串
     */
    private fun getString(stackTrace: Array<StackTraceElement>): String {
        val callerInfo = StringBuilder()
        var foundCount = 0
        val maxStackDepth = 4
        val projectPackage = "io.github.aoguai.sesameag"
        
        // 寻找项目包名下的调用者
        for (element in stackTrace) {
            val className = element.className
            // 只显示项目包名下的类，跳过ResChecker
            if (className.startsWith(projectPackage) && !className.contains("ResChecker")) {
                // 获取类名（保留项目包名后的部分）
                val relativeClassName = className.substring(projectPackage.length + 1)
                if (foundCount > 0) {
                    callerInfo.append(" <- ")
                }
                callerInfo.append(relativeClassName)
                    .append(".")
                    .append(element.methodName)
                    .append(":")
                    .append(element.lineNumber)
                
                foundCount++
                if (foundCount >= maxStackDepth) {
                    break
                }
            }
        }
        
        return callerInfo.toString()
    }
    
    /**
     * 检查JSON对象是否表示成功
     *
     * 成功条件包括：
     * - success == true
     * - isSuccess == true
     * - resultCode == 200 或 "SUCCESS" 或 "100"
     * - memo == "SUCCESS"
     *
     * @param tag 标签
     * @param jo JSON对象
     * @return true 如果成功
     */
    @JvmStatic
    fun checkRes(tag: String, jo: JSONObject): Boolean {
        return core(tag, null, jo)
    }

    @JvmStatic
    fun checkRes(tag: String, context: String, jo: JSONObject): Boolean {
        return core(tag, context, jo)
    }

    @JvmStatic
    fun isSilentFailure(jo: JSONObject): Boolean {
        return try {
            isSilentFailure(extractFailureInfo(jo))
        } catch (_: Throwable) {
            false
        }
    }
    
    /**
     * 检查JSON对象是否表示成功
     *
     * 成功条件包括：
     * - success == true
     * - isSuccess == true
     * - resultCode == 200 或 "SUCCESS" 或 "100"
     * - memo == "SUCCESS"
     *
     * @param tag 标签
     * @param jsonStr JSON对象的字符串表示
     * @return true 如果成功
     */
    @JvmStatic
    @Throws(JSONException::class)
    fun checkRes(tag: String, jsonStr: String?): Boolean {
        return checkRes(tag, null, jsonStr)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun checkRes(tag: String, context: String?, jsonStr: String?): Boolean {
        // 检查null或空字符串
        if (jsonStr.isNullOrBlank()) {
            Log.error(tag, formatFailureMessage(context, "RPC响应为空"))
            return false
        }
        val jo = JSONObject(jsonStr)
        return core(tag, context, jo)
    }
}

