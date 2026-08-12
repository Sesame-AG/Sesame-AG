package io.github.aoguai.sesameag.task.antOcean

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.AlipayBeach
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import io.github.aoguai.sesameag.util.maps.BeachMap
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.UserMap
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
    enum class ApplyAction(
        val code: Int,
        val desc: String,
    ) {
        AVAILABLE(0, "可用"),
        NO_STOCK(1, "无库存"),
        ENERGY_LACK(2, "能量不足"),
        ;

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
        private const val AI_FISH_SCENE_CODE = "ANTAIFISH"
        private const val AI_FISH_STATUS_DEFAULT = "DEFAULT_AI_FISH"
        private const val AI_FISH_STATUS_CAPTURED = "CAPTURED"
        private val AI_FISH_IMAGE_URLS =
            arrayOf(
                "https://mdn.alipayobjects.com/afts/img/JNVwTJAPzqMAAAAAQOAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/ZK3FTK8eQ-IAAAAAQIAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/Se_RQ7215tsAAAAAQQAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/n8yQSYH-lvgAAAAAQGAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/_rKKT6IJRWcAAAAAQKAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/_zy6QaSffRMAAAAAQGAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/3tFWT43StnAAAAAAQHAAAAgA9KBtAQJr/original?bz=ai_fish",
            )

        /**
         * 保护类型字段（静态）
         */
        private var userprotectType: ChoiceModelField? = null
    }

    private data class OceanCultivation(
        val cultivationCode: String,
        val templateSubType: String,
        val projectCode: String,
        val cultivationName: String,
        val applyAction: String,
        val energy: Int,
    ) {
        fun matchesProtectionType(protectionType: Int): Boolean =
            when (protectionType) {
                ProtectType.PROTECT_ALL -> true
                ProtectType.PROTECT_BEACH -> templateSubType == "BEACH"
                else -> false
            }
    }

    private data class SeaAreaAdvanceState(
        val hasOpenedUnfinishedExtraCollect: Boolean,
        val canCreateExtraCollect: Boolean,
        val hasPendingLockedSeaArea: Boolean,
        val allOpenedSeaAreasCompleted: Boolean,
    ) {
        val canRepairNextSeaArea: Boolean
            get() =
                allOpenedSeaAreasCompleted &&
                    hasPendingLockedSeaArea &&
                    !hasOpenedUnfinishedExtraCollect &&
                    !canCreateExtraCollect

        val currentChapterFullyCompleted: Boolean
            get() =
                allOpenedSeaAreasCompleted &&
                    !hasPendingLockedSeaArea &&
                    !hasOpenedUnfinishedExtraCollect &&
                    !canCreateExtraCollect
    }

    private data class FishPropTarget(
        val name: String,
        val order: Int,
        val pieceIds: LinkedHashSet<Int>,
    )

    private data class FriendCleanAttemptResult(
        val cleaned: Boolean,
        val limitReached: Boolean = false,
    )

    private enum class OceanNoticeScene {
        HOME_FULL,
        TASK_AND_GAME,
        GAME_ONLY,
    }

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
     * 保护 | 海洋列表
     */
    private var protectOceanList: SelectAndCountModelField? = null

    private var PDL_task: BooleanModelField? = null
    private var aiFishEnable: BooleanModelField? = null
    private var aiFishAutoTask: BooleanModelField? = null

    private val loggedMessages = HashSet<String>()

    private fun buildOceanTaskBizKey(
        sceneCode: String,
        taskType: String,
        taskTitle: String,
    ): String = "$sceneCode|$taskType|$taskTitle"

    private var currentOceanUserId: String? = null
    private var currentSeaAreaCode: String? = null
    private var lastKnownRubbishNumber: Int = -1
    private var selfOceanCleanRetried = false
    private var noticeLinkedRefreshNeeded = false
    private var oceanHomeRefreshNeeded = false
    private var oceanTasksDoneInvalidatedThisRun = false

    override fun getName(): String = "海洋"

    override fun getGroup(): ModelGroup = ModelGroup.FOREST

    override fun getIcon(): String = "AntOcean.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("dailyOceanTask", "海洋任务 | 开启", false)
                .withDesc(
                    "完成并领取神奇海洋每日任务奖励，为清理和合成提供碎片。",
                ).also { dailyOceanTask = it },
        )
        modelFields.addField(
            BooleanModelField("cleanOcean", "清理 | 开启", false)
                .withDesc(
                    "执行清理自己和好友海域垃圾的主流程。",
                ).also { cleanOcean = it },
        )
        modelFields.addField(
            ChoiceModelField(
                "cleanOceanType",
                "清理 | 动作",
                CleanOceanType.DONT_CLEAN,
                CleanOceanType.nickNames,
            ).withDesc("决定列表中的好友是清理还是跳过。").also { cleanOceanType = it },
        )
        modelFields.addField(
            FriendSelectionModelField(
                "cleanOceanList",
                "清理 | 好友列表",
            ).withDesc("配置要参与清理规则的好友列表。").also { cleanOceanList = it },
        )
        modelFields.addField(
            BooleanModelField("exchangeProp", "万能拼图 | 制作", false)
                .withDesc(
                    "把重复碎片制作成万能拼图。",
                ).also { exchangeProp = it },
        )
        modelFields.addField(
            BooleanModelField("usePropByType", "万能拼图 | 使用", false)
                .withDesc(
                    "在可合成目标鱼类时自动消耗万能拼图。",
                ).also { usePropByType = it },
        )
        modelFields.addField(
            ChoiceModelField(
                "userprotectType",
                "海洋生态保护 | 范围",
                ProtectType.DONT_PROTECT,
                ProtectType.nickNames,
            ).withDesc("选择申请保护的范围；“仅保护沙滩”只处理服务端标记为 BEACH 的项目。").also { userprotectType = it },
        )
        modelFields.addField(
            SelectAndCountModelField(
                "protectOceanList",
                "海洋生态保护 | 列表与目标证书数",
                LinkedHashMap(),
                AlipayBeach::getListAsMapperEntity,
            ).withDesc("配置项目及目标证书总数；候选以当前服务端返回的 cultivationCode 为准，旧选择需重新配置。").also { protectOceanList = it },
        )
        modelFields.addField(
            BooleanModelField("PDL_task", "潘多拉任务 | 开启", false)
                .withDesc(
                    "执行潘多拉活动系列的独立任务与奖励领取。",
                ).also { PDL_task = it },
        )
        modelFields.addField(
            BooleanModelField("aiFishEnable", "海洋摸鱼 | 开启", false)
                .withDesc(
                    "执行海洋摸鱼状态查询、开通画鱼、解救被困鱼和消耗摸鱼次数。",
                ).also { aiFishEnable = it },
        )
        modelFields.addField(
            BooleanModelField("aiFishAutoTask", "海洋摸鱼 | 自动做任务", false)
                .withDesc(
                    "自动完成并领取海洋摸鱼任务，获取额外摸鱼次数。需开启“海洋摸鱼 | 开启”。",
                ).also { aiFishAutoTask = it },
        )
        return modelFields
    }

    override fun check(): Boolean =
        when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.ocean("⏸ 当前为只收能量时间【" + BaseModel.energyTime.value + "】，停止执行" + getName() + "任务！")
                false
            }

            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.ocean("💤 模块休眠时间【" + BaseModel.modelSleepTime.value + "】停止执行" + getName() + "任务！")
                false
            }

            else -> {
                true
            }
        }

    override suspend fun runSuspend() {
        try {
            Log.ocean("执行开始-" + getName())
            loggedMessages.clear()
            currentOceanUserId = null
            currentSeaAreaCode = null
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

            if (aiFishEnable?.value == true) {
                runAiFish()
            }
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
     * 刷新当前服务端可申请的海洋生态项目。缓存键必须与详情、申请 RPC 的
     * cultivationCode 一致，避免配置项把展示字段误当成行为参数。
     */
    fun initBeach() {
        val beachMap = IdMapManager.getInstance(BeachMap::class.java)
        try {
            val response = AntOceanRpcCall.queryCultivationList()
            val jsonResponse = JsonUtil.parseJSONObjectOrNull(response) ?: return
            if (!ResChecker.checkRes(TAG, jsonResponse)) {
                Log.runtime(TAG, "刷新海洋生态候选失败: ${jsonResponse.optString("resultDesc", "未知错误")}")
                return
            }
            val cultivationList =
                jsonResponse.optJSONArray("cultivationItemVOList") ?: run {
                    Log.runtime(TAG, "刷新海洋生态候选失败: 缺少cultivationItemVOList")
                    return
                }

            beachMap.clear()
            var availableCount = 0
            for (i in 0 until cultivationList.length()) {
                val cultivation = parseOceanCultivation(cultivationList.optJSONObject(i)) ?: continue
                if (cultivation.applyAction != ApplyAction.AVAILABLE.name) {
                    continue
                }
                beachMap.add(
                    cultivation.cultivationCode,
                    "${cultivation.cultivationName}(${cultivation.energy}g)",
                )
                availableCount++
            }
            beachMap.save()
            Log.runtime(TAG, "刷新海洋生态候选成功[$availableCount]")
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "刷新海洋生态候选JSON错误：", e)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "刷新海洋生态候选出错", e)
        }
    }

    private fun parseOceanCultivation(item: JSONObject?): OceanCultivation? {
        if (item == null) {
            return null
        }
        val cultivationCode = item.optString("cultivationCode").trim()
        val projectCode =
            item
                .optJSONObject("projectConfigVO")
                ?.optString("code")
                .orEmpty()
                .trim()
        if (cultivationCode.isEmpty() || projectCode.isEmpty()) {
            Log.runtime(
                TAG,
                "跳过缺少海洋生态身份字段的候选[cultivationCode=$cultivationCode, projectCode=$projectCode]",
            )
            return null
        }
        return OceanCultivation(
            cultivationCode = cultivationCode,
            templateSubType = item.optString("templateSubType").trim(),
            projectCode = projectCode,
            cultivationName = item.optString("cultivationName", cultivationCode),
            applyAction = item.optString("applyAction").trim(),
            energy = item.optInt("energy", 0),
        )
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

    private fun rememberCurrentSeaAreaCode(container: JSONObject?) {
        val seaAreaCode = extractCurrentSeaAreaCode(container)
        if (seaAreaCode.isNotBlank()) {
            currentSeaAreaCode = seaAreaCode
        }
    }

    private fun extractCurrentSeaAreaCode(container: JSONObject?): String {
        if (container == null) {
            return ""
        }
        listOf(
            container.optJSONObject("displaySeaAreaVO")?.optString("code").orEmpty(),
            container.optJSONObject("currentSeaAreaVO")?.optString("code").orEmpty(),
            container.optJSONObject("seaAreaVO")?.optString("code").orEmpty(),
            container.optString("currentSeaAreaCode"),
        ).firstOrNull { it.isNotBlank() }?.let { return it }

        val seaAreaVOs = container.optJSONArray("seaAreaVOs") ?: return ""
        for (index in 0 until seaAreaVOs.length()) {
            val seaAreaVO = seaAreaVOs.optJSONObject(index) ?: continue
            if (!isSeaAreaLocked(seaAreaVO)) {
                return seaAreaVO.optString("code")
            }
        }
        return seaAreaVOs.optJSONObject(0)?.optString("code").orEmpty()
    }

    private fun buildOceanMiscQueryBizTypes(includeActivityProbe: Boolean): List<String> =
        if (includeActivityProbe) {
            listOf(
                "KNOWLEDGE_TIPS",
                "EMERGENCY",
                "HOME_TIPS_REFRESH",
                "NEW_SEA_AREA_CAN_BE_REPAIRED_TIP",
            )
        } else {
            listOf("HOME_TIPS_REFRESH")
        }

    private fun buildNoticeRequest(
        noticeType: String,
        fromAct: String? = null,
    ): JSONObject =
        JSONObject().apply {
            put("needDetail", false)
            put("noticeType", noticeType)
            if (!fromAct.isNullOrBlank()) {
                put("extInfo", JSONObject().put("fromAct", fromAct))
            }
        }

    private fun buildOceanNoticeReqList(scene: OceanNoticeScene): JSONArray {
        val requests = JSONArray()
        when (scene) {
            OceanNoticeScene.HOME_FULL -> {
                requests.put(buildNoticeRequest("INDEX_TASK_NOTICE", fromAct = "dynamic_task"))
                requests.put(buildNoticeRequest("CULTIVATION_LIST_ENTRANCE"))
                requests.put(buildNoticeRequest("INDEX_GAME_ENTRY_NOTICE"))
                requests.put(buildNoticeRequest("INTERACT_RECEIVE_PIECE"))
            }

            OceanNoticeScene.TASK_AND_GAME -> {
                requests.put(buildNoticeRequest("INDEX_TASK_NOTICE", fromAct = "dynamic_task"))
                requests.put(buildNoticeRequest("INDEX_GAME_ENTRY_NOTICE"))
            }

            OceanNoticeScene.GAME_ONLY -> {
                requests.put(buildNoticeRequest("INDEX_GAME_ENTRY_NOTICE"))
            }
        }
        return requests
    }

    private fun logOceanActivityEntranceDetected() {
        logOceanTaskOnce("神奇海洋🌊[检测到海洋活动入口]")
    }

    private fun extractOceanResultDesc(jo: JSONObject): String =
        jo.optString("resultDesc").ifBlank {
            jo.optString("memo").ifBlank {
                jo.optString("desc").ifBlank {
                    jo.optString("errorMsg").ifBlank {
                        jo.toString()
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

    private fun isSelfCleanTask(taskType: String): Boolean = taskType == "CLEAN_RUBBISH_2_EVERY_DAY"

    private fun isHelpFriendCleanTask(
        taskType: String,
    ): Boolean {
        val normalizedTaskType = taskType.uppercase()
        return normalizedTaskType.contains("HELP_CLEAN") ||
            normalizedTaskType.contains("FRIENDRUBBISHCLEAN") ||
            normalizedTaskType.contains("FRIEND_RUBBISH_CLEAN") ||
            normalizedTaskType.contains("CLEAN_FRIEND")
    }

    private fun isConsecutiveVisitTask(
        taskType: String,
    ): Boolean = taskType == "VISISIT_3DAYS_CONSECUTIVE"

    private fun isOceanQuestionTask(taskType: String): Boolean = taskType == "ANTOCEAN_DATI_PINTU_722_new"

    private fun isOceanActionTask(
        taskType: String,
    ): Boolean =
        !isHelpFriendCleanTask(taskType) &&
            (
                isSelfCleanTask(taskType) ||
                    taskType.startsWith("CLEAN_")
            )

    private fun markOceanTasksDoneInvalidated() {
        oceanTasksDoneInvalidatedThisRun = true
        Status.removeFlag(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE)
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
        extractBubbleVOList(refreshedHomePage)?.let { collectEnergy(it) }
        rememberSelfOceanState(refreshedHomePage.optJSONObject("userInfoVO"))
        rememberCurrentSeaAreaCode(refreshedHomePage)
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

    private fun isSeaAreaLocked(seaAreaVO: JSONObject): Boolean =
        when (seaAreaVO.optString("status")) {
            "WAIT_FOR_UNLOCK", "LOCKED", "TO_OPEN", "NOT_OPEN" -> true
            else -> false
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

    private fun buildSeaAreaAdvanceState(
        jo: JSONObject,
        seaAreaVOs: JSONArray,
    ): SeaAreaAdvanceState {
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
            allOpenedSeaAreasCompleted = allOpenedSeaAreasCompleted,
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
            extractBubbleVOList(joHomePage)?.let { collectEnergy(it) }
            val userInfoVO = joHomePage.optJSONObject("userInfoVO")
            rememberSelfOceanState(userInfoVO)
            rememberCurrentSeaAreaCode(joHomePage)
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
            queryMiscInfo(includeActivityProbe = true)
            queryNotice(OceanNoticeScene.HOME_FULL)
            queryRefinedMaterial()
            queryReplicaHome()
            if (cleanOcean?.value == true) {
                queryUserRanking() // 清理
            }
            refreshOceanHomeIfNeeded("能量/清理流程")
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryHomePage err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun runAiFish() {
        try {
            val statusJo = queryAiFishStatus() ?: return
            if (statusJo.optString("fishStatus") == AI_FISH_STATUS_DEFAULT && drawAiFish()) {
                delay(500)
            }

            var homeJo = queryAiFishHomepage(logSummary = true) ?: return
            if (extractAiFishInteractStatus(homeJo) == AI_FISH_STATUS_CAPTURED && rescueAiFish()) {
                delay(500)
                homeJo = queryAiFishHomepage(logSummary = true) ?: homeJo
            }

            if (aiFishAutoTask?.value == true) {
                val taskResult = runAiFishTaskFlow()
                if (taskResult?.actionAttempted == true) {
                    homeJo = queryAiFishHomepage(logSummary = false) ?: homeJo
                }
            }

            touchAiFish(homeJo)
        } catch (t: Throwable) {
            Log.runtime(TAG, "runAiFish err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun queryAiFishStatus(): JSONObject? {
        val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.aiFishStatus()) ?: return null
        if (!ResChecker.checkRes(TAG, "海洋摸鱼状态查询失败:", jo)) {
            Log.ocean("海洋摸鱼🐟状态查询失败：${extractOceanResultDesc(jo)}")
            return null
        }
        return jo
    }

    private fun queryAiFishHomepage(logSummary: Boolean): JSONObject? {
        val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.aiFishHomepage()) ?: return null
        if (!ResChecker.checkRes(TAG, "海洋摸鱼主页查询失败:", jo)) {
            Log.ocean("海洋摸鱼🐟主页查询失败：${extractOceanResultDesc(jo)}")
            return null
        }
        if (logSummary) {
            logAiFishHomepageSummary(jo)
        }
        return jo
    }

    private fun logAiFishHomepageSummary(homeJo: JSONObject) {
        val myFish = homeJo.optJSONObject("myFish")
        val interactVO = myFish?.optJSONObject("interactVO")
        val owner = interactVO?.optJSONObject("owner")
        val currentSeasonInfo = homeJo.optJSONObject("currentSeasonInfo")
        val project = homeJo.optJSONObject("project")
        val seasonTitle =
            currentSeasonInfo
                ?.optJSONObject("extInfo")
                ?.optString("seasonTitle")
                .orEmpty()
        val seasonId = currentSeasonInfo?.optString("seasonId").orEmpty()
        val projectName = project?.optString("projectName").orEmpty()
        val region = project?.optString("region").orEmpty()
        val nickName = myFish?.optString("nickName").orEmpty()
        val fishLevelName = myFish?.optJSONObject("currentLevel")?.optString("name").orEmpty()
        val fishInteractStatus = interactVO?.optString("fishInteractStatus").orEmpty()
        val remainTouchChance = interactVO?.optInt("remainTouchChance", 0) ?: 0
        val touchTotal = interactVO?.optInt("touchTotal", 0) ?: 0
        val touchEnergy = myFish?.optInt("touchEnergy", 0) ?: 0
        val totalEnergy = homeJo.optLong("energy", 0L)
        val ownerSuffix =
            owner
                ?.optString("nickName")
                ?.takeIf { it.isNotBlank() }
                ?.let { " 主人[$it]" }
                .orEmpty()
        Log.ocean(
            "海洋摸鱼🐟赛季[$seasonTitle]($seasonId)项目[$projectName]($region)用户[$nickName]" +
                "状态[$fishInteractStatus]等级[$fishLevelName]可摸鱼${remainTouchChance}次" +
                "累计摸${touchTotal}次累计获得${touchEnergy}g(总${totalEnergy}g)$ownerSuffix",
        )
    }

    private fun drawAiFish(): Boolean {
        return try {
            val imgUrl = AI_FISH_IMAGE_URLS[RandomUtil.nextInt(0, AI_FISH_IMAGE_URLS.size)]
            val start = imgUrl.indexOf("img/")
            val end = imgUrl.indexOf("/original")
            if (start < 0 || end <= start) {
                Log.error(TAG, "海洋摸鱼🐟画鱼资源缺少 imgId：$imgUrl")
                return false
            }
            val imgId = imgUrl.substring(start + 4, end)
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.drawAiFish(imgId, imgUrl)) ?: return false
            if (!ResChecker.checkRes(TAG, "海洋摸鱼开通画鱼失败:", jo)) {
                Log.ocean("海洋摸鱼🐟开通画鱼失败：${extractOceanResultDesc(jo)}")
                return false
            }
            Log.ocean("海洋摸鱼🐟提交画鱼成功")
            Toast.show("海洋摸鱼🐟提交画鱼成功")
            true
        } catch (t: Throwable) {
            Log.runtime(TAG, "drawAiFish err:")
            Log.printStackTrace(TAG, t)
            false
        }
    }

    private fun rescueAiFish(): Boolean {
        return try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.rescueAiFish()) ?: return false
            if (!ResChecker.checkRes(TAG, "海洋摸鱼解救失败:", jo)) {
                Log.ocean("海洋摸鱼🐟解救失败：${extractOceanResultDesc(jo)}")
                return false
            }
            val remainChance = extractAiFishRemainTouchChance(jo)
            val fishInteractStatus = extractAiFishInteractStatus(jo)
            Log.ocean("海洋摸鱼🐟解救成功[状态:$fishInteractStatus][剩余次数:${remainChance ?: 0}]")
            Toast.show("海洋摸鱼🐟解救成功")
            true
        } catch (t: Throwable) {
            Log.runtime(TAG, "rescueAiFish err:")
            Log.printStackTrace(TAG, t)
            false
        }
    }

    private fun runAiFishTaskFlow(): TaskFlowRunResult? =
        try {
            TaskFlowEngine(AiFishTaskFlowAdapter(), roundSleepMs = 500L).run()
        } catch (t: Throwable) {
            Log.runtime(TAG, "runAiFishTaskFlow err:")
            Log.printStackTrace(TAG, t)
            null
        }

    private inner class AiFishTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = TASK_BLACKLIST_MODULE
        override val flowName: String = "海洋摸鱼任务"

        override fun query(): JSONObject {
            val response = AntOceanRpcCall.aiFishListTask()
            return JsonUtil.parseJSONObjectOrNull(response) ?: JSONObject()
                .put("success", false)
                .put("resultDesc", "aiFishListTask返回空或无法解析")
        }

        override fun isQuerySuccess(response: JSONObject): Boolean = ResChecker.checkRes(TAG, response)

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskInfoList = response.optJSONArray("taskInfoList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskInfoList.length()) {
                val taskInfo = taskInfoList.optJSONObject(i) ?: continue
                val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: continue
                val taskType = taskBaseInfo.optString("taskType").trim()
                if (taskType.isBlank()) {
                    continue
                }
                val bizInfo = parseJSONObject(taskBaseInfo.opt("bizInfo")) ?: JSONObject()
                val taskRights = taskInfo.optJSONObject("taskRights") ?: JSONObject()
                val taskTitle = bizInfo.optString("taskTitle", taskType).trim().ifBlank { taskType }
                val taskStatus = taskBaseInfo.optString("taskStatus").trim()
                val awardCount = taskRights.optInt("awardCount", 0)
                val raw =
                    JSONObject()
                        .put("taskInfo", taskInfo)
                        .put("taskBaseInfo", taskBaseInfo)
                        .put("taskRights", taskRights)
                        .put("awardCount", awardCount)
                        .put("taskMode", taskBaseInfo.optString("taskMode"))

                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = taskTitle,
                        status = taskStatus,
                        type = taskType,
                        sceneCode = AI_FISH_SCENE_CODE,
                        blacklistKeys = listOf(taskType, taskTitle),
                        raw = raw,
                        progress = "award=$awardCount",
                    ),
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when {
                isRewardReadyStatus(item.status) -> TaskFlowPhase.REWARD_READY

                isRewardReceivedStatus(item.status) ||
                    item.status == "DONE" ||
                    item.status == "COMPLETED" -> TaskFlowPhase.TERMINAL

                item.status == TaskStatus.TODO.name -> TaskFlowPhase.READY_TO_COMPLETE

                else -> TaskFlowPhase.UNKNOWN
            }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntOceanRpcCall.aiFishReceiveTaskAward(item.type)
            val result =
                JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "aiFishReceiveTaskAward返回空或无法解析",
                    rpc = "AntOceanRpcCall.aiFishReceiveTaskAward",
                    raw = response,
                    detail = aiFishTaskActionDetail(item, "receiveTaskAward"),
                    stopCurrentRound = true,
                )
            if (isOceanTaskRpcSuccess(result)) {
                val awardCount = item.raw?.optInt("awardCount", 0) ?: 0
                Log.ocean("摸鱼任务🎖️[${item.title}]获得摸鱼次数*$awardCount")
                return TaskFlowActionResult.success()
            }
            return aiFishTaskActionFailureResult(
                item = item,
                response = result,
                rpc = "AntOceanRpcCall.aiFishReceiveTaskAward",
                detail = aiFishTaskActionDetail(item, "receiveTaskAward"),
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntOceanRpcCall.aiFishFinishTask(item.type)
            val result =
                JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "aiFishFinishTask返回空或无法解析",
                    rpc = "AntOceanRpcCall.aiFishFinishTask",
                    raw = response,
                    detail = aiFishTaskActionDetail(item, "finishTask"),
                    stopCurrentRound = true,
                )
            if (isOceanTaskRpcSuccess(result)) {
                Log.ocean("摸鱼任务🧾️[${item.title}]")
                return TaskFlowActionResult.success()
            }
            return aiFishTaskActionFailureResult(
                item = item,
                response = result,
                rpc = "AntOceanRpcCall.aiFishFinishTask",
                detail = aiFishTaskActionDetail(item, "finishTask"),
            )
        }

        override fun logInfo(message: String) {
            Log.ocean(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }
    }

    private fun aiFishTaskActionFailureResult(
        item: TaskFlowItem,
        response: JSONObject,
        rpc: String,
        detail: String,
    ): TaskFlowActionResult {
        val failureType = classifyOceanTaskFailure(response)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = extractOceanTaskFailureCode(response),
            message = extractOceanTaskFailureMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = detail,
        )
    }

    private fun aiFishTaskActionDetail(
        item: TaskFlowItem,
        action: String,
    ): String {
        val awardCount = item.raw?.optInt("awardCount", 0) ?: 0
        val taskMode = item.raw?.optString("taskMode").orEmpty()
        return buildString {
            append("sceneCode=")
            append(item.sceneCode)
            append(" taskType=")
            append(item.type)
            append(" action=")
            append(action)
            append(" awardCount=")
            append(awardCount)
            if (taskMode.isNotBlank()) {
                append(" taskMode=")
                append(taskMode)
            }
        }
    }

    private fun touchAiFish(homeJo: JSONObject) {
        try {
            var remainTouchChance = extractAiFishRemainTouchChance(homeJo) ?: 0
            if (remainTouchChance <= 0) {
                Log.ocean("海洋摸鱼🐟当前无可用摸鱼次数")
                return
            }
            val maxAttempts = remainTouchChance + 3
            var touchCount = 0
            var totalEnergy = 0
            while (remainTouchChance > 0 && touchCount < maxAttempts) {
                val response = AntOceanRpcCall.touchAiFish()
                val result = JsonUtil.parseJSONObjectOrNull(response) ?: break
                if (result.optString("resultCode") == "USER_CAPTURED") {
                    Log.ocean("海洋摸鱼🐟目标已被其他用户抢先处理，刷新摸鱼状态")
                    queryAiFishHomepage(logSummary = true)
                    break
                }
                if (!ResChecker.checkRes(TAG, "海洋摸鱼执行失败:", result)) {
                    Log.ocean("海洋摸鱼🐟摸鱼失败：${extractOceanResultDesc(result)}")
                    break
                }
                touchCount += 1

                val rewardName = extractAiFishRewardName(result)
                val energyGain = extractAiFishRewardEnergy(result)
                val captureName =
                    result
                        .optJSONObject("captureInfoVO")
                        ?.optString("nickName")
                        .orEmpty()
                        .ifBlank {
                            result
                                .optJSONObject("captureInfoVO")
                                ?.optString("userId")
                                .orEmpty()
                        }
                if (energyGain > 0) {
                    totalEnergy += energyGain
                    if (captureName.isNotBlank() && result.optString("touchType") == "touchFish") {
                        Log.ocean("海洋摸鱼🐟摸到了[$captureName]的鱼获得${energyGain}g能量")
                    } else {
                        Log.ocean("海洋摸鱼🐟[$rewardName]${energyGain}g")
                    }
                    Toast.show("海洋摸鱼🐟获得${energyGain}g能量")
                }

                val nextRemainTouchChance = extractAiFishRemainTouchChance(result)
                val stateChanged = nextRemainTouchChance != null && nextRemainTouchChance != remainTouchChance
                if (!stateChanged && energyGain <= 0 && captureName.isBlank()) {
                    Log.ocean("海洋摸鱼🐟本次未观测到次数变化或奖励，停止循环避免空转")
                    break
                }
                remainTouchChance =
                    if (nextRemainTouchChance != null) {
                        nextRemainTouchChance
                    } else {
                        val refreshedHome = queryAiFishHomepage(logSummary = false)
                        extractAiFishRemainTouchChance(refreshedHome ?: JSONObject())
                            ?: (remainTouchChance - 1).coerceAtLeast(0)
                    }
            }
            if (touchCount > 0) {
                Log.ocean("海洋摸鱼🐟本次共摸鱼${touchCount}次获得${totalEnergy}g能量")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "touchAiFish err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun extractAiFishRemainTouchChance(payload: JSONObject): Int? =
        payload
            .optJSONObject("myFish")
            ?.optJSONObject("interactVO")
            ?.optInt("remainTouchChance")

    private fun extractAiFishInteractStatus(payload: JSONObject): String =
        payload
            .optJSONObject("myFish")
            ?.optJSONObject("interactVO")
            ?.optString("fishInteractStatus")
            .orEmpty()

    private fun extractAiFishRewardName(payload: JSONObject): String {
        val touchRewardList = payload.optJSONArray("touchRewardList") ?: return "奖励"
        for (i in 0 until touchRewardList.length()) {
            val reward = touchRewardList.optJSONObject(i) ?: continue
            val popup = reward.optJSONObject("extInfo")?.optJSONObject("popup") ?: continue
            val name = popup.optString("name")
            if (name.isNotBlank()) {
                return name
            }
        }
        return "奖励"
    }

    private fun extractAiFishRewardEnergy(payload: JSONObject): Int {
        val touchRewardList = payload.optJSONArray("touchRewardList") ?: return 0
        var totalEnergy = 0
        for (i in 0 until touchRewardList.length()) {
            val reward = touchRewardList.optJSONObject(i) ?: continue
            val popup = reward.optJSONObject("extInfo")?.optJSONObject("popup") ?: continue
            totalEnergy += popup.optInt("rightsNums", 0)
        }
        return totalEnergy
    }

    private fun queryMiscInfo(
        includeActivityProbe: Boolean = false,
        targetUserId: String? = null,
    ) {
        try {
            val s =
                AntOceanRpcCall.queryMiscInfo(
                    queryBizTypes = buildOceanMiscQueryBizTypes(includeActivityProbe),
                    targetUserId = targetUserId,
                )
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val miscHandlerVOMap = jo.optJSONObject("miscHandlerVOMap") ?: return
                val emergency = miscHandlerVOMap.optJSONObject("EMERGENCY")
                if (emergency?.optBoolean("showEmergency") == true || emergency?.optBoolean("showIntroduceBubble") == true) {
                    logOceanActivityEntranceDetected()
                }
            } else {
                Log.runtime(TAG, extractOceanResultDesc(jo))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryMiscInfo err:")
            Log.printStackTrace(TAG, t)
        }
    }

    // 最新 queryHomePage 把能量球挪到了 resData.bubbleVOList（userInfoVO 等仍在顶层）；优先取 resData，回退顶层兼容旧结构
    private fun extractBubbleVOList(homePage: JSONObject): JSONArray? =
        homePage.optJSONObject("resData")?.optJSONArray("bubbleVOList")
            ?: homePage.optJSONArray("bubbleVOList")

    private fun collectEnergy(bubbleVOList: JSONArray) {
        val antForest = getModel(AntForest::class.java)
        // 逐条处理：单个能量球字段缺失/结构变化只跳过该条并记录，不再因一个异常中断整段收取（根因A）
        for (i in 0 until bubbleVOList.length()) {
            try {
                val bubble = bubbleVOList.optJSONObject(i) ?: continue
                if ("AVAILABLE" != bubble.optString("collectStatus")) {
                    continue
                }
                val bubbleId = bubble.optLong("id")
                val userId = bubble.optString("userId")
                if (bubbleId <= 0L || userId.isBlank()) {
                    continue
                }
                val energy = bubble.optInt("fullEnergy", bubble.optInt("energy", -1))
                if (energy < 0) {
                    Log.runtime(TAG, "神奇海洋🌊能量球[$bubbleId]缺少有效能量值，跳过收取")
                    continue
                }
                if (antForest != null && !antForest.shouldCollectSelfBubble(energy)) {
                    continue
                }
                val s = AntOceanRpcCall.collectEnergy(bubbleId.toString(), userId)
                val jo = JsonUtil.parseJSONObjectOrNull(s) ?: continue
                if (ResChecker.checkRes(TAG, jo)) {
                    val retBubbles = jo.optJSONArray("bubbles")
                    if (retBubbles != null) {
                        for (j in 0 until retBubbles.length()) {
                            val retBubble = retBubbles.optJSONObject(j) ?: continue
                            val collectedEnergy = retBubble.optInt("collectedEnergy")
                            Log.ocean("神奇海洋🌊收取[${UserMap.getMaskName(userId)}]#${collectedEnergy}g")
                            Toast.show("海洋能量🌊收取[${UserMap.getMaskName(userId)}]#${collectedEnergy}g")
                        }
                        markOceanHomeRefreshNeeded()
                    }
                } else {
                    Log.runtime(TAG, jo.optString("resultDesc"))
                }
            } catch (t: Throwable) {
                Log.runtime(TAG, "collectEnergy item err:")
                Log.printStackTrace(TAG, t)
            }
        }
    }

    private fun cleanOcean(
        userId: String,
        rubbishNumber: Int,
    ) {
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

    private suspend fun unLockReplicaPhase(
        replicaCode: String,
        replicaPhaseCode: String,
    ) {
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
                val seaAreaName =
                    jo.optJSONObject("currentSeaAreaVO")?.optString("name")
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

    private suspend fun createSeaAreaExtraCollect(seaAreaCode: String) {
        try {
            val jo =
                JsonUtil.parseJSONObjectOrNull(
                    AntOceanRpcCall.createSeaAreaExtraCollect(seaAreaCode),
                ) ?: return
            if (ResChecker.checkRes(TAG, jo)) {
                val extraCollectVO = jo.optJSONObject("seaAreaExtraCollectVO")
                val extraCollectName =
                    extraCollectVO?.optJSONObject("seaAreaVO")?.optString("name")
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
                val seaAreaCode =
                    currentSeaAreaCode.orEmpty().ifBlank {
                        extractCurrentSeaAreaCode(detailJo)
                    }
                if (seaAreaCode.isBlank()) {
                    Log.runtime(TAG, "createSeaAreaExtraCollect skip: currentSeaAreaCode为空")
                    return
                }
                querySeaAreaDetailData(seaAreaCode) ?: return
                createSeaAreaExtraCollect(seaAreaCode)
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

    private suspend fun querySeaAreaDetailData(seaAreaCode: String = ""): JSONObject? {
        val s = AntOceanRpcCall.querySeaAreaDetailList(seaAreaCode)
        val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return null
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.runtime(TAG, extractOceanResultDesc(jo))
            return null
        }
        rememberCurrentSeaAreaCode(jo)
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

    private fun extractNestedJsonArray(
        container: JSONObject,
        key: String,
    ): JSONArray? = container.optJSONArray(key) ?: container.optJSONObject("resData")?.optJSONArray(key)

    private fun shouldCleanFriend(userId: String): Boolean {
        if (FriendGuard.shouldSkipFriend(userId, TAG, "神奇海洋好友清理")) {
            return false
        }
        var isOceanClean = cleanOceanList?.contains(userId) == true
        if (cleanOceanType?.value == CleanOceanType.DONT_CLEAN) {
            isOceanClean = !isOceanClean
        }
        return isOceanClean
    }

    private fun giveFriendPiece(userId: String) {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.giveFriendPiece(userId)) ?: return
            if (isFriendPieceAlreadyGiven(jo)) {
                return
            }
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isFriendPieceReceiveLimit(jo)) {
                    Log.error(
                        TAG,
                        "神奇海洋🌊[送碎片]#好友=$userId 已被其他用户送过，跳过当前好友并继续后续选择",
                    )
                } else {
                    Log.error(
                        TAG,
                        "神奇海洋🌊[送碎片]#好友=$userId 请求失败:" + extractOceanTaskFailureMessage(jo),
                    )
                }
                return
            }
            extractNestedJsonArray(jo, "normalRewardVOS")?.let { checkReward(it) }
        } catch (t: Throwable) {
            Log.runtime(TAG, "giveFriendPiece err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun refreshOceanPropListState() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryOceanPropList()) ?: return
            ResChecker.checkRes(TAG, jo)
        } catch (t: Throwable) {
            Log.runtime(TAG, "refreshOceanPropListState err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun refreshUserRankingState() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryUserRanking()) ?: return
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "refreshUserRankingState err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun refreshUserDynamicStatisticsState() {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.queryUserDynamicStatistics()) ?: return
            ResChecker.checkRes(TAG, jo)
        } catch (t: Throwable) {
            Log.runtime(TAG, "refreshUserDynamicStatisticsState err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun cleanFriendByUserId(userId: String): FriendCleanAttemptResult {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
            return FriendCleanAttemptResult(cleaned = false, limitReached = true)
        }
        if (!shouldCleanFriend(userId)) {
            return FriendCleanAttemptResult(cleaned = false)
        }
        try {
            val friendPageResponse =
                AntOceanRpcCall.queryFriendPage(
                    userId = userId,
                    fromAct = "SAIL_AWAY",
                    currentUserId = currentOceanUserId,
                )
            var jo = JsonUtil.parseJSONObjectOrNull(friendPageResponse) ?: return FriendCleanAttemptResult(cleaned = false)
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                    return FriendCleanAttemptResult(cleaned = false, limitReached = true)
                }
                if (isOceanFriendNotOpen(jo)) {
                    FriendCapabilityRecorder.record(
                        userId,
                        "OCEAN",
                        FriendCapabilityState.NOT_OPEN,
                        "AntOcean.queryFriendPage",
                        extractOceanResultDesc(jo),
                    )
                }
                Log.runtime(TAG, extractOceanResultDesc(jo))
                return FriendCleanAttemptResult(cleaned = false)
            }

            FriendCapabilityRecorder.record(userId, "OCEAN", FriendCapabilityState.OPEN, "AntOcean.queryFriendPage")
            queryMiscInfo(includeActivityProbe = true, targetUserId = userId)
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                return FriendCleanAttemptResult(cleaned = false, limitReached = true)
            }

            jo = JsonUtil.parseJSONObjectOrNull(AntOceanRpcCall.cleanFriendOcean(userId))
                ?: return FriendCleanAttemptResult(cleaned = false)
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                    return FriendCleanAttemptResult(cleaned = false, limitReached = true)
                }
                Log.runtime(TAG, extractOceanResultDesc(jo))
                return FriendCleanAttemptResult(cleaned = false)
            }

            val maskName = UserMap.getMaskName(userId) ?: userId
            Log.ocean("神奇海洋🌊[帮助:${maskName}清理海域]")
            extractNestedJsonArray(jo, "cleanRewardVOS")?.let { checkReward(it) }
            giveFriendPiece(userId)
            markOceanHomeRefreshNeeded()
            return FriendCleanAttemptResult(cleaned = true)
        } catch (t: Throwable) {
            Log.runtime(TAG, "cleanFriendByUserId err:")
            Log.printStackTrace(TAG, t)
        }
        return FriendCleanAttemptResult(cleaned = false)
    }

    @Suppress("ReturnCount")
    private fun cleanFriendOcean(fillFlag: JSONObject): Int {
        if (!fillFlag.optBoolean("canClean")) {
            return 0
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
            return 0
        }
        val userId = fillFlag.optString("userId")
        if (userId.isBlank()) {
            return 0
        }
        return if (cleanFriendByUserId(userId).cleaned) 1 else 0
    }

    private fun fillUserFlagAndClean(
        userIds: List<String>,
        maxSuccessfulCleans: Int = Int.MAX_VALUE,
    ): Int {
        if (userIds.isEmpty() || maxSuccessfulCleans <= 0) return 0
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) return 0

        val ja = JSONArray()
        for (id in userIds) {
            if (id.isNotBlank() && !FriendGuard.shouldSkipFriend(id, TAG, "神奇海洋好友清理")) {
                ja.put(id)
            }
        }
        if (ja.length() == 0) return 0

        val s = AntOceanRpcCall.fillUserFlag(ja)
        val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return 0
        if (!ResChecker.checkRes(TAG, jo)) {
            if (isHelpCleanLimit(jo)) {
                markHelpCleanLimit(jo)
            } else {
                Log.runtime(TAG, extractOceanResultDesc(jo))
            }
            return 0
        }

        val fillFlagVOList = jo.optJSONArray("fillFlagVOList") ?: return 0
        var successfulCleans = 0
        for (i in 0 until fillFlagVOList.length()) {
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) return successfulCleans
            successfulCleans += cleanFriendOcean(fillFlagVOList.getJSONObject(i))
            if (successfulCleans >= maxSuccessfulCleans) {
                return successfulCleans
            }
        }
        return successfulCleans
    }

    private fun queryUserRanking(maxSuccessfulCleans: Int = Int.MAX_VALUE): Int {
        var successfulCleans = 0
        try {
            if (cleanOcean?.value != true || maxSuccessfulCleans <= 0) {
                return 0
            }
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                return 0
            }
            val s = AntOceanRpcCall.queryUserRanking()
            val jo = JsonUtil.parseJSONObjectOrNull(s) ?: return 0
            if (!ResChecker.checkRes(TAG, jo)) {
                if (isHelpCleanLimit(jo)) {
                    markHelpCleanLimit(jo)
                } else {
                    Log.runtime(TAG, extractOceanResultDesc(jo))
                }
                return 0
            }

            // 1) 先处理首屏 fillFlagVOList（通常为 20 个）
            val firstFillFlags = jo.optJSONArray("fillFlagVOList")
            if (firstFillFlags != null) {
                for (i in 0 until firstFillFlags.length()) {
                    successfulCleans += cleanFriendOcean(firstFillFlags.getJSONObject(i))
                    if (successfulCleans >= maxSuccessfulCleans ||
                        Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)
                    ) {
                        return successfulCleans
                    }
                }
            }

            // 2) 扩展处理：若首屏无可清理，继续从 allRankingList 取后续用户并通过 fillUserFlag 补全 canClean
            val allRankingList = jo.optJSONArray("allRankingList") ?: return successfulCleans
            val pageSize = jo.optInt("pageSize", 20).takeIf { it in 1..50 } ?: 20

            var pos = pageSize
            val idList = ArrayList<String>(pageSize)
            val currentUid = UserMap.currentUid

            while (pos < allRankingList.length() &&
                successfulCleans < maxSuccessfulCleans &&
                !Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)
            ) {
                val friend = allRankingList.optJSONObject(pos)
                val userId = friend?.optString("userId").orEmpty()
                if (userId.isNotBlank() && userId != currentUid) {
                    idList.add(userId)
                }

                pos++
                if (idList.size >= pageSize) {
                    successfulCleans +=
                        fillUserFlagAndClean(
                            idList,
                            maxSuccessfulCleans - successfulCleans,
                        )
                    idList.clear()
                }
            }

            if (idList.isNotEmpty() &&
                successfulCleans < maxSuccessfulCleans &&
                !Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)
            ) {
                successfulCleans +=
                    fillUserFlagAndClean(
                        idList,
                        maxSuccessfulCleans - successfulCleans,
                    )
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryUserRanking err:")
            Log.printStackTrace(TAG, t)
        }
        return successfulCleans
    }

    private suspend fun receiveTaskAward(): TaskFlowRunResult? {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE) && !oceanTasksDoneInvalidatedThisRun) {
            Log.ocean("海洋任务🌊[今日已确认完成，跳过重复查询]")
            return null
        }
        try {
            val adapter = OceanTaskFlowAdapter()
            val result = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
            if (!result.stopped && !result.interrupted && adapter.canMarkTasksDoneForToday()) {
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
        private var querySucceeded = false
        private var latestItems: List<TaskFlowItem> = emptyList()
        private var unknownPhaseSeen = false
        private var unknownFailureSeen = false

        override val moduleName: String = TASK_BLACKLIST_MODULE
        override val flowName: String = "神奇海洋任务"

        override fun isFlowHandledToday(): Boolean =
            Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE) && !oceanTasksDoneInvalidatedThisRun

        override fun query(): JSONObject {
            val response = AntOceanRpcCall.queryTaskList()
            return JsonUtil.parseJSONObjectOrNull(response) ?: JSONObject()
                .put("success", false)
                .put("resultDesc", "queryTaskList返回空或无法解析")
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            querySucceeded = ResChecker.checkRes(TAG, response)
            return querySucceeded
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONArray("antOceanTaskVOList")
            if (taskList == null) {
                latestItems = emptyList()
                return emptyList()
            }
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val bizInfo = parseJSONObject(task.opt("bizInfo")) ?: JSONObject()
                val extendInfo = parseJSONObject(task.opt("extend")) ?: JSONObject()
                val taskType = task.optString("taskType").trim()
                if (taskType.isBlank()) {
                    continue
                }
                val taskTitle = bizInfo.optString("taskTitle", taskType).trim().ifBlank { taskType }
                val sceneCode = task.optString("sceneCode").trim()
                val taskStatus = task.optString("taskStatus").trim()
                val awardCount =
                    bizInfo
                        .optString("awardCount", task.optString("awardCount", "0"))
                        .trim()
                        .ifBlank { "0" }
                val taskProgress = parseOceanTaskProgressInt(task, "taskProgress")
                val taskRequire = parseOceanTaskProgressInt(task, "taskRequire")?.takeIf { it > 0 }
                val raw =
                    JSONObject()
                        .put("task", task)
                        .put("bizInfo", bizInfo)
                        .put("awardCount", awardCount)
                        .put("extend", extendInfo)
                        .put("rightsTimes", task.opt("rightsTimes"))
                        .put("rightsTimesLimit", task.opt("rightsTimesLimit"))
                        .put("alreadyReceiveAwardCount", extendInfo.opt("alreadyReceiveAwardCount"))
                        .put("iepTaskTracer", task.optString("iepTaskTracer"))

                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = taskTitle,
                        status = taskStatus,
                        type = taskType,
                        sceneCode = sceneCode,
                        actionType =
                            task.optString("actionType").ifBlank {
                                bizInfo.optString("actionType")
                            },
                        blacklistKeys = listOf(taskType, taskTitle).filter { it.isNotBlank() },
                        raw = raw,
                        progress = "award=$awardCount progress=${taskProgress ?: 0}/${taskRequire ?: 0}",
                        current = taskProgress,
                        limit = taskRequire,
                    ),
                )
            }
            latestItems = items
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when {
                isRewardReadyStatus(item.status) -> {
                    TaskFlowPhase.REWARD_READY
                }

                isRewardReceivedStatus(item.status) ||
                    item.status == "HAS_RECEIVED" ||
                    item.status == "DONE" ||
                    item.status == "COMPLETED" -> {
                    TaskFlowPhase.TERMINAL
                }

                item.status == TaskStatus.TODO.name && isConsecutiveVisitTask(item.type) -> {
                    mapConsecutiveVisitPhase(item)
                }

                item.status == TaskStatus.TODO.name && isHelpFriendCleanTask(item.type) -> {
                    TaskFlowPhase.READY_TO_COMPLETE
                }

                item.status == TaskStatus.TODO.name && isSelfCleanTask(item.type) -> {
                    TaskFlowPhase.READY_TO_COMPLETE
                }

                item.status == TaskStatus.TODO.name && isOceanActionTask(item.type) -> {
                    TaskFlowPhase.BUSINESS_ACTION
                }

                item.status == TaskStatus.TODO.name -> {
                    TaskFlowPhase.READY_TO_COMPLETE
                }

                else -> {
                    TaskFlowPhase.UNKNOWN
                }
            }

        override fun shouldSkipByTodayState(item: TaskFlowItem): Boolean {
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT) &&
                isHelpFriendCleanTask(item.type) &&
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
            if (shouldBypassOceanBlacklist(item)) {
                return false
            }
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logOceanTaskOnce("海洋任务🌊[${item.title}]已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            // 海洋最新快照里 RECEIVED/HAS_RECEIVED 都表示奖励已领终态，不能再次发领奖 RPC。
            if (isRewardReceivedStatus(item.status)) {
                logOceanTaskOnce("海洋任务🌊[${item.title}]已处于领奖终态，跳过重复领奖")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.TERMINAL_DONE,
                    code = "ALREADY_RECEIVED_STATE",
                    message = "任务已处于已领奖终态，跳过重复领奖",
                    rpc = "AntOcean.receive.guard",
                    detail = oceanTaskActionDetail(item, "receiveGuard", "decision=MARK_HANDLED"),
                )
            }
            val response = AntOceanRpcCall.receiveTaskAward(item.sceneCode, item.type)
            val result =
                JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "receiveTaskAward返回空或无法解析",
                    rpc = "AntOceanRpcCall.receiveTaskAward",
                    raw = response,
                    detail = oceanTaskActionDetail(item, "receiveTaskAward"),
                    stopCurrentRound = true,
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
                    stopCurrentRound = true,
                )
            }
            if (isOceanTaskRpcSuccess(result)) {
                val awardCount = item.raw?.optString("awardCount", "0") ?: "0"
                Log.ocean("海洋奖励🌊[${item.title}]# $awardCount 拼图")
                markOceanHomeRefreshNeeded()
                return TaskFlowActionResult.success()
            }
            return oceanTaskActionFailureResult(
                item = item,
                response = result,
                rpc = "AntOceanRpcCall.receiveTaskAward",
                detail = oceanTaskActionDetail(item, "receiveTaskAward"),
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (isHelpFriendCleanTask(item.type)) {
                return completeHelpFriendCleanTask(item)
            }

            if (isConsecutiveVisitTask(item.type)) {
                mapConsecutiveVisitPhase(item)
                return TaskFlowActionResult.success(progressChanged = false)
            }

            if (isSelfCleanTask(item.type)) {
                return if (retrySelfOceanCleanIfNeeded(item.title)) {
                    TaskFlowActionResult.success()
                } else {
                    val detail = oceanTaskActionDetail(item, "retrySelfOceanCleanIfNeeded")
                    logOceanTaskOnce("海洋任务🌊[${item.title}]自清理业务动作当前无可推进垃圾，$detail")
                    TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.NO_PROGRESS_COOLDOWN,
                        message = "自清理当前无可推进垃圾",
                        rpc = "AntOcean.retrySelfOceanCleanIfNeeded",
                        detail = detail,
                    )
                }
            }

            if (isOceanQuestionTask(item.type)) {
                return if (answerQuestion()) {
                    TaskFlowActionResult.success()
                } else {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "答题流程未完成",
                        rpc = "AntOcean.answerQuestion",
                        detail = oceanTaskActionDetail(item, "answerQuestion"),
                    )
                }
            }

            return finishOceanTask(item)
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String = "${action.logName}:${buildOceanTaskBizKey(item.sceneCode, item.type, item.title)}"

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision,
        ) {
            if (result.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW) {
                unknownFailureSeen = true
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.ocean("查询任务列表失败：" + extractOceanTaskFailureMessage(response))
        }

        override fun onUnknownPhase(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ) {
            unknownPhaseSeen = true
            Log.error(
                TAG,
                "$flowName[未知状态：${item.title}] taskId=${item.id.ifBlank { "UNKNOWN" }} " +
                    "status=${item.status.ifBlank { "UNKNOWN" }} actionType=${item.actionType.ifBlank { "UNKNOWN" }}",
            )
        }

        override fun logInfo(message: String) {
            Log.ocean(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        fun canMarkTasksDoneForToday(): Boolean {
            if (!querySucceeded || unknownPhaseSeen || unknownFailureSeen) {
                return false
            }
            for (item in latestItems) {
                if (shouldSkipByTodayState(item)) {
                    continue
                }
                val phase = mapPhase(item)
                if (phase == TaskFlowPhase.UNKNOWN) {
                    return false
                }
                if (phase == TaskFlowPhase.TERMINAL) {
                    continue
                }
                if (isConsecutiveVisitTaskWaitingForLaterDay(item, phase)) {
                    continue
                }
                if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT) &&
                    isHelpFriendCleanTask(item.type) &&
                    phase != TaskFlowPhase.REWARD_READY
                ) {
                    continue
                }
                if (phase != TaskFlowPhase.REWARD_READY && isBlacklisted(item)) {
                    continue
                }
                return false
            }
            return true
        }

        private fun isConsecutiveVisitTaskWaitingForLaterDay(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ): Boolean {
            val limit = item.limit ?: return false
            val current = item.current ?: return false
            return phase == TaskFlowPhase.BUSINESS_ACTION &&
                item.status == TaskStatus.TODO.name &&
                isConsecutiveVisitTask(item.type) &&
                current < limit
        }
    }

    private fun mapConsecutiveVisitPhase(item: TaskFlowItem): TaskFlowPhase {
        val current = item.current ?: 0
        val limit = item.limit
        val progressMessage =
            when {
                limit == null -> "缺少有效进度[${item.progress}]，等待服务端刷新"
                current < limit -> "进度未满[$current/$limit]，等待连续访问进度推进"
                else -> "进度已满[$current/$limit]，等待服务端刷新为可领奖状态"
            }
        logOceanTaskOnce("海洋任务🌊[${item.title}]$progressMessage，不调用finishTask")
        return TaskFlowPhase.BUSINESS_ACTION
    }

    private fun shouldBypassOceanBlacklist(item: TaskFlowItem): Boolean =
        isRewardReceivedStatus(item.status) ||
            isHelpFriendCleanTask(item.type) ||
            isConsecutiveVisitTask(item.type)

    private fun completeHelpFriendCleanTask(item: TaskFlowItem): TaskFlowActionResult {
        if (cleanOcean?.value != true) {
            logOceanTaskOnce("海洋任务🌊[${item.title}]好友清理未开启，等待手动完成")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                message = "好友清理未开启，等待手动完成",
                rpc = "AntOcean.completeHelpFriendCleanTask",
                detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
            )
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
            logOceanTaskOnce("海洋任务🌊[${item.title}]帮助清理次数已达上限，等待后续任务状态刷新")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                message = "帮助清理次数已达上限",
                rpc = "AntOcean.completeHelpFriendCleanTask",
                detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
            )
        }

        val taskEntryHomePage =
            queryHomePageData(showTaskPanel = true)
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "任务入口主页刷新失败",
                    rpc = "AntOcean.queryHomePageData",
                    detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                )
        rememberSelfOceanState(taskEntryHomePage.optJSONObject("userInfoVO"))
        rememberCurrentSeaAreaCode(taskEntryHomePage)
        logOceanTaskOnce("海洋任务🌊[${item.title}]从任务面板入口触发一次好友清理")

        val skipUsers = JSONObject()
        repeat(20) {
            val sailingAwayResponse = AntOceanRpcCall.sailingAway(skipUsers)
            val sailingAwayResult =
                JsonUtil.parseJSONObjectOrNull(sailingAwayResponse)
                    ?: return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        message = "sailingAway返回空或无法解析",
                        rpc = "AntOceanRpcCall.sailingAway",
                        raw = sailingAwayResponse,
                        detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                    )
            if (!ResChecker.checkRes(TAG, sailingAwayResult)) {
                if (isHelpCleanLimit(sailingAwayResult)) {
                    markHelpCleanLimit(sailingAwayResult)
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        message = "帮助清理次数已达上限",
                        rpc = "AntOceanRpcCall.sailingAway",
                        detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                    )
                }
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = extractOceanTaskFailureCode(sailingAwayResult),
                    message = extractOceanResultDesc(sailingAwayResult),
                    rpc = "AntOceanRpcCall.sailingAway",
                    raw = sailingAwayResult.toString(),
                    detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                )
            }

            val friendUserId =
                sailingAwayResult.optString("friendId").ifBlank {
                    sailingAwayResult.optJSONObject("resData")?.optString("friendId").orEmpty()
                }
            if (friendUserId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    message = "任务入口未返回可清理好友",
                    rpc = "AntOceanRpcCall.sailingAway",
                    detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                )
            }
            if (!shouldCleanFriend(friendUserId)) {
                skipUsers.put(friendUserId, "clean")
                return@repeat
            }

            val cleanResult = cleanFriendByUserId(friendUserId)
            if (cleanResult.cleaned) {
                queryMiscInfo()
                refreshOceanPropListState()
                queryNotice(OceanNoticeScene.TASK_AND_GAME)
                refreshUserRankingState()
                refreshUserDynamicStatisticsState()
                return TaskFlowActionResult.success(refreshAfterAction = true)
            }
            if (cleanResult.limitReached || Status.hasFlagToday(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT)) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    message = "帮助清理次数已达上限",
                    rpc = "AntOcean.completeHelpFriendCleanTask",
                    detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
                )
            }
            skipUsers.put(friendUserId, "clean")
        }

        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.BUSINESS_LIMIT,
            message = "任务入口未找到符合清理规则的好友，等待手动完成",
            rpc = "AntOcean.completeHelpFriendCleanTask",
            detail = oceanTaskActionDetail(item, "helpFriendCleanTask"),
        )
    }

    private fun finishOceanTask(item: TaskFlowItem): TaskFlowActionResult {
        val response = AntOceanRpcCall.finishTask(item.sceneCode, item.type)
        val result =
            JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "finishTask返回空或无法解析",
                rpc = "AntOceanRpcCall.finishTask",
                raw = response,
                detail = oceanTaskActionDetail(item, "finishTask"),
                stopCurrentRound = true,
            )
        if (isOceanTaskRpcSuccess(result)) {
            Log.ocean("海洋任务🌊完成[${item.title}]")
            return TaskFlowActionResult.success()
        }
        return oceanTaskActionFailureResult(
            item = item,
            response = result,
            rpc = "AntOceanRpcCall.finishTask",
            detail = oceanTaskActionDetail(item, "finishTask"),
        )
    }

    private fun oceanTaskActionFailureResult(
        item: TaskFlowItem,
        response: JSONObject,
        rpc: String,
        detail: String,
    ): TaskFlowActionResult {
        val failureType = classifyOceanTaskFailure(response)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = extractOceanTaskFailureCode(response),
            message = extractOceanTaskFailureMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = detail,
        )
    }

    private fun oceanTaskActionDetail(
        item: TaskFlowItem,
        action: String,
        extra: String = "",
    ): String {
        val rightsTimes = item.raw?.opt("rightsTimes")
        val rightsTimesLimit = item.raw?.opt("rightsTimesLimit")
        val alreadyReceiveAwardCount = item.raw?.opt("alreadyReceiveAwardCount")
        val iepTaskTracer = item.raw?.optString("iepTaskTracer").orEmpty()
        return buildString {
            append("taskType=")
            append(item.type)
            append(" sceneCode=")
            append(item.sceneCode)
            append(" action=")
            append(action)
            if (rightsTimes != null && rightsTimes != JSONObject.NULL) {
                append(" rightsTimes=")
                append(rightsTimes)
            }
            if (rightsTimesLimit != null && rightsTimesLimit != JSONObject.NULL) {
                append(" rightsTimesLimit=")
                append(rightsTimesLimit)
            }
            if (alreadyReceiveAwardCount != null && alreadyReceiveAwardCount != JSONObject.NULL) {
                append(" alreadyReceiveAwardCount=")
                append(alreadyReceiveAwardCount)
            }
            if (iepTaskTracer.isNotBlank()) {
                append(" iepTaskTracer=")
                append(iepTaskTracer)
            }
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
            is Number -> {
                if (resultCode.toInt() == 100 || resultCode.toInt() == 200) return true
            }

            is String -> {
                if (
                    resultCode.equals("SUCCESS", ignoreCase = true) ||
                    resultCode == "100" ||
                    resultCode == "200"
                ) {
                    return true
                }
            }
        }
        return response.optString("memo").equals("SUCCESS", ignoreCase = true)
    }

    private fun classifyOceanTaskFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractOceanTaskFailureCode(response)
        val message = extractOceanTaskFailureMessage(response)
        return when {
            code in
                setOf(
                    "400000030",
                    "400000012",
                    "RECEIVE_REWARD_REPEATED",
                    "TASK_ALREADY_FINISHED",
                    "TASK_HAS_FINISHED",
                    "REPEAT_FINISH",
                    "REPEAT_REWARD",
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
                    "无状态转换处理",
                ) -> {
                TaskRpcFailureType.TERMINAL_DONE
            }

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
                    "风险",
                ) -> {
                TaskRpcFailureType.BUSINESS_LIMIT
            }

            code == "400000040" ||
                containsAnyOcean(message, "不支持rpc调用", "不支持RPC完成") -> {
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
            }

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAnyOcean(message, "参数错误", "任务ID非法", "模板不存在") -> {
                TaskRpcFailureType.NON_RETRYABLE_INVALID
            }

            code in
                setOf(
                    "3000",
                    "400000004",
                    "REMOTE_INVOKE_EXCEPTION",
                    "OP_REPEAT_CHECK",
                    "SYSTEM_BUSY",
                    "NETWORK_ERROR",
                    "1009",
                    "I07",
                    "USER_FREQUENTLY_LOCK",
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
                    "任务未完成无法领取",
                ) ||
                isOceanFailureMarkedRetryable(response) -> {
                TaskRpcFailureType.RETRYABLE_RPC
            }

            else -> {
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }
    }

    private fun isFriendPieceAlreadyGiven(response: JSONObject): Boolean {
        val code = extractOceanTaskFailureCode(response)
        val message = extractOceanTaskFailureMessage(response)
        return code == "PIECE_HAVE_GAVE" ||
            containsAnyOcean(message, "碎片已经成功送出啦")
    }

    private fun isFriendPieceReceiveLimit(response: JSONObject): Boolean =
        extractOceanTaskFailureCode(response) == "RECEIVE_PIECE_LIMIT"

    private fun extractOceanTaskFailureCode(response: JSONObject): String =
        response
            .optString("code")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("resultCode") }

    private fun extractOceanTaskFailureMessage(response: JSONObject): String =
        response
            .optString("desc")
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("memo") }
            .ifBlank { response.optString("message") }
            .ifBlank { response.toString() }

    private fun isOceanFailureMarkedRetryable(response: JSONObject): Boolean {
        response.optJSONObject("resData")?.let {
            return isOceanFailureMarkedRetryable(it)
        }
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun containsAnyOcean(
        text: String,
        vararg keywords: String,
    ): Boolean = keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }

    private fun logOceanTaskOnce(message: String) {
        if (loggedMessages.add(message)) {
            Log.ocean(message)
        }
    }

    private fun isRewardReadyStatus(taskStatus: String): Boolean =
        taskStatus == TaskStatus.FINISHED.name ||
            taskStatus == "COMPLETE" ||
            taskStatus == "WAIT_RECEIVE" ||
            taskStatus == "TO_RECEIVE"

    private fun isRewardReceivedStatus(taskStatus: String): Boolean =
        taskStatus == TaskStatus.RECEIVED.name ||
            taskStatus == "HAS_RECEIVED"

    private fun isOceanTaskRewardNotReady(jo: JSONObject): Boolean {
        val code =
            jo.optString("code").ifBlank {
                jo.optString("errorCode").ifBlank { jo.optString("resultCode") }
            }
        val desc =
            jo.optString("desc").ifBlank {
                jo.optString("errorMsg").ifBlank { jo.optString("resultDesc") }
            }
        return code == "400000004" ||
            containsAnyOcean(desc, "任务未完成,无法领取", "任务未完成，无法领取", "任务未完成无法领取")
    }

    private fun parseOceanTaskProgressInt(
        task: JSONObject,
        key: String,
    ): Int? =
        when (val value = task.opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            null -> null
            else -> value.toString().trim().toIntOrNull()
        }

    private fun parseJSONObject(value: Any?): JSONObject? =
        when (value) {
            is JSONObject -> value
            is String -> JsonUtil.parseJSONObjectOrNull(value)
            else -> null
        }

    private fun logOceanPdlAwardFailure(
        taskType: String,
        taskTitle: String,
        response: JSONObject,
    ) {
        val failureType = classifyOceanTaskFailure(response)
        val code = extractOceanTaskFailureCode(response).ifBlank { "UNKNOWN" }
        val message = extractOceanTaskFailureMessage(response).ifBlank { "UNKNOWN" }
        val detail =
            "module=神奇海洋 taskType=$taskType taskName=$taskTitle " +
                "action=PDLreceiveTaskAward code=$code msg=$message raw=$response"
        val logMessage =
            "潘多拉海洋任务[$taskTitle] classification=$failureType " +
                "decision=${oceanPdlDecisionText(failureType)} $detail"
        if (failureType == TaskRpcFailureType.TERMINAL_DONE) {
            Log.ocean(logMessage)
        } else {
            Log.error(TAG, logMessage)
        }
    }

    private fun oceanPdlDecisionText(failureType: TaskRpcFailureType): String =
        when (failureType) {
            TaskRpcFailureType.TERMINAL_DONE -> "MARK_HANDLED"

            TaskRpcFailureType.BUSINESS_LIMIT -> "STOP_TODAY_OR_CURRENT_CHAIN"

            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
            TaskRpcFailureType.NON_RETRYABLE_INVALID,
            -> "LOG_ONLY"

            TaskRpcFailureType.RETRYABLE_RPC -> "RETRY_LATER"

            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> "LOG_ONLY"
        }

    private fun queryNotice(scene: OceanNoticeScene) {
        try {
            val jo =
                JsonUtil.parseJSONObjectOrNull(
                    AntOceanRpcCall.queryNotice(buildOceanNoticeReqList(scene)),
                ) ?: return
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
                            logOceanActivityEntranceDetected()
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

    private fun queryPopupWin() {
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
                    logOceanActivityEntranceDetected()
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
                        if (isOceanTaskRpcSuccess(receiveTaskJson)) {
                            Log.ocean("海洋奖励🌊[领取:$taskTitle]获得潘多拉能量x$awardCount")
                        } else {
                            logOceanPdlAwardFailure(taskType, taskTitle, receiveTaskJson)
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
        val protectionType = userprotectType?.value ?: return
        val configuredTargets = protectOceanList?.value ?: return
        val selectedCodes =
            configuredTargets
                .mapNotNull { (code, count) ->
                    code
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.takeIf { (count ?: 0) > 0 }
                }.toSet()
        if (selectedCodes.isEmpty()) {
            Log.ocean("海洋生态保护列表未配置有效目标，跳过申请")
            return
        }

        try {
            val response = AntOceanRpcCall.queryCultivationList()
            val responseJson =
                JsonUtil.parseJSONObjectOrNull(response) ?: run {
                    Log.error(TAG, "海洋生态保护查询失败: 响应不可解析")
                    return
                }
            if (!ResChecker.checkRes(TAG, responseJson)) {
                Log.error(
                    TAG,
                    "海洋生态保护查询失败[resultDesc=${responseJson.optString("resultDesc")}, raw=$responseJson]",
                )
                return
            }
            val cultivationList =
                responseJson.optJSONArray("cultivationItemVOList") ?: run {
                    Log.error(TAG, "海洋生态保护查询失败: 缺少cultivationItemVOList")
                    return
                }

            val skippedCodes = selectedCodes.toMutableSet()
            for (i in 0 until cultivationList.length()) {
                val cultivation = parseOceanCultivation(cultivationList.optJSONObject(i)) ?: continue
                if (cultivation.cultivationCode !in selectedCodes) {
                    continue
                }
                if (!cultivation.matchesProtectionType(protectionType) ||
                    cultivation.applyAction != ApplyAction.AVAILABLE.name
                ) {
                    continue
                }
                val targetCount = configuredTargets[cultivation.cultivationCode] ?: continue
                if (targetCount <= 0) {
                    continue
                }
                skippedCodes.remove(cultivation.cultivationCode)
                oceanExchangeTree(
                    cultivation.cultivationCode,
                    cultivation.projectCode,
                    cultivation.cultivationName,
                    targetCount,
                )
            }
            if (skippedCodes.isNotEmpty()) {
                Log.ocean(
                    "海洋生态保护跳过配置项[${skippedCodes.joinToString()}]：不在当前范围、不可申请或未出现在当前候选中",
                )
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "protectOcean err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun oceanExchangeTree(
        cultivationCode: String,
        projectCode: String,
        itemName: String,
        count: Int,
    ) {
        try {
            var appliedTimes = queryCultivationDetail(cultivationCode, projectCode, count)
            if (appliedTimes < 0) return

            while (appliedTimes in 1..count) {
                val response = AntOceanRpcCall.oceanExchangeTree(cultivationCode, projectCode)
                val responseJson = JsonUtil.parseJSONObjectOrNull(response)
                if (responseJson == null) {
                    Log.error(
                        TAG,
                        "海洋生态申请失败[cultivationCode=$cultivationCode, projectCode=$projectCode, itemName=$itemName]: 响应不可解析",
                    )
                    break
                }
                if (!ResChecker.checkRes(TAG, responseJson)) {
                    Log.error(
                        TAG,
                        "海洋生态申请失败[cultivationCode=$cultivationCode, projectCode=$projectCode, itemName=$itemName, resultDesc=${responseJson.optString(
                            "resultDesc",
                        )}, raw=$responseJson]",
                    )
                    break
                }
                val awards =
                    buildString {
                        responseJson.optJSONArray("rewardItemVOs")?.let { awardInfos ->
                            for (i in 0 until awardInfos.length()) {
                                val award = awardInfos.optJSONObject(i)
                                if (award != null) {
                                    val name = award.optString("name")
                                    if (name.isNotEmpty()) {
                                        if (isNotEmpty()) append(";")
                                        append(name).append("*").append(award.optInt("num", 0))
                                    }
                                }
                            }
                        }
                    }
                val resultMessage =
                    if (awards.isEmpty()) {
                        "保护海洋生态🏖️[$itemName]#第${appliedTimes}次申请成功，奖励明细未返回"
                    } else {
                        "保护海洋生态🏖️[$itemName]#第${appliedTimes}次-获得奖励$awards"
                    }
                Log.ocean(resultMessage)

                val nextAppliedTimes = queryCultivationDetail(cultivationCode, projectCode, count)
                if (nextAppliedTimes < 0) {
                    break
                }
                if (nextAppliedTimes <= appliedTimes) {
                    Log.error(
                        TAG,
                        "海洋生态申请后未确认进展[cultivationCode=$cultivationCode, projectCode=$projectCode, itemName=$itemName, expectedNext=$appliedTimes, actualNext=$nextAppliedTimes]",
                    )
                    break
                }
                appliedTimes = nextAppliedTimes
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "海洋生态申请错误:", t)
        }
    }

    private suspend fun queryCultivationDetail(
        cultivationCode: String,
        projectCode: String,
        count: Int,
    ): Int {
        try {
            val response = AntOceanRpcCall.queryCultivationDetail(cultivationCode, projectCode)
            val responseJson =
                JsonUtil.parseJSONObjectOrNull(response) ?: run {
                    Log.error(
                        TAG,
                        "海洋生态详情查询失败[cultivationCode=$cultivationCode, projectCode=$projectCode]: 响应不可解析",
                    )
                    return -1
                }
            if (!ResChecker.checkRes(TAG, responseJson)) {
                Log.error(
                    TAG,
                    "海洋生态详情查询失败[cultivationCode=$cultivationCode, projectCode=$projectCode, resultDesc=${responseJson.optString(
                        "resultDesc",
                    )}, raw=$responseJson]",
                )
                return -1
            }
            val userInfo =
                responseJson.optJSONObject("userInfoVO") ?: run {
                    Log.error(TAG, "海洋生态详情查询失败[cultivationCode=$cultivationCode]: 缺少userInfoVO")
                    return -1
                }
            val cultivationDetail =
                responseJson.optJSONObject("cultivationDetailVO") ?: run {
                    Log.error(TAG, "海洋生态详情查询失败[cultivationCode=$cultivationCode]: 缺少cultivationDetailVO")
                    return -1
                }
            val itemName = cultivationDetail.optString("cultivationName", cultivationCode)
            val currentEnergy = userInfo.optInt("currentEnergy", 0)
            val energy = cultivationDetail.optInt("energy", 0)
            val certNum = cultivationDetail.optInt("certNum", 0)
            when {
                cultivationDetail.optString("applyAction") != ApplyAction.AVAILABLE.name -> {
                    Log.ocean("保护海洋🏖️[$itemName]#当前不可申请")
                    return -1
                }

                currentEnergy < energy -> {
                    Log.ocean("保护海洋🏖️[$itemName]#能量不足停止申请")
                    return -1
                }

                certNum >= count -> {
                    return -1
                }

                else -> {
                    return certNum + 1
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryCultivationDetail err:")
            Log.printStackTrace(TAG, t)
            return -1
        }
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
        extraCollectOnly: Boolean,
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

    private fun buildFishPropTarget(
        fishVOs: JSONArray?,
        maxPieceCount: Int,
    ): FishPropTarget? {
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
                    pieceIds = pieceIds,
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
                val target =
                    querySeaAreaDetailData()?.let { detailJo ->
                        findCurrentChapterPropTarget(detailJo, maxPieceCount = 1, extraCollectOnly = false)
                    }
                if (target != null) {
                    val replenishResult =
                        ExchangeReplenisher.replenish(
                            need = ExchangeEffectNeed.OCEAN_UNIVERSAL_PIECE,
                            reason = "神奇海洋万能拼图不足",
                            maxCount = 1,
                        ) {
                            AntOceanRpcCall.usePropByTypeList()
                        }
                    if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                        Log.ocean("神奇海洋🏖️[万能拼图]已触发缺货补兑，重新查询道具列表")
                        usePropByType(allowReplenish = false)
                        return
                    }
                    val randomPieceResult =
                        ExchangeReplenisher.replenish(
                            need = ExchangeEffectNeed.OCEAN_RANDOM_PIECE,
                            reason = "神奇海洋随机拼图推进",
                            maxCount = 1,
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
                val propName =
                    propInfo.optString("name").ifBlank {
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
                        Log.runtime(
                            TAG,
                            usePropResultObj.optString("resultDesc").ifBlank {
                                usePropResultObj.optString("memo").ifBlank { usePropResultObj.toString() }
                            },
                        )
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
