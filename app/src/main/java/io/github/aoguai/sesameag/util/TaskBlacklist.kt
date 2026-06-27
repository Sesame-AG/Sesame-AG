package io.github.aoguai.sesameag.util

import com.fasterxml.jackson.core.type.TypeReference

/**
 * 通用任务黑名单管理器
 * 使用DataStore持久化存储黑名单数据
 */
object TaskBlacklist {
    private const val TAG = "TaskBlacklist"
    private const val BLACKLIST_KEY = "task_blacklist"

    private fun getAllBlacklists(): Map<String, Set<String>> {
        return try {
            DataStore.getOrCreate(BLACKLIST_KEY, object : TypeReference<Map<String, Set<String>>>() {})
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "获取黑名单列表失败", e)
            emptyMap()
        }
    }

    /**
     * 保存完整的黑名单映射
     */
    private fun saveAllBlacklists(blacklists: Map<String, Set<String>>) {
        try {
            DataStore.put(BLACKLIST_KEY, blacklists)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "保存黑名单失败", e)
        }
    }

    /**
     * 获取所有有黑名单的模块名称
     */
    fun getBlacklistModuleNames(): List<String> {
        return getAllBlacklists().keys.toList()
    }

    /**
     * 获取指定模块的黑名单
     */
    fun getBlacklist(moduleName: String?): Set<String> {
        if (moduleName.isNullOrBlank()) return emptySet()
        return getAllBlacklists()[moduleName] ?: emptySet()
    }

    /**
     * 检查任务是否在黑名单中
     * 采用包含匹配逻辑，确保 ID 或 标题 任意一项命中即可
     */
    fun isTaskInBlacklist(moduleName: String?, taskInfo: String?): Boolean {
        if (moduleName.isNullOrBlank() || taskInfo.isNullOrBlank()) return false

        // 1. 检查内置黑名单
        DEFAULT_BLACKLIST[moduleName]?.let { defaultSet ->
            if (defaultSet.any { isMatch(taskInfo, it) }) return true
        }

        // 2. 检查持久化存储的黑名单
        val moduleBlacklist = getBlacklist(moduleName)
        return moduleBlacklist.any { isMatch(taskInfo, it) }
    }

    /**
     * 统一的匹配逻辑
     * @param input 传入的待检查字符串 (通常是 taskId)
     * @param blacklistItem 黑名单中的项 (通常是 taskId + taskTitle)
     */
    private fun isMatch(input: String, blacklistItem: String): Boolean {
        if (blacklistItem.isBlank()) return false

        // 1. 完全匹配
        if (input == blacklistItem) return true

        // 2. 分隔符匹配（如果黑名单项包含 | 分隔符，说明是新格式）
        if (blacklistItem.contains('|')) {
            val parts = blacklistItem.split('|')
            return parts.any { part ->
                if (part.isBlank()) return@any false
                if (input == part) return@any true
                // 如果 input 包含中文（标题），则允许包含匹配
                val inputHasChinese = input.any { it in '\u4e00'..'\u9fa5' }
                if (inputHasChinese) {
                    return@any input.contains(part) || part.contains(input)
                }
                false
            }
        }

        val itemHasChinese = blacklistItem.any { it in '\u4e00'..'\u9fa5' }
        val inputHasChinese = input.any { it in '\u4e00'..'\u9fa5' }

        return if (itemHasChinese) {
            if (!inputHasChinese) {
                if (blacklistItem.startsWith(input)) {
                    val nextIdx = input.length
                    if (nextIdx < blacklistItem.length && blacklistItem[nextIdx] in '\u4e00'..'\u9fa5') {
                        return true
                    }
                }
                false
            } else {
                blacklistItem.contains(input) || input.contains(blacklistItem)
            }
        } else {
            input.contains(blacklistItem)
        }
    }

    /**
     * 添加任务到指定模块的黑名单
     */
    fun addToBlacklist(moduleName: String?, taskId: String, taskTitle: String = "") {
        if (moduleName.isNullOrBlank() || taskId.isBlank()) return

        // 使用分隔符 | 拼接 ID 和 标题，便于后续精确匹配
        val blacklistItem = if (taskTitle.isNotBlank() && taskId != taskTitle) "$taskId|$taskTitle" else taskId
        val allBlacklists = getAllBlacklists().toMutableMap()
        val moduleSet = allBlacklists[moduleName]?.toMutableSet() ?: mutableSetOf()

        if (moduleSet.add(blacklistItem)) {
            allBlacklists[moduleName] = moduleSet
            saveAllBlacklists(allBlacklists)
        }
    }

    /**
     * 从指定模块的黑名单中移除任务
     */
    fun removeFromBlacklist(moduleName: String?, taskId: String, taskTitle: String = "") {
        if (moduleName.isNullOrBlank() || taskId.isBlank()) return

        val blacklistItem = if (taskTitle.isNotBlank() && taskId != taskTitle) "$taskId|$taskTitle" else taskId
        val allBlacklists = getAllBlacklists().toMutableMap()
        val moduleSet = allBlacklists[moduleName]?.toMutableSet() ?: return

        if (moduleSet.remove(blacklistItem)) {
            allBlacklists[moduleName] = moduleSet
            saveAllBlacklists(allBlacklists)
            Log.record(TAG, "模块[$moduleName]的任务[$blacklistItem]已从黑名单移除")
        }
    }

    /**
     * 清空指定模块的黑名单
     */
    fun clearBlacklist(moduleName: String?) {
        if (moduleName.isNullOrBlank()) return
        val allBlacklists = getAllBlacklists().toMutableMap()
        if (allBlacklists.remove(moduleName) != null) {
            saveAllBlacklists(allBlacklists)
            Log.record(TAG, "模块[$moduleName]的黑名单已清空")
        }
    }

    /**
     * 清空所有模块的黑名单
     */
    fun clearAllBlacklists() {
        saveAllBlacklists(emptyMap())
        Log.record(TAG, "所有任务黑名单已清空")
    }

    /**
     * 自动添加任务到模块黑名单。
     *
     * 调用方必须已经把失败分类为“无稳定完成闭环”或“稳定非重试参数错误”。
     * 业务受限、重复领取、系统繁忙、稍后再试、操作频繁等临时/业务终态不要传入这里。
     */
    fun autoAddToBlacklist(moduleName: String?, taskId: String, taskTitle: String = "", errorCode: String) {
        if (moduleName.isNullOrBlank() || taskId.isBlank()) return

        val reason = when (errorCode) {
            "400000040" -> "不支持rpc调用"
            "20020012" -> "任务ID为空或无效"
            "ILLEGAL_ARGUMENT" -> "参数错误"
            "TASK_ID_INVALID" -> "任务ID非法"
            "PROMISE_TEMPLATE_NOT_EXIST", "生活记录模板不存在" -> "模板不存在"
            "FAKE_SUCCESS" -> "检测到伪成功"
            "AD_TRAFFIC_RISK", "217", "61002" -> "广告流量风控"
            "UNSUPPORTED_GAMEPLAY_TASK" -> "无稳定自动完成RPC闭环"
            else -> return
        }

        addToBlacklist(moduleName, taskId, taskTitle)
        val taskInfo = if (taskTitle.isNotBlank()) "$taskId - $taskTitle" else taskId
        Log.record(
            TAG,
            "模块[$moduleName]任务[$taskInfo]因$reason 已加入自动任务跳过列表（用于避免重复尝试，不代表账号异常）"
        )
    }
}
