package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity

/**
 * 通用任务黑名单管理器
 * 默认黑名单长期有效，自动黑名单通过 DataStore 保存并仅在所属自然日生效。
 */
object TaskBlacklist {
    private const val TAG = "TaskBlacklist"
    private const val BLACKLIST_KEY = "task_blacklist"
    private const val MYBANK_MODULE_NAME = "网商银行"
    private const val LEGACY_MYBANK_WELFARE_MODULE_NAME = "网商福利金"

    private fun canonicalModuleName(moduleName: String): String =
        when (moduleName) {
            LEGACY_MYBANK_WELFARE_MODULE_NAME -> MYBANK_MODULE_NAME
            "AntForestPatrol" -> "保护地巡护"
            "AntFarm" -> "蚂蚁庄园"
            "AntForest" -> "蚂蚁森林"
            "AntMember" -> "会员"
            else -> moduleName
        }

    private fun relatedModuleNames(moduleName: String): LinkedHashSet<String> {
        val canonicalName = canonicalModuleName(moduleName)
        return when (canonicalName) {
            MYBANK_MODULE_NAME -> linkedSetOf(MYBANK_MODULE_NAME, LEGACY_MYBANK_WELFARE_MODULE_NAME)
            "保护地巡护" -> linkedSetOf("保护地巡护", "AntForestPatrol")
            "蚂蚁庄园" -> linkedSetOf("蚂蚁庄园", "AntFarm")
            "蚂蚁森林" -> linkedSetOf("蚂蚁森林", "AntForest")
            "会员" -> linkedSetOf("会员", "AntMember")
            else -> linkedSetOf(canonicalName)
        }
    }

    private fun collectModuleBlacklist(
        allBlacklists: Map<String, Set<String>>,
        moduleName: String,
    ): LinkedHashSet<String> {
        val merged = LinkedHashSet<String>()
        relatedModuleNames(moduleName).forEach { name ->
            merged.addAll(allBlacklists[name].orEmpty())
        }
        return merged
    }

    private data class DailyBlacklist(
        val date: String,
        val modules: Map<String, Set<String>>,
    )

    private fun readModules(value: Any?): Map<String, Set<String>> {
        require(value is Map<*, *>) { "自动黑名单模块数据格式无效" }
        return value.entries.associate { (module, entries) ->
            require(module is String && entries is Collection<*>) { "自动黑名单条目格式无效" }
            module to entries.map { entry ->
                require(entry is String) { "自动黑名单任务条目格式无效" }
                entry
            }.toSet()
        }
    }

    private fun updateBlacklists(change: (MutableMap<String, Set<String>>) -> Unit): DailyBlacklist {
        val today = TimeUtil.getDateStr2()
        val session = AccountSessionCoordinator.currentSession() ?: return DailyBlacklist(today, emptyMap())
        return AccountSessionCoordinator.withCurrentSession(AccountSessionIdentity(session.userId, session.sessionEpoch)) {
            val store = requireNotNull(UserDataStoreManager.getInstance(session.userId))
            val stored = store.get(BLACKLIST_KEY, Map::class.java)
            val saved = stored?.takeIf { it["date"] == today }?.let { readModules(it["modules"]) }.orEmpty()
            val modules = saved.toMutableMap()
            change(modules)
            if (modules.isEmpty()) {
                if (stored != null) store.remove(BLACKLIST_KEY)
            } else if (modules != saved) {
                store.put(BLACKLIST_KEY, mapOf("date" to today, "modules" to modules.mapValues { it.value.toList() }))
            }
            DailyBlacklist(today, modules)
        }
    }

    private fun getDailyBlacklist(): DailyBlacklist =
        try {
            updateBlacklists { }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "获取当日自动黑名单失败", e)
            DailyBlacklist(TimeUtil.getDateStr2(), emptyMap())
        }

    private fun getAllBlacklists(): Map<String, Set<String>> = getDailyBlacklist().modules

    /**
     * 获取所有有黑名单的模块名称
     */
    fun getBlacklistModuleNames(): List<String> = getAllBlacklists().keys.map(::canonicalModuleName).distinct()

    /**
     * 获取指定模块的黑名单
     */
    fun getBlacklist(moduleName: String?): Set<String> {
        if (moduleName.isNullOrBlank()) return emptySet()
        return collectModuleBlacklist(getAllBlacklists(), moduleName)
    }

    /**
     * 检查任务是否在黑名单中
     * 默认黑名单沿用原匹配规则，自动黑名单精确匹配任务标识。
     */
    fun isTaskInBlacklist(
        moduleName: String?,
        taskInfo: String?,
    ): Boolean = findMatch(moduleName, taskInfo) != null

    /** 返回实际命中条目及存储来源，供任务流记录一次诊断。 */
    fun findMatch(moduleName: String?, taskInfo: String?): Pair<String, String>? {
        if (moduleName.isNullOrBlank() || taskInfo.isNullOrBlank()) return null
        relatedModuleNames(moduleName).forEach { name ->
            DEFAULT_BLACKLIST[name]?.firstOrNull { isMatch(taskInfo, it) }?.let {
                return "默认" to it
            }
        }
        val dailyBlacklist = getDailyBlacklist()
        return collectModuleBlacklist(dailyBlacklist.modules, moduleName).firstOrNull { taskInfo == it }?.let {
            "自动（当日，${dailyBlacklist.date}）" to it
        }
    }

    /**
     * 统一的匹配逻辑
     * @param input 传入的待检查字符串 (通常是 taskId)
     * @param blacklistItem 黑名单中的项 (通常是 taskId + taskTitle)
     */
    private fun isMatch(
        input: String,
        blacklistItem: String,
    ): Boolean {
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
     * 添加任务到指定模块的当日自动黑名单
     */
    fun addToBlacklist(
        moduleName: String?,
        taskId: String,
        taskTitle: String = "",
    ) {
        if (moduleName.isNullOrBlank() || taskId.isBlank()) return
        if (addBlacklistEntry(moduleName, taskId)) {
            Log.record(TAG, "模块[$moduleName]任务[${taskTitle.ifBlank { taskId }}]已加入当日自动黑名单 taskId=$taskId")
        }
    }

    private fun addBlacklistEntry(moduleName: String, taskId: String): Boolean {
        val canonicalName = canonicalModuleName(moduleName)
        return try {
            var added = false
            updateBlacklists { allBlacklists ->
                val moduleSet = collectModuleBlacklist(allBlacklists, canonicalName).toMutableSet()
                if (moduleSet.add(taskId)) {
                    added = true
                    relatedModuleNames(canonicalName).forEach { name ->
                        if (name != canonicalName) {
                            allBlacklists.remove(name)
                        }
                    }
                    allBlacklists[canonicalName] = moduleSet
                }
            }
            added
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "保存当日自动黑名单失败", e)
            false
        }
    }

    /**
     * 自动添加任务到模块黑名单。
     *
     * 调用方必须已经把失败分类为“无稳定完成闭环”或“稳定非重试参数错误”。
     * 业务受限、重复领取、系统繁忙、稍后再试、操作频繁等临时/业务终态不要传入这里。
     */
    fun autoAddToBlacklist(
        moduleName: String?,
        taskId: String,
        taskTitle: String = "",
        errorCode: String,
    ) {
        if (moduleName.isNullOrBlank() || taskId.isBlank()) return

        val reason =
            when (errorCode) {
                "400000040" -> "不支持rpc调用"
                "20020012" -> "任务ID为空或无效"
                "ILLEGAL_ARGUMENT" -> "参数错误"
                "TASK_ID_INVALID" -> "任务ID非法"
                "PROMISE_TEMPLATE_NOT_EXIST", "生活记录模板不存在" -> "模板不存在"
                "FAKE_SUCCESS" -> "检测到伪成功"
                "AD_TRAFFIC_RISK", "217", "61002" -> "广告流量风控"
                else -> return
            }

        if (!addBlacklistEntry(moduleName, taskId)) return
        val taskInfo = if (taskTitle.isNotBlank()) "$taskId - $taskTitle" else taskId
        Log.record(
            TAG,
            "模块[$moduleName]任务[$taskInfo]因$reason 已加入当日自动黑名单（用于避免当天重复尝试，不代表账号异常）",
        )
    }
}
