package io.github.aoguai.sesameag.task.antOcean

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.AlipayBeach
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.BeachMap
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.HashSet

/**
 * @author Constanline
 * @since 2023/08/01
 */
class AntOcean : ModelTask() {

    /**
     * 申请动作枚举
     */
    enum class ApplyAction(val code: Int, val desc: String) {
        AVAILABLE(0, "可用"),
        NO_STOCK(1, "无库存"),
        ENERGY_LACK(2, "能量不足");

        companion object {
            /**
             * 根据字符串获取对应枚举
             */
            fun fromString(value: String): ApplyAction? {
                for (action in values()) {
                    if (action.name.equals(value, ignoreCase = true)) {
                        return action
                    }
                }
                Log.error("ApplyAction", "Unknown applyAction: $value")
                return null
            }
        }
    }

    /**
     * 保护类型接口常量
     */
    object ProtectType {
        const val DONT_PROTECT = 0
        const val PROTECT_ALL = 1
        const val PROTECT_BEACH = 2
        val nickNames = arrayOf("不保护", "保护全部", "仅保护沙滩")
    }

    /**
     * 清理类型接口常量
     */
    object CleanOceanType {
        const val CLEAN = 0
        const val DONT_CLEAN = 1
        val nickNames = arrayOf("选中清理", "选中不清理")
    }

    companion object {
        private const val TAG = "AntOcean"
        private const val TASK_BLACKLIST_MODULE = "神奇海洋"

        /**
         * 保护类型字段（静态）
         */
        private var userprotectType: ChoiceModelField? = null
    }

    private data class SeaAreaAdvanceState(
        val hasOpenedUnfinishedExtraCollect: Boolean,
        val canCreateExtraCollect: Boolean,
        val hasPendingLockedSeaArea: Boolean,
        val allOpenedSeaAreasCompleted: Boolean
    ) {
        val canRepairNextSeaArea: Boolean
            get() = allOpenedSeaAreasCompleted &&
                hasPendingLockedSeaArea &&
                !hasOpenedUnfinishedExtraCollect &&
                !canCreateExtraCollect

        val currentChapterFullyCompleted: Boolean
            get() = allOpenedSeaAreasCompleted &&
                !hasPendingLockedSeaArea &&
                !hasOpenedUnfinishedExtraCollect &&
                !canCreateExtraCollect
    }

    private data class FishPropTarget(
        val name: String,
        val order: Int,
        val pieceIds: LinkedHashSet<Int>
    )

    /**
     * 海洋任务
     */
    private var dailyOceanTask: BooleanModelField? = null

    /**
     * 清理 | 开启
     */
    private var cleanOcean: BooleanModelField? = null

    /**
     * 清理 | 动作
     */
    private var cleanOceanType: ChoiceModelField? = null

    /**
     * 清理 | 好友列表
     */
    private var cleanOceanList: FriendSelectionModelField? = null

    /**
     * 神奇海洋 | 制作万能拼图
     */
    private var exchangeProp: BooleanModelField? = null

    /**
     * 神奇海洋 | 使用万能拼图
     */
    private var usePropByType: BooleanModelField? = null

    /**
     * 保护 | 开启
     */
    private var protectOcean: BooleanModelField? = null

    /**
     * 保护 | 海洋列表
     */
    private var protectOceanList: SelectAndCountModelField? = null

    private var PDL_task: BooleanModelField? = null

    private val loggedMessages = HashSet<String>()

    private fun buildOceanTaskBizKey(sceneCode: String, taskType: String, taskTitle: String): String {
        return "$sceneCode|$taskType|$taskTitle"
    }
    private var currentOceanUserId: String? = null
    private var lastKnownRubbishNumber: Int = -1
    private var selfOceanCleanRetried = false
    private var noticeLinkedRefreshNeeded = false
    private var oceanHomeRefreshNeeded = false
    private var oceanTasksDoneInvalidatedThisRun = false

    override fun getName(): String {
        return "海洋"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.FOREST
    }

    override fun getIcon(): String {
        return "AntOcean.png"
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("dailyOceanTask", "海洋任务 | 开启", false).withDesc(
                "完成并领取神奇海洋每日任务奖励，为清理和合成提供碎片。"
            ).also { dailyOceanTask = it }
        )
        modelFields.addField(
            BooleanModelField("cleanOcean", "清理 | 开启", false).withDesc(
                "执行清理自己和好友海域垃圾的主流程。"
            ).also { cleanOcean = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "cleanOceanType",
                "清理 | 动作",
                CleanOceanType.DONT_CLEAN,
                CleanOceanType.nickNames
            ).withDesc("决定列表中的好友是清理还是跳过。").also { cleanOceanType = it }
        )
        modelFields.addField(
            FriendSelectionModelField(
                "cleanOceanList",
                "清理 | 好友列表"
            ).withDesc("配置要参与清理规则的好友列表。").also { cleanOceanList = it }
        )
        modelFields.addField(
            BooleanModelField("exchangeProp", "万能拼图 | 制作", false).withDesc(
                "把重复碎片制作成万能拼图。"
            ).also { exchangeProp = it }
        )
        modelFields.addField(
            BooleanModelField("usePropByType", "万能拼图 | 使用", false).withDesc(
                "在可合成目标鱼类时自动消耗万能拼图。"
            ).also { usePropByType = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "userprotectType",
                "保护海域 | 跳过类型",
                ProtectType.DONT_PROTECT,
                ProtectType.nickNames
            ).withDesc("控制哪些海域或沙滩不参与自动推进。").also { userprotectType = it }
        )
        modelFields.addField(
            SelectAndCountModelField(
                "protectOceanList",
                "保护海域 | 列表与数量",
                LinkedHashMap(),
                AlipayBeach::getListAsMapperEntity
            ).withDesc("配置需要保护的海域列表及对应数量配置。").also { protectOceanList = it }
        )
        modelFields.addField(
            BooleanModelField("PDL_task", "潘多拉任务 | 开启", false).withDesc(
                "执行潘多拉活动系列的独立任务与奖励领取。"
            ).also { PDL_task = it }
        )
        return modelFields
    }

    override fun check(): Boolean {
        return when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.ocean("⏸ 当前为只收能量时间【" + BaseModel.energyTime.value + "】，停止执行" + getName() + "任务！"
                )
                false
            }
            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.ocean("💤 模块休眠时间【" + BaseModel.modelSleepTime.value + "】停止执行" + getName() + "任务！"
                )
                false
            }
            else -> true
        }
    }

    override suspend fun runSuspend() {
        try {
            Log.ocean("执行开始-" + getName())
            loggedMessages.clear()
            currentOceanUserId = null
            lastKnownRubbishNumber = -1
            selfOceanCleanRetried = false
            noticeLinkedRefreshNeeded = false
            oceanHomeRefreshNeeded = false
            oceanTasksDoneInvalidatedThisRun = false

            if (!queryOceanStatus()) {
                return
            }

            if (dailyOceanTask?.value == true) {
                receiveTaskAward() // 先推进任务接口，避免好友清理真实次数先被消耗
            }
            queryHomePage()

            if (dailyOceanTask?.value == true) {
                receiveTaskAward() // 清理流程后复查日常任务
                if (noticeLinkedRefreshNeeded) {
                    // 公告提示到存在待完成/待领取任务时，再做一次晚刷新，
                    // 尽量承接前置模块已完成后的联动状态，减少同轮碎片漏领。
                    receiveTaskAward()
                }
            }

            if (userprotectType?.value != ProtectType.DONT_PROTECT) {
                protectOcean() // 保护
            }

            // 日常清理和任务奖励完成后，先做一轮当前系列直连合成。
            querySeaAreaDetailList(allowAdvance = false)

            // 制作万能碎片
            if (exchangeProp?.value == true) {
                exchangeProp()
            }

            // 使用万能拼图
            if (usePropByType?.value == true) {
                usePropByType()
            }

            if (PDL_task?.value == true) {
                doOceanPDLTask() // 潘多拉任务领取
            }
            // 所有清理、任务、万能拼图处理完成后，再统一推进当前海域/系列。
            querySeaAreaDetailList(allowAdvance = true)
        } catch (e: CancellationException) {
            Log.runtime(TAG, "AntOcean 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.ocean("执行结束-" + getName())
        }
    }

    /**
     * 初始化沙滩任务。
     * 通过调用 AntOceanRpc 接口查询养成列表，
     * 并将符合条件的任务加入 BeachMap。
     */
    fun initBeach() {
        try {
            val response = AntOceanRpcCall.queryCultivationList()
            val jsonResponse = JsonUtil.parseJSONObjectOrNull(response) ?: run {
                IdMapManager.getInstance(BeachMap::class.java).load()
                return
            }
            if (ResChecker.checkRes(TAG, jsonResponse)) {
                val cultivationList = jsonResponse.optJSONArray("cultivationItemVOList")
                if (cultivationList != null) {
                    for (i in 0 until cultivationList.length()) {
                        val item = cultivationList.getJSONObject(i)
                        val templateSubType = item.getString("templateSubType")
                        // 检查 applyAction 是否为 AVAILABLE
                        val actionStr = item.getString("applyAction")
                        val action = ApplyAction.fromString(actionStr)
                        if (action == ApplyAction.AVAILABLE) {
                            val templateCode = item.getString("templateCode") // 业务id
                            val cultivationName = item.getString("cultivationName")
                            val energy = item.getInt("energy")
                            when (userprotectType?.value) {
                                ProtectType.PROTECT_ALL -> {
                                    IdMapManager.getInstance(BeachMap::class.java)
                                        .add(templateCode, "$cultivationName(${energy}g)")
                                }
                                ProtectType.PROTECT_BEACH -> {
                                    if (templateSubType != "BEACH") {
                                        IdMapManager.getInstance(BeachMap::class.java)
                                            .add(templateCode, "$cultivationName(${energy}g)")
                                    }
                                }
                                else -> {
                                    // DONT_PROTECT 或其他，不做处理
                                }
                            }
                        }
                    }
                    Log.runtime(TAG, "初始化沙滩数据成功。")
                }
                // 将所有筛选结果保存到 BeachMap
                IdMapManager.getInstance(BeachMap::class.java).save()
            } else {
                Log.runtime(jsonResponse.optString("resultDesc", "未知错误"))
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "JSON 解析错误：", e)
            IdMapManager.getInstance(BeachMap::class.java).load() // 若出现异常则加载保存的 BeachMap 备份
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "初始化沙滩任务时出错", e)
            IdMapManager.getInstance(BeachMap::class.java).load() // 加载保存的 BeachMap 备份
        }
    }

    private suspend fun queryOceanStatus(): Boolean {
        return try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryOceanStatus()) ?: return false
            if (ResChecker.checkRes(TAG, jo)) {
                if (!jo.optBoolean("opened", false)) {
                    Log.ocean("神奇海洋🌊[未开通或未完成引导，本轮跳过]")
                    false
                } else {
                    initBeach()
                    true
                }
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryOceanStatus err:")
            Log.printStackTrace(TAG, t)
            false
        }
    }

    private fun queryHomePageData(showTaskPanel: Boolean = false): JSONObject? {
        val joHomePage = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryHomePage(showTaskPanel)) ?: return null
        if (!ResChecker.checkRes(TAG, joHomePage)) {
            Log.runtime(TAG, extractOceanResultDesc(joHomePage))
            return null
        }
        return joHomePage
    }

    private fun rememberSelfOceanState(userInfoVO: JSONObject?) {
        if (userInfoVO == null) {
            return
        }
        val userId = userInfoVO.optString("userId")
        if (userId.isNotBlank()) {
            currentOceanUserId = userId
        }
        if (userInfoVO.has("rubbishNumber")) {
            lastKnownRubbishNumber = userInfoVO.optInt("rubbishNumber", 0).coerceAtLeast(0)
        }
    }

    private fun extractOceanResultDesc(jo: JSONObject): String {
        return jo.optString("resultDesc").ifBlank {
            jo.optString("memo").ifBlank {
                jo.optString("desc").ifBlank {
                    jo.optString("errorMsg").ifBlank {
                        jo.toString()
                    }
                }
            }
        }
    }

    private fun isHelpCleanLimit(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode").ifBlank { jo.optString("code") }
        val desc = extractOceanResultDesc(jo)
        return resultCode == "HELP_CLEAN_LIMIT" ||
            resultCode == "HELP_CLEAN_ALL_FRIEND_LIMIT" ||
            desc.contains("清理好友海域的次数已达上限") ||
            desc.contains("清理次数已达20次上限") ||
            desc.contains("已达20次上限")
    }

    private fun isOceanFriendNotOpen(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode").ifBlank { jo.optString("code") }
        val desc = extractOceanResultDesc(jo)
        return resultCode == "USER_NOT_OPEN" ||
            resultCode == "NOT_OPEN" ||
            desc.contains("未开通") ||
            desc.contains("未完成引导")
    }

    private fun markHelpCleanLimit(jo: JSONObject) {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
            return
        }
        Status.setFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)
        Log.ocean("神奇海洋🌊帮助清理次数已达上限：${extractOceanResultDesc(jo)}，已记录为当日限制，本轮剩余好友清理全部跳过")
    }

    private fun isSelfCleanTask(taskType: String, taskTitle: String): Boolean {
        val normalizedTaskType = taskType.uppercase()
        return normalizedTaskType == "CLEAN_RUBBISH_2_EVERY_DAY" ||
            (normalizedTaskType.contains("SELF") && normalizedTaskType.contains("CLEAN")) ||
            (normalizedTaskType.contains("OWN") && normalizedTaskType.contains("CLEAN")) ||
            (normalizedTaskType.contains("CLEAN_RUBBISH") &&
                !normalizedTaskType.contains("FRIEND") &&
                !normalizedTaskType.contains("HELP")) ||
            taskTitle.contains("清理自己") ||
            taskTitle.contains("自己海域") ||
            taskTitle.contains("自己垃圾")
    }

    private fun isHelpFriendCleanTask(taskType: String, taskTitle: String): Boolean {
        val normalizedTaskType = taskType.uppercase()
        return normalizedTaskType.contains("HELP_CLEAN") ||
            normalizedTaskType.contains("FRIENDRUBBISHCLEAN") ||
            normalizedTaskType.contains("FRIEND_RUBBISH_CLEAN") ||
            normalizedTaskType.contains("CLEAN_FRIEND") ||
            taskTitle.contains("帮好友清理") ||
            taskTitle.contains("帮助好友清理") ||
            taskTitle.contains("好友清理") ||
            (taskTitle.contains("好友") && taskTitle.contains("清理") && taskTitle.contains("垃圾"))
    }

    private fun isOceanActionTask(taskType: String, taskTitle: String): Boolean {
        return !isHelpFriendCleanTask(taskType, taskTitle) &&
            (isSelfCleanTask(taskType, taskTitle) ||
                taskType.startsWith("CLEAN_") ||
                taskTitle.contains("清理好友") ||
                taskTitle.contains("清理海域"))
    }

    private fun resolveOceanTaskFinishSource(taskType: String, taskTitle: String, bizInfo: JSONObject): String {
        val jumpUrl = bizInfo.optString("taskJumpUrl")
        val fallbackUrl = bizInfo.optString("fallbackUrl")
        val taskDesc = bizInfo.optString("taskDesc")
        val indicators = listOf(taskType, taskTitle, taskDesc, jumpUrl, fallbackUrl)
        return if (
            taskType.startsWith("BUSINESS_LIGHTS") ||
            taskType.contains("XLIGHT", ignoreCase = true) ||
            indicators.any { indicator ->
                indicator.contains("iepTaskType=", ignoreCase = true) ||
                    indicator.contains("renderConfigKey=", ignoreCase = true) ||
                    indicator.contains("adPosId=", ignoreCase = true)
            }
        ) {
            "ADBASICLIB"
        } else {
            "ANTFOCEAN"
        }
    }

    private fun markOceanTasksDoneInvalidated() {
        oceanTasksDoneInvalidatedThisRun = true
    }

    private fun markOceanNoticeLinkedRefreshNeeded() {
        noticeLinkedRefreshNeeded = true
        markOceanTasksDoneInvalidated()
    }

    private fun markOceanHomeRefreshNeeded() {
        oceanHomeRefreshNeeded = true
        markOceanTasksDoneInvalidated()
    }

    private suspend fun refreshOceanHomeIfNeeded(reason: String) {
        if (!oceanHomeRefreshNeeded) {
            return
        }
        oceanHomeRefreshNeeded = false
        val refreshedHomePage = queryHomePageData(showTaskPanel = true)
        if (refreshedHomePage == null) {
            Log.runtime(TAG, "神奇海洋🌊[$reason]刷新主页失败，保留当前收球结果")
            return
        }
        Log.ocean("神奇海洋🌊[$reason]刷新主页，检查新增能量球")
        refreshedHomePage.optJSONArray("bubbleVOList")?.let { collectEnergy(it) }
        rememberSelfOceanState(refreshedHomePage.optJSONObject("userInfoVO"))
    }

    private fun isActiveExtraCollect(extraCollectVO: JSONObject?): Boolean {
        if (extraCollectVO == null) {
            return false
        }
        val status = extraCollectVO.optString("status")
        if (status.isBlank()) {
            return extraCollectVO.has("expireAt") || extraCollectVO.has("expireAtLeftDays")
        }
        return status != "FINISHED" &&
            status != "UNOPEN" &&
            status != "NOT_OPEN" &&
            status != "WAIT_OPEN" &&
            status != "TO_OPEN"
    }

    private fun getFishList(container: JSONObject?): JSONArray? {
        if (container == null) {
            return null
        }
        return container.optJSONArray("fishVO") ?: container.optJSONArray("fishVOList")
    }

    private fun isSeaAreaLocked(seaAreaVO: JSONObject): Boolean {
        return when (seaAreaVO.optString("status")) {
            "WAIT_FOR_UNLOCK", "LOCKED", "TO_OPEN", "NOT_OPEN" -> true
            else -> false
        }
    }

    private fun isFishUnlocked(fish: JSONObject): Boolean {
        if (fish.optBoolean("unlock", false)) {
            return true
        }
        return when (fish.optString("status").uppercase()) {
            "UNLOCK", "UNLOCKED", "OBTAINED", "FINISHED" -> true
            else -> false
        }
    }

    private fun areAllFishUnlocked(fishVOs: JSONArray?): Boolean {
        if (fishVOs == null || fishVOs.length() == 0) {
            return true
        }
        for (i in 0 until fishVOs.length()) {
            val fish = fishVOs.optJSONObject(i) ?: return false
            if (!isFishUnlocked(fish)) {
                return false
            }
        }
        return true
    }

    private fun isSeaAreaFullyCompleted(seaAreaVO: JSONObject): Boolean {
        if (isSeaAreaLocked(seaAreaVO)) {
            return false
        }
        if (!areAllFishUnlocked(getFishList(seaAreaVO))) {
            return false
        }
        val extraCollectVO = seaAreaVO.optJSONObject("seaAreaExtraCollectVO") ?: return true
        val extraFishList = getFishList(extraCollectVO)
        if (isActiveExtraCollect(extraCollectVO)) {
            return areAllFishUnlocked(extraFishList)
        }
        if (extraFishList != null && extraFishList.length() > 0) {
            return areAllFishUnlocked(extraFishList)
        }
        return extraCollectVO.optString("status").equals("FINISHED", ignoreCase = true)
    }

    private fun buildSeaAreaAdvanceState(jo: JSONObject, seaAreaVOs: JSONArray): SeaAreaAdvanceState {
        var hasOpenedUnfinishedExtraCollect = false
        var hasPendingLockedSeaArea = false
        var allOpenedSeaAreasCompleted = true

        for (i in 0 until seaAreaVOs.length()) {
            val seaAreaVO = seaAreaVOs.optJSONObject(i) ?: continue
            if (isSeaAreaLocked(seaAreaVO)) {
                hasPendingLockedSeaArea = true
                continue
            }
            val extraCollectVO = seaAreaVO.optJSONObject("seaAreaExtraCollectVO")
            if (isActiveExtraCollect(extraCollectVO)) {
                hasOpenedUnfinishedExtraCollect = true
            }
            if (!isSeaAreaLocked(seaAreaVO) && !isSeaAreaFullyCompleted(seaAreaVO)) {
                allOpenedSeaAreasCompleted = false
            }
        }

        return SeaAreaAdvanceState(
            hasOpenedUnfinishedExtraCollect = hasOpenedUnfinishedExtraCollect,
            canCreateExtraCollect = jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false),
            hasPendingLockedSeaArea = hasPendingLockedSeaArea,
            allOpenedSeaAreasCompleted = allOpenedSeaAreasCompleted
        )
    }

    private fun retrySelfOceanCleanIfNeeded(taskTitle: String): Boolean {
        if (cleanOcean?.value != true) {
            return false
        }
        if (selfOceanCleanRetried) {
            return false
        }
        val userId = currentOceanUserId
        if (userId.isNullOrBlank()) {
            return false
        }
        selfOceanCleanRetried = true
        val joHomePage = queryHomePageData(showTaskPanel = true) ?: return false
        val userInfoVO = joHomePage.optJSONObject("userInfoVO")
        rememberSelfOceanState(userInfoVO)
        val refreshedUserId = userInfoVO?.optString("userId").orEmpty().ifBlank { userId }
        val refreshedRubbishNumber = userInfoVO?.optInt("rubbishNumber", 0)?.coerceAtLeast(0) ?: 0
        if (refreshedRubbishNumber <= 0) {
            return false
        }
        Log.ocean("神奇海洋🌊[$taskTitle]触发自清理补偿刷新：$refreshedRubbishNumber 次")
        cleanOcean(refreshedUserId, refreshedRubbishNumber)
        return true
    }

    private suspend fun queryHomePage() {
        try {
            val joHomePage = queryHomePageData(showTaskPanel = true) ?: return
            if (joHomePage.has("bubbleVOList")) {
                collectEnergy(joHomePage.getJSONArray("bubbleVOList"))
            }
            val userInfoVO = joHomePage.optJSONObject("userInfoVO")
            rememberSelfOceanState(userInfoVO)
            if (cleanOcean?.value == true) {
                val rubbishNumber = lastKnownRubbishNumber.coerceAtLeast(0)
                val userId = currentOceanUserId.orEmpty()
                cleanOcean(userId, rubbishNumber)
            }
            val ipVO = userInfoVO?.optJSONObject("ipVO")
            if (ipVO != null) {
                val surprisePieceNum = ipVO.optInt("surprisePieceNum", 0)
                if (surprisePieceNum > 0) {
                    ipOpenSurprise()
                }
            }
            queryMiscInfo()
            queryNotice()
            queryRefinedMaterial()
            queryReplicaHome()
            if (cleanOcean?.value == true) {
                queryUserRanking() // 清理
            }
            refreshOceanHomeIfNeeded("清理流程")
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryHomePage err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun queryMiscInfo() {
        try {
            val s = AntOceanRpcCall.queryMiscInfo()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val miscHandlerVOMap = jo.optJSONObject("miscHandlerVOMap") ?: return
                val emergency = miscHandlerVOMap.optJSONObject("EMERGENCY")
                if (emergency?.optBoolean("showEmergency") == true || emergency?.optBoolean("showIntroduceBubble") == true) {
                    Log.ocean("神奇海洋🌊[检测到海洋活动入口]")
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryMiscInfo err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun collectEnergy(bubbleVOList: JSONArray) {
        try {
            for (i in 0 until bubbleVOList.length()) {
                val bubble = bubbleVOList.getJSONObject(i)
                if ("ocean" != bubble.getString("channel")) {
                    continue
                }
                if ("AVAILABLE" == bubble.getString("collectStatus")) {
                    val bubbleId = bubble.getLong("id")
                    val userId = bubble.getString("userId")
                    val s = AntForestRpcCall.collectEnergy("", userId, bubbleId)
                    val jo = JsonUtil.parseJSONObjectOrNull(s) ?: continue
                    if (ResChecker.checkRes(TAG, jo)) {
                        val retBubbles = jo.optJSONArray("bubbles")
                        if (retBubbles != null) {
                            for (j in 0 until retBubbles.length()) {
                                val retBubble = retBubbles.optJSONObject(j)
                                if (retBubble != null) {
                                    val collectedEnergy = retBubble.getInt("collectedEnergy")
                                    Log.ocean("神奇海洋🌊收取[${UserMap.getMaskName(userId)}]#${collectedEnergy}g")
                                    Toast.show("海洋能量🌊收取[${UserMap.getMaskName(userId)}]#${collectedEnergy}g")
                                }
                            }
                        }
                    } else {
                        Log.runtime(TAG, jo.getString("resultDesc"))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryHomePage err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun cleanOcean(userId: String, rubbishNumber: Int) {
        if (userId.isBlank() || rubbishNumber <= 0) {
            return
        }
        try {
            var cleanedTimes = 0
            for (i in 0 until rubbishNumber) {
                val s = AntOceanRpcCall.cleanOcean(userId)
                val jo = JsonUtil.parseJSONObjectOrNull(s) ?: continue
                if (ResChecker.checkRes(TAG, jo)) {
                    val cleanRewardVOS = jo.getJSONArray("cleanRewardVOS")
                    checkReward(cleanRewardVOS)
                    Log.ocean("神奇海洋🌊[清理:${UserMap.getMaskName(userId)}海域]")
                    markOceanHomeRefreshNeeded()
                    cleanedTimes += 1
                } else {
                    Log.runtime(TAG, extractOceanResultDesc(jo))
                    break
                }
            }
            if (cleanedTimes > 0 && lastKnownRubbishNumber >= 0) {
                lastKnownRubbishNumber = (lastKnownRubbishNumber - cleanedTimes).coerceAtLeast(0)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "cleanOcean err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun ipOpenSurprise() {
        try {
            val s = AntOceanRpcCall.ipOpenSurprise()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val rewardVOS = jo.getJSONArray("surpriseRewardVOS")
                checkReward(rewardVOS)
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "ipOpenSurprise err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun combineFish(fishId: String) {
        try {
            val s = AntOceanRpcCall.combineFish(fishId)
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val fishDetailVO = jo.getJSONObject("fishDetailVO")
                val name = fishDetailVO.getString("name")
                Log.ocean("神奇海洋🌊[$name]合成成功")
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "combineFish err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun checkReward(rewards: JSONArray) {
        try {
            for (i in 0 until rewards.length()) {
                val reward = rewards.getJSONObject(i)
                val name = reward.getString("name")
                val attachReward = reward.getJSONArray("attachRewardBOList")
                if (attachReward.length() > 0) {
                    Log.ocean("神奇海洋🌊[获得:" + name + "碎片]")
                    var canCombine = true
                    for (j in 0 until attachReward.length()) {
                        val detail = attachReward.getJSONObject(j)
                        if (detail.optInt("count", 0) == 0) {
                            canCombine = false
                            break
                        }
                    }
                    if (canCombine && shouldCombineFish(reward)) {
                        val fishId = reward.opt("id")?.toString().orEmpty()
                        if (fishId.isBlank()) {
                            continue
                        }
                        combineFish(fishId)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "checkReward err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun collectReplicaAsset(canCollectAssetNum: Int) {
        try {
            for (i in 0 until canCollectAssetNum) {
                val s = AntOceanRpcCall.collectReplicaAsset()
                val jo = JsonUtil.parseJSONObjectOrNull(s) ?: continue
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.ocean("神奇海洋🌊[学习海洋科普知识]#潘多拉能量+1")
                } else {
                    Log.runtime(TAG, jo.getString("resultDesc"))
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "collectReplicaAsset err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun unLockReplicaPhase(replicaCode: String, replicaPhaseCode: String) {
        try {
            val s = AntOceanRpcCall.unLockReplicaPhase(replicaCode, replicaPhaseCode)
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val name = jo.getJSONObject("currentPhaseInfo").getJSONObject("extInfo").getString("name")
                Log.ocean("神奇海洋🌊迎回[$name]")
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "unLockReplicaPhase err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun queryReplicaHome() {
        try {
            val s = AntOceanRpcCall.queryReplicaHome()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                if (jo.has("userReplicaAssetVO")) {
                    val userReplicaAssetVO = jo.getJSONObject("userReplicaAssetVO")
                    val canCollectAssetNum = userReplicaAssetVO.getInt("canCollectAssetNum")
                    collectReplicaAsset(canCollectAssetNum)
                }
                if (jo.has("userCurrentPhaseVO")) {
                    val userCurrentPhaseVO = jo.getJSONObject("userCurrentPhaseVO")
                    val phaseCode = userCurrentPhaseVO.getString("phaseCode")
                    val code = jo.getJSONObject("userReplicaInfoVO").getString("code")
                    if ("COMPLETED" == userCurrentPhaseVO.getString("phaseStatus")) {
                        unLockReplicaPhase(code, phaseCode)
                    }
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryReplicaHome err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun queryOceanPropList() {
        try {
            val s = AntOceanRpcCall.queryOceanPropList()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                repairSeaArea()
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryOceanPropList err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun repairSeaArea() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.repairSeaArea()) ?: return
            val resultCode = jo.optString("resultCode")
            val resultDesc = jo.optString("resultDesc")
            if (resultCode == "SEA_AREA_EXTRA_COLLECT_COLLECTING" ||
                resultDesc.contains("先结束正在进行的限时挑战")
            ) {
                Log.ocean("神奇海洋[限时挑战进行中，跳过开启下一海域]")
                return
            }
            if (ResChecker.checkRes(TAG, jo)) {
                val seaAreaName = jo.optJSONObject("currentSeaAreaVO")?.optString("name")
                    ?: jo.optJSONObject("seaAreaVO")?.optString("name")
                    ?: jo.optJSONObject("seaAreaInfoVO")?.optString("name")
                    ?: jo.optString("seaAreaName")
                if (seaAreaName.isNotBlank()) {
                    Log.ocean("神奇海洋🌊[开启修复:$seaAreaName]")
                } else {
                    Log.ocean("神奇海洋🌊[开启新海域修复]")
                }
            } else {
                Log.runtime(TAG, resultDesc.ifBlank { jo.toString() })
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "repairSeaArea err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun createSeaAreaExtraCollect() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.createSeaAreaExtraCollect()) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val extraCollectVO = jo.optJSONObject("seaAreaExtraCollectVO")
                val extraCollectName = extraCollectVO?.optJSONObject("seaAreaVO")?.optString("name")
                    ?: extraCollectVO?.optString("name")
                    ?: "限时挑战"
                Log.ocean("神奇海洋🌊[开启限时挑战:$extraCollectName]")
            } else {
                Log.runtime(TAG, jo.optString("resultDesc").ifBlank { jo.toString() })
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "createSeaAreaExtraCollect err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun switchOceanChapter(currentChapterFullyCompleted: Boolean) {
        if (!currentChapterFullyCompleted) {
            return
        }
        val s = AntOceanRpcCall.queryOceanChapterList()
        try {
            var jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val currentChapterCode = jo.getString("currentChapterCode")
                val chapterVOs = jo.getJSONArray("userChapterDetailVOList")
                var hasReachedCurrentChapter = false
                var dstChapterCode = ""
                var dstChapterName = ""
                for (i in 0 until chapterVOs.length()) {
                    val chapterVO = chapterVOs.getJSONObject(i)
                    val repairedSeaAreaNum = chapterVO.getInt("repairedSeaAreaNum")
                    val seaAreaNum = chapterVO.getInt("seaAreaNum")
                    if (chapterVO.getString("chapterCode") == currentChapterCode) {
                        hasReachedCurrentChapter = true
                    } else {
                        if (repairedSeaAreaNum >= seaAreaNum || !chapterVO.getBoolean("chapterOpen")) {
                            continue
                        }
                        if (!hasReachedCurrentChapter) {
                            continue
                        }
                        dstChapterName = chapterVO.getString("chapterName")
                        dstChapterCode = chapterVO.getString("chapterCode")
                        break
                    }
                }
                if (dstChapterCode.isNotEmpty()) {
                    val switchS = AntOceanRpcCall.switchOceanChapter(dstChapterCode)
                    jo = JsonUtil.parseJSONObjectOrNull(switchS) ?: return
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.ocean("神奇海洋🌊切换到[$dstChapterName]系列")
                    } else {
                        Log.runtime(TAG, jo.getString("resultDesc"))
                    }
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryUserRanking err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun querySeaAreaDetailList(allowAdvance: Boolean) {
        try {
            var detailJo = querySeaAreaDetailData() ?: return
            val firstSeaAreaVOs = detailJo.optJSONArray("seaAreaVOs") ?: return
            var combinedAnyFish = false
            for (i in 0 until firstSeaAreaVOs.length()) {
                val seaAreaVO = firstSeaAreaVOs.optJSONObject(i) ?: continue
                combinedAnyFish = combineCompletedFish(getFishList(seaAreaVO)) || combinedAnyFish
                val seaAreaExtraCollectVO = seaAreaVO.optJSONObject("seaAreaExtraCollectVO")
                if (seaAreaExtraCollectVO != null) {
                    combinedAnyFish = combineCompletedFish(getFishList(seaAreaExtraCollectVO)) || combinedAnyFish
                }
            }

            if (!allowAdvance) {
                return
            }

            if (combinedAnyFish) {
                detailJo = querySeaAreaDetailData() ?: return
            }
            val seaAreaVOs = detailJo.optJSONArray("seaAreaVOs") ?: return
            val advanceState = buildSeaAreaAdvanceState(detailJo, seaAreaVOs)

            if (advanceState.canCreateExtraCollect) {
                createSeaAreaExtraCollect()
                return
            }
            if (advanceState.canRepairNextSeaArea) {
                queryOceanPropList()
                return
            }
            switchOceanChapter(advanceState.currentChapterFullyCompleted)
        } catch (t: Throwable) {
            Log.runtime(TAG, "querySeaAreaDetailList err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun querySeaAreaDetailData(): JSONObject? {
        val s = AntOceanRpcCall.querySeaAreaDetailList()
        val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return null
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.runtime(TAG, extractOceanResultDesc(jo))
            return null
        }
        return jo
    }

    private suspend fun combineCompletedFish(fishVOs: JSONArray?): Boolean {
        if (fishVOs == null) {
            return false
        }
        var attemptedCombine = false
        for (j in 0 until fishVOs.length()) {
            val fishVO = fishVOs.optJSONObject(j) ?: continue
            if (!shouldCombineFish(fishVO)) {
                continue
            }
            val fishId = fishVO.opt("id")?.toString().orEmpty()
            if (fishId.isNotBlank()) {
                attemptedCombine = true
                combineFish(fishId)
            }
        }
        return attemptedCombine
    }

    private fun shouldCombineFish(fish: JSONObject): Boolean {
        if (fish.optBoolean("unlock", false)) {
            return false
        }
        if (fish.optBoolean("isCombine", false)) {
            return true
        }

        val status = fish.optString("status")
        if (
            status.equals("COMPLETED", ignoreCase = true) ||
            status.equals("CAN_COMBINE", ignoreCase = true) ||
            status.equals("COMBINABLE", ignoreCase = true)
        ) {
            return true
        }

        val pieces = fish.optJSONArray("pieces")
        if (pieces != null && pieces.length() > 0) {
            var completedPieceCount = 0
            for (i in 0 until pieces.length()) {
                if (pieces.optJSONObject(i)?.optInt("num", 0) ?: 0 > 0) {
                    completedPieceCount++
                }
            }
            if (completedPieceCount >= pieces.length()) {
                return true
            }
            val obtainedPieces = fish.optInt("obtainedPieces", -1)
            if (obtainedPieces >= pieces.length()) {
                return true
            }
        }

        val attachRewardBOList = fish.optJSONArray("attachRewardBOList")
        if (attachRewardBOList != null && attachRewardBOList.length() > 0) {
            for (i in 0 until attachRewardBOList.length()) {
                if (attachRewardBOList.optJSONObject(i)?.optInt("count", 0) ?: 0 <= 0) {
                    return false
                }
            }
            return true
        }
        return false
    }

    @Suppress("ReturnCount")
    private suspend fun cleanFriendOcean(fillFlag: JSONObject) {
        if (!fillFlag.optBoolean("canClean")) {
            return
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
            return
        }
        try {
            val userId = fillFlag.getString("userId")
            if (FriendGuard.shouldSkipFriend(userId, TAG, "神奇海洋好友清理")) {
                return
            }
            var isOceanClean = cleanOceanList?.contains(userId) == true
            if (cleanOceanType?.value == CleanOceanType.DONT_CLEAN) {
                isOceanClean = !isOceanClean
            }
            if (!isOceanClean) {
                return
            }
            var s = AntOceanRpcCall.queryFriendPage(userId)
            var jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                FriendCapabilityRecorder.record(userId, "OCEAN", FriendCapabilityState.OPEN, "AntOcean.queryFriendPage")
                if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                    return
                }
                s = AntOceanRpcCall.cleanFriendOcean(userId)
                jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
                if (ResChecker.checkRes(TAG, jo)) {
                    val maskName = UserMap.getMaskName(userId) ?: userId
                    Log.ocean("神奇海洋🌊[帮助:${maskName}清理海域]")
                    val cleanRewardVOS = jo.getJSONArray("cleanRewardVOS")
                    checkReward(cleanRewardVOS)
                    markOceanHomeRefreshNeeded()
                } else {
                    if (isHelpCleanLimit(jo)) {
                        markHelpCleanLimit(jo)
                    } else {
                        Log.runtime(TAG, extractOceanResultDesc(jo))
                    }
                }
            } else {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                } else {
                    if (isOceanFriendNotOpen(jo)) {
                        FriendCapabilityRecorder.record(
                            userId,
                            "OCEAN",
                            FriendCapabilityState.NOT_OPEN,
                            "AntOcean.queryFriendPage",
                            extractOceanResultDesc(jo)
                        )
                    }
                    Log.runtime(TAG, extractOceanResultDesc(jo))
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "cleanFriendOcean err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun fillUserFlagAndClean(userIds: List<String>) {
        if (userIds.isEmpty()) return
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) return

        val ja = JSONArray()
        for (id in userIds) {
            if (id.isNotBlank() && !FriendGuard.shouldSkipFriend(id, TAG, "神奇海洋好友清理")) {
                ja.put(id)
            }
        }
        if (ja.length() == 0) return

        val s = AntOceanRpcCall.fillUserFlag(ja)
        val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
        if (!ResChecker.checkRes(TAG, jo)) {
            if (isHelpCleanLimit(jo)) {
                markHelpCleanLimit(jo)
            } else {
                Log.runtime(TAG, extractOceanResultDesc(jo))
            }
            return
        }

        val fillFlagVOList = jo.optJSONArray("fillFlagVOList") ?: return
        for (i in 0 until fillFlagVOList.length()) {
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) return
            cleanFriendOcean(fillFlagVOList.getJSONObject(i))
        }
    }

    private suspend fun queryUserRanking() {
        try {
            if (cleanOcean?.value != true) {
                return
            }
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                return
            }
            val s = AntOceanRpcCall.queryUserRanking()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                } else {
                    Log.runtime(TAG, extractOceanResultDesc(jo))
                }
                return
            }

            // 1) 先处理首屏 fillFlagVOList（通常为 20 个）
            val firstFillFlags = jo.optJSONArray("fillFlagVOList")
            if (firstFillFlags != null) {
                for (i in 0 until firstFillFlags.length()) {
                    cleanFriendOcean(firstFillFlags.getJSONObject(i))
                    if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) return
                }
            }

            // 2) 扩展处理：若首屏无可清理，继续从 allRankingList 取后续用户并通过 fillUserFlag 补全 canClean
            val allRankingList = jo.optJSONArray("allRankingList") ?: return
            val pageSize = jo.optInt("pageSize", 20).takeIf { it in 1..50 } ?: 20

            var pos = pageSize
            val idList = ArrayList<String>(pageSize)
            val currentUid = UserMap.currentUid

            while (pos < allRankingList.length() && !Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                val friend = allRankingList.optJSONObject(pos)
                val userId = friend?.optString("userId").orEmpty()
                if (userId.isNotBlank() && userId != currentUid) {
                    idList.add(userId)
                }

                pos++
                if (idList.size >= pageSize) {
                    fillUserFlagAndClean(idList)
                    idList.clear()
                }
            }

            if (idList.isNotEmpty() && !Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                fillUserFlagAndClean(idList)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryUserRanking err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun receiveTaskAward(): TaskFlowRunResult? {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE) && !oceanTasksDoneInvalidatedThisRun) {
            Log.ocean("海洋任务🌊[今日已确认完成，跳过重复查询]")
            return null
        }
        try {
            val result = TaskFlowEngine(OceanTaskFlowAdapter(), roundSleepMs = 500L).run()
            if (result.completed && !result.actionAttempted && !result.interrupted) {
                Status.setFlagToday(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE)
                oceanTasksDoneInvalidatedThisRun = false
                Log.ocean("海洋任务🌊今日已确认完成")
            }
            refreshOceanHomeIfNeeded("任务领奖/清理后")
            return result
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误: " + (e.message ?: ""))
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "receiveTaskAward err:")
            Log.printStackTrace(TAG, t)
        }
        return null
    }

    private inner class OceanTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = TASK_BLACKLIST_MODULE
        override val flowName: String = "神奇海洋任务"

        override fun isFlowHandledToday(): Boolean {
            return Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE) && !oceanTasksDoneInvalidatedThisRun
        }

        override fun query(): JSONObject {
            val response = AntOceanRpcCall.queryTaskList()
            return JsonUtil.parseJSONObjectOrNull(response) ?: JSONObject()
                .put("success", false)
                .put("resultDesc", "queryTaskList返回空或无法解析")
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONArray("antOceanTaskVOList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val bizInfo = parseJSONObject(task.opt("bizInfo")) ?: JSONObject()
                val taskType = task.optString("taskType").trim()
                if (taskType.isBlank()) {
                    continue
                }
                val taskTitle = bizInfo.optString("taskTitle", taskType).trim().ifBlank { taskType }
                val sceneCode = task.optString("sceneCode").trim()
                val taskStatus = task.optString("taskStatus").trim()
                val awardCount = bizInfo.optString("awardCount", task.optString("awardCount", "0"))
                    .trim()
                    .ifBlank { "0" }
                val raw = JSONObject()
                    .put("task", task)
                    .put("bizInfo", bizInfo)
                    .put("awardCount", awardCount)

                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = taskTitle,
                        status = taskStatus,
                        type = taskType,
                        sceneCode = sceneCode,
                        actionType = task.optString("actionType").ifBlank {
                            bizInfo.optString("actionType")
                        },
                        blacklistKeys = listOf(taskType, taskTitle).filter { it.isNotBlank() },
                        raw = raw,
                        progress = "award=$awardCount"
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            return when {
                isRewardReadyStatus(item.status) -> TaskFlowPhase.REWARD_READY
                isRewardReceivedStatus(item.status) ||
                    item.status == "HAS_RECEIVED" ||
                    item.status == "DONE" ||
                    item.status == "COMPLETED" -> TaskFlowPhase.TERMINAL

                item.status == TaskStatus.TODO.name && isSelfCleanTask(item.type, item.title) ->
                    if (canRetrySelfOceanCleanTask()) {
                        TaskFlowPhase.READY_TO_COMPLETE
                    } else {
                        TaskFlowPhase.BUSINESS_ACTION
                    }

                item.status == TaskStatus.TODO.name && isOceanActionTask(item.type, item.title) ->
                    TaskFlowPhase.BUSINESS_ACTION

                item.status == TaskStatus.TODO.name -> TaskFlowPhase.READY_TO_COMPLETE
                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkipByTodayState(item: TaskFlowItem): Boolean {
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT) &&
                isHelpFriendCleanTask(item.type, item.title) &&
                !isRewardReadyStatus(item.status) &&
                !isRewardReceivedStatus(item.status) &&
                item.status != TaskStatus.TODO.name
            ) {
                logOceanTaskOnce("海洋任务🌊[${item.title}]帮助清理次数已达上限，当前状态[${item.status}]不可补完成")
                return true
            }
            return false
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logOceanTaskOnce("海洋任务🌊[${item.title}]已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntOceanRpcCall.receiveTaskAward(item.sceneCode, item.type)
            val result = JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "receiveTaskAward返回空或无法解析",
                rpc = "AntOceanRpcCall.receiveTaskAward",
                raw = response,
                detail = oceanTaskActionDetail(item, "receiveTaskAward"),
                stopCurrentRound = true
            )
            if (isOceanTaskRewardNotReady(result)) {
                logOceanTaskOnce("海洋任务🌊[${item.title}]奖励未就绪，等待服务端刷新后再领取")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = extractOceanTaskFailureCode(result),
                    message = extractOceanTaskFailureMessage(result),
                    rpc = "AntOceanRpcCall.receiveTaskAward",
                    raw = result.toString(),
                    detail = oceanTaskActionDetail(item, "receiveTaskAward"),
                    stopCurrentRound = true
                )
            }
            if (isOceanTaskRpcSuccess(result)) {
                val awardCount = item.raw?.optString("awardCount", "0") ?: "0"
                Log.ocean("海洋奖励🌊[${item.title}]# $awardCount 拼图")
                markOceanHomeRefreshNeeded()
                return TaskFlowActionResult.success()
            }
            return oceanTaskActionFailureResult(
                response = result,
                rpc = "AntOceanRpcCall.receiveTaskAward",
                detail = oceanTaskActionDetail(item, "receiveTaskAward")
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (isSelfCleanTask(item.type, item.title)) {
                return if (retrySelfOceanCleanIfNeeded(item.title)) {
                    TaskFlowActionResult.success()
                } else {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        message = "自清理业务动作当前无可推进垃圾",
                        rpc = "AntOcean.retrySelfOceanCleanIfNeeded",
                        detail = oceanTaskActionDetail(item, "retrySelfOceanCleanIfNeeded")
                    )
                }
            }

            if (item.title.contains("答题")) {
                return if (answerQuestion()) {
                    TaskFlowActionResult.success()
                } else {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "答题流程未完成",
                        rpc = "AntOcean.answerQuestion",
                        detail = oceanTaskActionDetail(item, "answerQuestion")
                    )
                }
            }

            val bizInfo = item.raw?.optJSONObject("bizInfo") ?: JSONObject()
            return finishOceanTaskWithSourceFallback(item, bizInfo)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:${buildOceanTaskBizKey(item.sceneCode, item.type, item.title)}"
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.ocean("查询任务列表失败：" + extractOceanTaskFailureMessage(response))
        }

        override fun logInfo(message: String) {
            Log.ocean(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }
    }

    private fun canRetrySelfOceanCleanTask(): Boolean {
        return cleanOcean?.value == true &&
            !selfOceanCleanRetried &&
            !currentOceanUserId.isNullOrBlank()
    }

    private fun finishOceanTaskWithSourceFallback(
        item: TaskFlowItem,
        bizInfo: JSONObject
    ): TaskFlowActionResult {
        val finishSource = resolveOceanTaskFinishSource(item.type, item.title, bizInfo)
        val fallbackSource = if (finishSource == "ADBASICLIB") "ANTFOCEAN" else "ADBASICLIB"
        val sourcesToTry = listOf(finishSource, fallbackSource).distinct()
        val failures = mutableListOf<TaskFlowActionResult>()
        var hasUnparsedResponse = false
        var unparsedRaw = ""

        for (source in sourcesToTry) {
            val response = AntOceanRpcCall.finishTask(item.sceneCode, item.type, source)
            val result = JsonUtil.parseJSONObjectOrNull(response)
            if (result == null) {
                hasUnparsedResponse = true
                if (unparsedRaw.isBlank()) {
                    unparsedRaw = response
                }
                continue
            }

            if (isOceanTaskRpcSuccess(result)) {
                Log.ocean("海洋任务🌊完成[${item.title}]")
                if (isHelpFriendCleanTask(item.type, item.title)) {
                    markOceanHomeRefreshNeeded()
                }
                return TaskFlowActionResult.success()
            }

            failures.add(
                oceanTaskActionFailureResult(
                    response = result,
                    rpc = "AntOceanRpcCall.finishTask",
                    detail = oceanTaskActionDetail(item, "finishTask", "source=$source")
                )
            )
        }

        return selectOceanFinishFailure(item, sourcesToTry.size, failures, hasUnparsedResponse, unparsedRaw)
    }

    private fun selectOceanFinishFailure(
        item: TaskFlowItem,
        sourceCount: Int,
        failures: List<TaskFlowActionResult>,
        hasUnparsedResponse: Boolean,
        unparsedRaw: String
    ): TaskFlowActionResult {
        if (failures.isEmpty()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "finishTask兜底路由未得到可解析响应",
                rpc = "AntOceanRpcCall.finishTask",
                raw = unparsedRaw,
                detail = oceanTaskActionDetail(item, "finishTask"),
                stopCurrentRound = true
            )
        }

        if (!hasUnparsedResponse &&
            failures.size == sourceCount &&
            failures.all { it.failureType == TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE }
        ) {
            return failures.last()
        }

        failures.firstOrNull { it.failureType == TaskRpcFailureType.TERMINAL_DONE }?.let { return it }
        failures.firstOrNull { it.failureType == TaskRpcFailureType.RETRYABLE_RPC }?.let { return it }
        failures.firstOrNull { it.failureType == TaskRpcFailureType.BUSINESS_LIMIT }?.let { return it }
        failures.firstOrNull { it.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW }?.let { return it }
        failures.firstOrNull { it.failureType == TaskRpcFailureType.NON_RETRYABLE_INVALID }?.let { return it }

        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "finishTask多路兜底失败但不足以确认永久失败",
            rpc = "AntOceanRpcCall.finishTask",
            raw = failures.joinToString(separator = ";") { it.raw },
            detail = oceanTaskActionDetail(item, "finishTask"),
            stopCurrentRound = true
        )
    }

    private fun oceanTaskActionFailureResult(
        response: JSONObject,
        rpc: String,
        detail: String
    ): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = classifyOceanTaskFailure(response),
            code = extractOceanTaskFailureCode(response),
            message = extractOceanTaskFailureMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = detail
        )
    }

    private fun oceanTaskActionDetail(
        item: TaskFlowItem,
        action: String,
        extra: String = ""
    ): String {
        return buildString {
            append("taskType=")
            append(item.type)
            append(" sceneCode=")
            append(item.sceneCode)
            append(" action=")
            append(action)
            if (extra.isNotBlank()) {
                append(" ")
                append(extra)
            }
        }
    }

    private fun isOceanTaskRpcSuccess(response: JSONObject): Boolean {
        if (response.optBoolean("success") || response.optBoolean("isSuccess")) {
            return true
        }
        if (response.optString("code") == "100000000") {
            return true
        }
        when (val resultCode = response.opt("resultCode")) {
            is Number -> if (resultCode.toInt() == 100 || resultCode.toInt() == 200) return true
            is String -> if (
                resultCode.equals("SUCCESS", ignoreCase = true) ||
                resultCode == "100" ||
                resultCode == "200"
            ) {
                return true
            }
        }
        return response.optString("memo").equals("SUCCESS", ignoreCase = true)
    }

    private fun classifyOceanTaskFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractOceanTaskFailureCode(response)
        val message = extractOceanTaskFailureMessage(response)
        return when {
            code in setOf(
                "400000030",
                "400000012",
                "RECEIVE_REWARD_REPEATED",
                "TASK_ALREADY_FINISHED",
                "TASK_HAS_FINISHED",
                "REPEAT_FINISH",
                "REPEAT_REWARD"
            ) ||
                containsAnyOcean(
                    message,
                    "已领取",
                    "已经领取",
                    "重复领取",
                    "重复领奖",
                    "重复完成",
                    "已完成",
                    "任务已完结",
                    "任务已结束",
                    "无状态转换处理"
                ) -> TaskRpcFailureType.TERMINAL_DONE

            code == "CAMP_TRIGGER_ERROR" ||
                code == "HELP_CLEAN_LIMIT" ||
                code == "HELP_CLEAN_ALL_FRIEND_LIMIT" ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAnyOcean(
                    message,
                    "上限",
                    "限制",
                    "受限",
                    "不可领取",
                    "资格不足",
                    "兑完",
                    "能量不足",
                    "风控",
                    "风险"
                ) -> TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAnyOcean(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAnyOcean(message, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf(
                "3000",
                "400000004",
                "REMOTE_INVOKE_EXCEPTION",
                "OP_REPEAT_CHECK",
                "SYSTEM_BUSY",
                "NETWORK_ERROR",
                "1009",
                "I07",
                "USER_FREQUENTLY_LOCK"
            ) ||
                containsAnyOcean(
                    message,
                    "系统出错",
                    "系统繁忙",
                    "稍后",
                    "繁忙",
                    "频繁",
                    "重试",
                    "需要验证",
                    "访问被拒绝",
                    "任务未完成,无法领取",
                    "任务未完成，无法领取",
                    "任务未完成无法领取"
                ) ||
                isOceanFailureMarkedRetryable(response) -> TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun extractOceanTaskFailureCode(response: JSONObject): String {
        return response.optString("code")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("resultCode") }
    }

    private fun extractOceanTaskFailureMessage(response: JSONObject): String {
        return response.optString("desc")
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("memo") }
            .ifBlank { response.optString("message") }
            .ifBlank { response.toString() }
    }

    private fun isOceanFailureMarkedRetryable(response: JSONObject): Boolean {
        response.optJSONObject("resData")?.let {
            return isOceanFailureMarkedRetryable(it)
        }
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun containsAnyOcean(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun logOceanTaskOnce(message: String) {
        if (loggedMessages.add(message)) {
            Log.ocean(message)
        }
    }

    private fun isRewardReadyStatus(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.FINISHED.name ||
            taskStatus == "COMPLETE" ||
            taskStatus == "WAIT_RECEIVE"
    }

    private fun isRewardReceivedStatus(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.RECEIVED.name
    }

    private fun isOceanTaskRewardNotReady(jo: JSONObject): Boolean {
        val code = jo.optString("code").ifBlank {
            jo.optString("errorCode").ifBlank { jo.optString("resultCode") }
        }
        val desc = jo.optString("desc").ifBlank {
            jo.optString("errorMsg").ifBlank { jo.optString("resultDesc") }
        }
        return code == "400000004" ||
            containsAnyOcean(desc, "任务未完成,无法领取", "任务未完成，无法领取", "任务未完成无法领取")
    }

    private fun parseJSONObject(value: Any?): JSONObject? {
        return when (value) {
            is JSONObject -> value
            is String -> JsonUtil.parseJSONObjectOrNull(value)
            else -> null
        }
    }

    private suspend fun queryNotice() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryNotice()) ?: return
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            val noticeInfoList = jo.optJSONArray("noticeInfoList") ?: return
            var needQueryPopup = false
            for (index in 0 until noticeInfoList.length()) {
                val noticeInfo = noticeInfoList.optJSONObject(index) ?: continue
                val noticeType = noticeInfo.optString("noticeType")
                val haveNotice = noticeInfo.optBoolean("haveNotice")
                val extendInfo = parseJSONObject(noticeInfo.opt("extendInfo"))
                when (noticeType) {
                    "INDEX_TASK_NOTICE" -> {
                        val todoTaskNum = extendInfo?.optInt("todoTaskNum", 0) ?: 0
                        val taskCanReceiveRewardNum = extendInfo?.optInt("taskCanReceiveRewardNum", 0) ?: 0
                        if (haveNotice || todoTaskNum > 0 || taskCanReceiveRewardNum > 0) {
                            markOceanNoticeLinkedRefreshNeeded()
                            Log.ocean("海洋任务🌊[待完成:$todoTaskNum,待领取:$taskCanReceiveRewardNum]")
                        }
                    }

                    "CULTIVATION_LIST_ENTRANCE" -> {
                        if (haveNotice) {
                            needQueryPopup = true
                            Log.ocean("神奇海洋🌊[检测到海洋活动入口]")
                        }
                    }

                    "INDEX_GAME_ENTRY_NOTICE" -> {
                        if (haveNotice) {
                            val todoTaskNum = extendInfo?.optInt("todoTaskNum", 0) ?: 0
                            markOceanNoticeLinkedRefreshNeeded()
                            Log.ocean("海洋任务🌊[游戏入口待处理:$todoTaskNum]")
                            needQueryPopup = true
                        }
                    }

                    "INTERACT_RECEIVE_PIECE" -> {
                        if (haveNotice) {
                            markOceanNoticeLinkedRefreshNeeded()
                            Log.ocean("海洋拼图🌊[存在可领取互动拼图]")
                        }
                    }
                }
            }
            if (needQueryPopup) {
                queryPopupWin()
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryNotice err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun queryPopupWin() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.popupWin()) ?: return
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            val refinedOpsVOList = jo.optJSONArray("refinedOpsVOList") ?: return
            for (index in 0 until refinedOpsVOList.length()) {
                val item = refinedOpsVOList.optJSONObject(index) ?: continue
                val renderValue = item.optJSONObject("renderValue")
                val jumpUrl = renderValue?.optString("jumpUrl").orEmpty()
                if (jumpUrl.contains("cultivationDetail") || jumpUrl.contains("projectCode")) {
                    Log.ocean("神奇海洋🌊[检测到海洋活动入口]")
                    return
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryPopupWin err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun queryRefinedMaterial() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryRefinedMaterial()) ?: return
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            val userDynamicsVOList = jo.optJSONArray("userDynamicsVOList") ?: return
            if (userDynamicsVOList.length() > 0) {
                Log.ocean("海洋动态🌊[检测到${userDynamicsVOList.length()}条精炼物料动态]")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryRefinedMaterial err:")
            Log.printStackTrace(TAG, t)
        }
    }

    // 海洋答题任务
    private fun answerQuestion(): Boolean {
        try {
            val questionResponse = AntOceanRpcCall.getQuestion()
            val questionJson = JsonUtil.parseJSONObjectOrNull(questionResponse) ?: return false
            if (questionJson.getBoolean("answered")) {
                Log.runtime(TAG, "问题已经被回答过，跳过答题流程")
                return false
            }
            if (questionJson.getInt("resultCode") == 200) {
                val questionId = questionJson.getString("questionId")
                val options = questionJson.getJSONArray("options")
                val answer = options.getString(0)
                val submitResponse = AntOceanRpcCall.submitAnswer(answer, questionId)
                val submitJson = JsonUtil.parseJSONObjectOrNull(submitResponse) ?: return false
                if (submitJson.getInt("resultCode") == 200) {
                    Log.ocean("🌊海洋答题成功")
                    return true
                } else {
                    Log.error(TAG, "海洋答题失败：$submitJson")
                }
            } else {
                Log.error(TAG, "海洋获取问题失败：$questionJson")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "海洋答题错误", t)
        }
        return false
    }

    /**
     * 潘多拉海洋任务领取
     */
    private suspend fun doOceanPDLTask() {
        try {
            Log.runtime(TAG, "执行潘多拉海域任务")
            val homeResponse = AntOceanRpcCall.PDLqueryReplicaHome()
            val homeJson = JsonUtil.parseJSONObjectOrNull(homeResponse) ?: return
            if (ResChecker.checkRes(TAG, homeJson)) {
                val taskListResponse = AntOceanRpcCall.PDLqueryTaskList()
                val taskListJson = JsonUtil.parseJSONObjectOrNull(taskListResponse) ?: return
                val antOceanTaskVOList = taskListJson.optJSONArray("antOceanTaskVOList") ?: return
                for (i in 0 until antOceanTaskVOList.length()) {
                    val task = antOceanTaskVOList.optJSONObject(i) ?: continue
                    val taskStatus = task.optString("taskStatus")
                    if (isRewardReadyStatus(taskStatus)) {
                        val bizInfo = parseJSONObject(task.opt("bizInfo")) ?: JSONObject()
                        val taskTitle = bizInfo.optString("taskTitle", task.optString("taskType"))
                        val awardCount = bizInfo.optString("awardCount", task.optString("awardCount", "0"))
                        val taskType = task.getString("taskType")
                        val receiveTaskResponse = AntOceanRpcCall.PDLreceiveTaskAward(taskType)
                        val receiveTaskJson = JsonUtil.parseJSONObjectOrNull(receiveTaskResponse) ?: continue
                        if (ResChecker.checkRes(TAG, receiveTaskJson) || receiveTaskJson.optInt("code") == 100000000) {
                            Log.ocean("海洋奖励🌊[领取:$taskTitle]获得潘多拉能量x$awardCount")
                        } else {
                            val message = receiveTaskJson.optString("message")
                                .ifBlank { receiveTaskJson.optString("resultDesc") }
                                .ifBlank { "领取任务奖励失败，未返回错误信息" }
                            Log.ocean("领取任务奖励失败: $message")
                        }
                    }
                }
            } else {
                Log.ocean("PDLqueryReplicaHome调用失败: ${homeJson.optString("message")}")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "doOceanPDLTask err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun protectOcean() {
        try {
            val s = AntOceanRpcCall.queryCultivationList()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("cultivationItemVOList")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    val templateSubType = item.getString("templateSubType")
                    val applyAction = item.getString("applyAction")
                    val cultivationName = item.getString("cultivationName")
                    val templateCode = item.getString("templateCode")
                    val projectConfig = item.getJSONObject("projectConfigVO")
                    val projectCode = projectConfig.getString("code")
                    val map = protectOceanList?.value ?: continue
                    for (entry in map.entries) {
                        if (entry.key == templateCode) {
                            val count = entry.value
                            if (count != null && count > 0) {
                                oceanExchangeTree(templateCode, projectCode, cultivationName, count)
                            }
                            break
                        }
                    }
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "protectBeach err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun oceanExchangeTree(
        cultivationCode: String,
        projectCode: String,
        itemName: String,
        count: Int
    ) {
        try {
            var appliedTimes = queryCultivationDetail(cultivationCode, projectCode, count)
            if (appliedTimes < 0) return

            for (applyCount in 1..count) {
                val s = AntOceanRpcCall.oceanExchangeTree(cultivationCode, projectCode)
                val jo = JsonUtil.parseJSONObjectOrNull(s) ?: break
                if (ResChecker.checkRes(TAG, jo)) {
                    val awardInfos = jo.getJSONArray("rewardItemVOs")
                    val award = StringBuilder()
                    for (i in 0 until awardInfos.length()) {
                        val awardItem = awardInfos.getJSONObject(i)
                        award.append(awardItem.getString("name")).append("*").append(awardItem.getInt("num"))
                    }
                    val str = "保护海洋生态🏖️[$itemName]#第${appliedTimes}次-获得奖励$award"
                    Log.ocean(str)
                } else {
                    Log.error("保护海洋生态🏖️[$itemName]#发生未知错误，停止申请")
                    break
                }
                appliedTimes = queryCultivationDetail(cultivationCode, projectCode, count)
                if (appliedTimes < 0) {
                    break
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "海洋保护错误:", t)
        }
    }

    private suspend fun queryCultivationDetail(
        cultivationCode: String,
        projectCode: String,
        count: Int
    ): Int {
        var appliedTimes = -1
        try {
            val s = AntOceanRpcCall.queryCultivationDetail(cultivationCode, projectCode)
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return appliedTimes
            if (ResChecker.checkRes(TAG, jo)) {
                val userInfo = jo.getJSONObject("userInfoVO")
                val currentEnergy = userInfo.getInt("currentEnergy")
                val cultivationDetailVO = jo.getJSONObject("cultivationDetailVO")
                val applyAction = cultivationDetailVO.getString("applyAction")
                val certNum = cultivationDetailVO.getInt("certNum")
                if ("AVAILABLE" == applyAction) {
                    if (currentEnergy >= cultivationDetailVO.getInt("energy")) {
                        if (certNum < count) {
                            appliedTimes = certNum + 1
                        }
                    } else {
                        Log.ocean("保护海洋🏖️[${cultivationDetailVO.getString("cultivationName")}]#能量不足停止申请")
                    }
                } else {
                    Log.ocean("保护海洋🏖️[${cultivationDetailVO.getString("cultivationName")}]#似乎没有了")
                }
            } else {
                Log.ocean(jo.getString("resultDesc"))
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryCultivationDetail err:")
            Log.printStackTrace(TAG, t)
        }
        return appliedTimes
    }

    // 制作万能碎片
    private suspend fun exchangeProp() {
        try {
            var shouldContinue = true
            while (shouldContinue) {
                // 获取道具兑换列表的JSON数据
                val propListJson = AntOceanRpcCall.exchangePropList()
                val propListObj = JsonUtil.parseJSONObjectOrNull(propListJson) ?: return
                // 检查是否成功获取道具列表
                if (ResChecker.checkRes(TAG, propListObj)) {
                    // 获取道具重复数量
                    val duplicatePieceNum = propListObj.getInt("duplicatePieceNum")
                    // 如果道具重复数量小于10，直接返回并停止循环
                    if (duplicatePieceNum < 10) {
                        return
                    }
                    // 如果道具重复数量大于等于10，则执行道具兑换操作
                    val exchangeResultJson = AntOceanRpcCall.exchangeProp()
                    val exchangeResultObj = JsonUtil.parseJSONObjectOrNull(exchangeResultJson) ?: return
                    // 获取兑换后的碎片数量和兑换数量
                    val exchangedPieceNum = exchangeResultObj.getString("duplicatePieceNum")
                    val exchangeNum = exchangeResultObj.getString("exchangeNum")
                    // 检查道具兑换操作是否成功
                    if (ResChecker.checkRes(TAG, exchangeResultObj)) {
                        // 输出日志信息
                        Log.ocean("神奇海洋🏖️[万能拼图]制作${exchangeNum}张,剩余${exchangedPieceNum}张碎片")
                        // 制作完成后休眠1秒钟
                        delay(1000)
                    }
                } else {
                    // 如果未成功获取道具列表，停止循环
                    shouldContinue = false
                }
            }
        } catch (t: Throwable) {
            // 捕获并记录异常
            Log.runtime(TAG, "exchangeProp error:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun findCurrentChapterPropTarget(
        detailJo: JSONObject,
        maxPieceCount: Int,
        extraCollectOnly: Boolean
    ): FishPropTarget? {
        if (maxPieceCount <= 0) {
            return null
        }
        val seaAreaVOs = detailJo.optJSONArray("seaAreaVOs") ?: return null
        for (i in 0 until seaAreaVOs.length()) {
            val seaAreaVO = seaAreaVOs.optJSONObject(i) ?: continue
            if (isSeaAreaLocked(seaAreaVO) || isSeaAreaFullyCompleted(seaAreaVO)) {
                continue
            }
            if (extraCollectOnly) {
                val extraCollectVO = seaAreaVO.optJSONObject("seaAreaExtraCollectVO")
                if (!isActiveExtraCollect(extraCollectVO)) {
                    return null
                }
                return buildFishPropTarget(getFishList(extraCollectVO), maxPieceCount)
            }
            return buildFishPropTarget(getFishList(seaAreaVO), maxPieceCount)
        }
        return null
    }

    private fun buildFishPropTarget(fishVOs: JSONArray?, maxPieceCount: Int): FishPropTarget? {
        if (fishVOs == null || maxPieceCount <= 0) {
            return null
        }
        for (i in 0 until fishVOs.length()) {
            val fish = fishVOs.optJSONObject(i) ?: continue
            if (isFishUnlocked(fish) || shouldCombineFish(fish)) {
                continue
            }
            val order = fish.optInt("order", 0)
            if (order <= 0) {
                continue
            }
            val pieces = fish.optJSONArray("pieces") ?: continue
            val pieceIds = LinkedHashSet<Int>()
            for (j in 0 until pieces.length()) {
                val piece = pieces.optJSONObject(j) ?: continue
                if (piece.optInt("num", 0) > 0) {
                    continue
                }
                val pieceId = piece.opt("id")?.toString()?.toIntOrNull() ?: continue
                pieceIds.add(pieceId)
                if (pieceIds.size >= maxPieceCount) {
                    break
                }
            }
            if (pieceIds.isNotEmpty()) {
                return FishPropTarget(
                    name = fish.optString("name").ifBlank { "未知鱼类" },
                    order = order,
                    pieceIds = pieceIds
                )
            }
        }
        return null
    }

    // 使用万能拼图
    private suspend fun usePropByType(allowReplenish: Boolean = true) {
        try {
            val propListJson = AntOceanRpcCall.usePropByTypeList()
            val propListObj = JsonUtil.parseJSONObjectOrNull(propListJson) ?: return
            if (!ResChecker.checkRes(TAG, propListObj)) {
                Log.runtime(TAG, extractOceanResultDesc(propListObj))
                return
            }
            val propInfos = ArrayList<JSONObject>()
            val oceanPropVOList = propListObj.optJSONArray("oceanPropVOList")
            if (oceanPropVOList != null) {
                for (i in 0 until oceanPropVOList.length()) {
                    val propInfo = oceanPropVOList.optJSONObject(i) ?: continue
                    if (propInfo.optString("type") == "UNIVERSAL_PIECE") {
                        propInfos.add(propInfo)
                    }
                }
            } else {
                val oceanPropVOByTypeList = propListObj.optJSONArray("oceanPropVOByTypeList") ?: return
                for (i in 0 until oceanPropVOByTypeList.length()) {
                    val propInfo = oceanPropVOByTypeList.optJSONObject(i) ?: continue
                    if (propInfo.optString("type") == "UNIVERSAL_PIECE") {
                        propInfos.add(propInfo)
                    }
                }
            }
            val hasUsableUniversalPiece = propInfos.any { it.optInt("holdsNum", 0) > 0 }
            if (!hasUsableUniversalPiece && allowReplenish) {
                val target = querySeaAreaDetailData()?.let { detailJo ->
                    findCurrentChapterPropTarget(detailJo, maxPieceCount = 1, extraCollectOnly = false)
                }
                if (target != null) {
                    val replenishResult = ExchangeReplenisher.replenish(
                        need = ExchangeEffectNeed.OCEAN_UNIVERSAL_PIECE,
                        reason = "神奇海洋万能拼图不足",
                        maxCount = 1
                    ) {
                        AntOceanRpcCall.usePropByTypeList()
                    }
                    if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                        Log.ocean("神奇海洋🏖️[万能拼图]已触发缺货补兑，重新查询道具列表")
                        usePropByType(allowReplenish = false)
                        return
                    }
                    val randomPieceResult = ExchangeReplenisher.replenish(
                        need = ExchangeEffectNeed.OCEAN_RANDOM_PIECE,
                        reason = "神奇海洋随机拼图推进",
                        maxCount = 1
                    ) {
                        AntOceanRpcCall.querySeaAreaDetailList()
                    }
                    if (randomPieceResult == ExchangeReplenishResult.EXCHANGED) {
                        Log.ocean("神奇海洋🏖️[随机拼图]已触发补兑，后续重新查询海域进度")
                        return
                    }
                }
            }
            propInfos.sortByDescending { it.optString("code") == "LIMIT_TIME_UNIVERSAL_PIECE" }
            for (propInfo in propInfos) {
                val propCode = propInfo.optString("code").ifBlank { "UNIVERSAL_PIECE" }
                val propName = propInfo.optString("name").ifBlank {
                    if (propCode == "LIMIT_TIME_UNIVERSAL_PIECE") "限时万能拼图" else "万能拼图"
                }
                var holdsNum = propInfo.optInt("holdsNum", 0)
                if (holdsNum <= 0) {
                    continue
                }
                val extraCollectOnly = propCode == "LIMIT_TIME_UNIVERSAL_PIECE"
                while (holdsNum > 0) {
                    val detailJo = querySeaAreaDetailData() ?: break
                    val target = findCurrentChapterPropTarget(detailJo, holdsNum, extraCollectOnly) ?: break
                    val useCount = target.pieceIds.size
                    val usePropResult = AntOceanRpcCall.usePropByType(propCode, target.order, target.pieceIds) ?: break
                    val usePropResultObj = JsonUtil.parseJSONObjectOrNull(usePropResult) ?: break
                    if (!ResChecker.checkRes(TAG, usePropResultObj)) {
                        Log.runtime(TAG, usePropResultObj.optString("resultDesc").ifBlank {
                            usePropResultObj.optString("memo").ifBlank { usePropResultObj.toString() }
                        })
                        break
                    }
                    holdsNum -= useCount
                    Log.ocean("神奇海洋🏖️[$propName]使用${useCount}张，获得[${target.name}]剩余${holdsNum}张")
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "usePropByType error:")
            Log.printStackTrace(TAG, t)
        }
    }
}
