package io.github.aoguai.sesameag.task.antFarm

import android.net.Uri
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectCatalog
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeFetchPacing
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsSnapshot
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.exchange.ExchangeSafetyRules
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * 小鸡抽抽乐功能类
 */
class ChouChouLe {
    companion object {
        private val TAG = ChouChouLe::class.java.simpleName
        private const val FARM_BLACKLIST_MODULE = "蚂蚁庄园"
        private const val DATA_FILE_NAME = "farmIPChouChouLeShop.json"

        /**
         * 供外部（如实体类）加载数据使用
         */
        @JvmStatic
        fun loadData(userId: String?): IpChouChouLeData {
            try {
                val file = Files.getTargetFileofUser(userId, DATA_FILE_NAME)
                if (file != null && file.exists()) {
                    val body = Files.readFromFile(file)
                    if (body.isNotEmpty()) {
                        return JsonUtil.parseObject(body, IpChouChouLeData::class.java)
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "加载IP抽抽乐数据失败", e)
            }
            return IpChouChouLeData()
        }

        @JvmStatic
        fun saveData(
            userId: String?,
            data: IpChouChouLeData,
        ) {
            try {
                val json = JsonUtil.formatJson(data)
                if (json.isEmpty()) return
                val file = Files.getTargetFileofUser(userId, DATA_FILE_NAME)
                if (file != null) {
                    Files.write2File(json, file)
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "保存IP抽抽乐数据失败", e)
            }
        }
    }

    /**
     * 合并后的数据结构
     */
    class IpChouChouLeData {
        var activityId: String = ""
        var shopItems: MutableMap<String, String> = mutableMapOf() // skuId -> "名称|限购|价格"
        var exchangedCounts: MutableMap<String, Int> = mutableMapOf() // skuId -> 累计兑换次数
    }

    /**
     * 任务信息结构体
     */
    private data class TaskInfo(
        var taskStatus: String = "",
        var title: String = "",
        var taskId: String = "",
        var innerAction: String = "",
        var rightsTimes: Int = 0,
        var alreadyReceiveStageAwardCount: Int? = null,
        var rightsTimesLimit: Int = 0,
        var awardType: String = "",
        var awardCount: Int = 0,
        var targetUrl: String = "",
        var desc: String = "",
        var taskMode: String = "",
        var countDownSeconds: Int = 0,
        var categorizationSecondLevel: String = "",
        var categorizationThirdLevel: String = "",
    ) {
        fun getRemainingTimes(): Int = max(0, rightsTimesLimit - rightsTimes)
    }

    private data class IpDrawActivity(
        val activityId: String,
        val endTime: Long,
    )

    private data class IpDrawMallSnapshot(
        val activityId: String,
        val endTime: Long,
        val balanceCent: Int,
        val items: List<IpDrawMallItem>,
        val rawItems: JSONArray,
    )

    private data class IpDrawMallItem(
        val spuId: String,
        val skuId: String,
        val name: String,
        val priceCent: Int,
        val limitCount: Int,
        val itemStatus: String,
        val itemStatusList: JSONArray?,
        val skuStatus: String,
        val skuStatusList: JSONArray?,
        val skuExtendInfo: String,
        val userTotalLeftAmount: Int,
        val isIp: Boolean,
        val isNewEgg: Boolean,
        val safety: ExchangeSafety,
        val safetyReason: String,
    )

    private data class IpDrawMallItemDetail(
        val item: IpDrawMallItem,
        val balanceCent: Int?,
    )

    fun run(antFarm: AntFarm) {
        if (antFarm.enableChouchoule?.value != true || ApplicationHookConstants.isOffline()) return
        if (!antFarm.farmWorkAttempted.add("draw")) return
        executeAndSync(antFarm)
    }

    private fun executeAndSync(antFarm: AntFarm) {
        if (this.chouchoule(useDailyState = true)) {
            Status.setFlagToday(StatusFlags.FLAG_FARM_CHOUCHOULE_FINISHED)
            antFarm.syncAnimalStatus(antFarm.ownerFarmId)
            Log.farm("今日抽抽乐已完成")
        } else {
            antFarm.syncAnimalStatus(antFarm.ownerFarmId)
            antFarm.deferFarmWork("draw", farmTimeToday(23, 30))
            Log.farm("抽抽乐尚有未完成项，保留原因并等待下一次自然调度")
        }
    }

    /**
     * 抽抽乐主入口
     * 返回值判断是否真的完成任务，是否全部执行完毕且无剩余（任务已做、奖励已领、抽奖已完）
     */
    fun chouchoule(useDailyState: Boolean = false): Boolean {
        var allFinished = true
        try {
            val response = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                return false
            }

            val drawMachineInfo = jo.optJSONObject("drawMachineInfo") ?: return false
            val hasIpDraw = drawMachineInfo.has("ipDrawMachineActivityId")
            val hasDailyDraw = drawMachineInfo.has("dailyDrawMachineActivityId")
            if (!hasIpDraw && !hasDailyDraw) {
                Log.error(TAG, "抽抽乐🎁[获取抽抽乐活动信息失败]")
                return false
            }

            for ((drawType, present, idKey) in listOf(
                Triple("ipDraw", hasIpDraw, "ipDrawMachineActivityId"),
                Triple("dailyDraw", hasDailyDraw, "dailyDrawMachineActivityId"),
            )) {
                if (!present || ApplicationHookConstants.isOffline()) continue
                val activityId = drawMachineInfo.optString(idKey)
                if (activityId.isBlank()) {
                    allFinished = false
                    continue
                }
                val flag = StatusFlags.FLAG_FARM_CHOUCHOULE_FINISHED + "::$drawType::$activityId::" +
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                if (useDailyState && Status.hasFlagToday(flag)) {
                    if (drawType == "ipDraw") exchangeIpRewards()
                    continue
                }
                val finished = doChouchoule(drawType)
                if (drawType == "ipDraw" && AntFarm.instance?.farmWorkAttempted?.contains("exchange") != true) {
                    exchangeIpRewards()
                }
                if (finished && useDailyState) Status.setFlagToday(flag)
                allFinished = allFinished && finished
            }

            return allFinished
        } catch (t: Throwable) {
            Log.printStackTrace("chouchoule err:", t)
            return false
        }
    }

    /**
     * 执行抽抽乐
     *
     * @param drawType "dailyDraw" 或 "ipDraw"
     * 返回是否该类型已全部完成
     */
    private fun doChouchoule(drawType: String): Boolean {
        var tasksCompleted = false
        try {
            runCatching {
                AntFarmRpcCall.refinedOperation("DRAW_MACHINE", "antfarm_villa", "RPC")
            }
            var currentTasks = emptyMap<String, TaskInfo>()
            tasksCompleted = TaskFlowEngine(object : TaskFlowAdapter {
                override val moduleName = FARM_BLACKLIST_MODULE
                override val flowName = "抽抽乐[$drawType]"
                override fun query() = JSONObject(AntFarmRpcCall.chouchouleListFarmTask(drawType))
                override fun isQuerySuccess(response: JSONObject) =
                    ResChecker.checkRes(TAG, response) && response.optJSONArray("farmTaskList") != null

                override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                    val tasks = parseTasks(response.getJSONArray("farmTaskList"))
                    currentTasks = tasks.associateBy { it.taskId }
                    return tasks.map { task ->
                        TaskFlowItem(
                            id = task.taskId, title = task.title, status = task.taskStatus,
                            type = task.innerAction, current = task.rightsTimes, limit = task.rightsTimesLimit,
                            progress = "${task.rightsTimes}:${task.alreadyReceiveStageAwardCount}:${task.awardCount}",
                        )
                    }
                }

                override fun mapPhase(item: TaskFlowItem) = when (item.status) {
                    "FINISHED" -> TaskFlowPhase.REWARD_READY
                    "TODO" -> if ((item.limit ?: 0) <= 0 || (item.current ?: 0) < (item.limit ?: 0))
                        TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.TERMINAL
                    "RECEIVED" -> TaskFlowPhase.TERMINAL
                    else -> TaskFlowPhase.UNKNOWN
                }

                override fun isBlacklisted(item: TaskFlowItem) =
                    currentTasks[item.id]?.let(::isBlacklistedTask) == true

                override fun complete(item: TaskFlowItem): TaskFlowActionResult =
                    doChouTask(drawType, currentTasks.getValue(item.id))

                override fun receive(item: TaskFlowItem): TaskFlowActionResult =
                    receiveTaskAward(drawType, currentTasks.getValue(item.id))

                override fun onQueryFailed(response: JSONObject) = Log.error(TAG, "$flowName 查询失败:$response")
                override fun logInfo(message: String) = Log.farm(message)
                override fun logError(message: String) = Log.error(TAG, message)
            }, roundSleepMs = 300L).run().completed
        } catch (t: Throwable) {
            val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "日常抽抽乐"
            Log.printStackTrace("doChouchoule err:", t)
            Log.error(TAG, "${taskName}任务流处理异常，保留任务重试，继续独立抽奖余额处理")
        }

        if (ApplicationHookConstants.isOffline()) {
            val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "日常抽抽乐"
            Log.farm("${taskName}任务流后检测到离线，跳过抽奖并保留后续重试")
            return false
        }

        // 执行抽奖
        val drawSuccess =
            if ("ipDraw" == drawType) {
                handleIpDraw()
            } else {
                handleDailyDraw()
            }

        if (!drawSuccess) return false

        // 最后校验是否真的全部完成
        return tasksCompleted && verifyFinished(drawType)
    }

    /*
     校验是否还有未完成的任务或抽奖
     */
    private fun verifyFinished(drawType: String): Boolean {
        return try {
            val tasks = queryChouchouleTasks(drawType) ?: return false
            for (task in tasks) {
                when (task.taskStatus) {
                    TaskStatus.RECEIVED.name -> Unit
                    TaskStatus.FINISHED.name -> return false
                    TaskStatus.TODO.name -> {
                        if (task.getRemainingTimes() > 0 &&
                            !isBlacklistedTask(task)
                        ) {
                            return false
                        }
                    }

                    else -> {
                        return false
                    }
                }
            }

            // 校验抽奖次数
            val drawJo =
                if ("ipDraw" == drawType) {
                    JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
                } else {
                    JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("dailyDrawMachine", "ipDrawMachine"))
                }
            if (!ResChecker.checkRes(TAG, drawJo)) return false
            val drawTimes = extractDrawTimes(drawJo)
            if (drawTimes != 0) return false

            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun queryChouchouleTasks(drawType: String): List<TaskInfo>? {
        val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "抽抽乐"
        return try {
            val jo = JSONObject(AntFarmRpcCall.chouchouleListFarmTask(drawType))
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "${taskName}任务列表获取失败")
                return null
            }
            val farmTaskList = jo.optJSONArray("farmTaskList")
            if (farmTaskList == null) {
                Log.error(TAG, "${taskName}任务列表缺少 farmTaskList")
                return null
            }
            parseTasks(farmTaskList)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "${taskName}任务列表解析失败:", t)
            null
        }
    }

    private fun isBlacklistedTask(task: TaskInfo): Boolean =
        listOf(
            task.innerAction
                .takeIf { it.isNotBlank() }
                ?.let { "innerAction:$it" }
                .orEmpty(),
            task.innerAction,
            task.taskId,
        ).filter { it.isNotBlank() }
            .any { TaskBlacklist.isTaskInBlacklist(FARM_BLACKLIST_MODULE, it) }

    private fun extractDrawTimes(jo: JSONObject): Int = jo.optInt("drawTimes", -1)

    /**
     * 解析任务列表
     */
    @Throws(Exception::class)
    private fun parseTasks(array: JSONArray): List<TaskInfo> {
        val list = ArrayList<TaskInfo>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val reportedRightsTimes =
                listOf(
                    item.optInt("rightsTimes", -1),
                    item.optInt("receivedTimes", -1),
                ).firstOrNull { it >= 0 }
            val info =
                TaskInfo(
                    taskStatus = item.getString("taskStatus"),
                    title = item.optString("title"),
                    taskId = item.optString("bizKey").ifBlank { item.optString("taskId") },
                    innerAction = item.optString("innerAction"),
                    rightsTimes = reportedRightsTimes ?: 0,
                    alreadyReceiveStageAwardCount =
                        item.optInt("alreadyReceiveStageAwardCount")
                            .takeIf { item.has("alreadyReceiveStageAwardCount") && !item.isNull("alreadyReceiveStageAwardCount") },
                    rightsTimesLimit =
                        listOf(
                            item.optInt("rightsTimesLimit", -1),
                            item.optInt("drawRightsTimes", -1),
                            item.optInt("canReceiveAwardCount", -1),
                        ).firstOrNull { it >= 0 } ?: 0,
                    awardType = item.optString("awardType").ifBlank { item.optString("taskAwardType") },
                    awardCount =
                        listOf(
                            item.optInt("awardCount", -1),
                            item.optInt("canReceiveAwardCount", -1),
                        ).firstOrNull { it >= 0 } ?: 0,
                    targetUrl = item.optString("targetUrl").ifBlank { item.optString("finishedUrl") },
                    desc = item.optString("desc"),
                    taskMode = item.optString("taskMode"),
                    countDownSeconds = item.optInt("countDownSeconds", 0),
                    categorizationSecondLevel = item.optString("categorizationSecondLevel"),
                    categorizationThirdLevel = item.optString("categorizationThirdLevel"),
                )
            list.add(info)
        }
        return list
    }

    private fun shouldUseDirectFinishTaskFlow(task: TaskInfo): Boolean {
        return task.taskMode.equals("COUNT_DOWN", ignoreCase = true) || task.countDownSeconds > 0
    }

    private fun extractRenderConfigKey(task: TaskInfo): String? =
        runCatching {
            Uri
                .parse(task.targetUrl)
                .getQueryParameter("renderConfigKey")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * 执行任务
     */
    private fun doChouTask(
        drawType: String,
        task: TaskInfo,
    ): TaskFlowActionResult {
        if (task.taskId.isBlank()) return TaskFlowActionResult.failure(
            TaskRpcFailureType.NON_RETRYABLE_INVALID, message = "抽抽乐任务缺少 taskId",
            detail = "taskName=${task.title}", continueCurrentRoundOnFailure = true,
        )
        val farm = AntFarm.instance ?: return TaskFlowActionResult.failure(
            TaskRpcFailureType.RETRYABLE_RPC, message = "庄园实例未就绪", stopCurrentRound = true,
        )
        val renderConfigKey = extractRenderConfigKey(task)
        if (renderConfigKey != null || shouldUseDirectFinishTaskFlow(task)) {
            return handleDirectFinishTask(drawType, task, renderConfigKey)
        }
        val response = JSONObject(AntFarmRpcCall.chouchouleDoFarmTask(drawType, task.taskId))
        if (!ResChecker.checkRes(TAG, response)) {
            return farm.buildFarmTaskFailureResult(response, task.taskId, task.title, "完成", "doFarmTask")
        }
        GlobalThreadPools.sleepCompat(1000L)
        return TaskFlowActionResult.success(progressChanged = false)
    }

    private fun finishTaskDirectly(
        drawType: String,
        task: TaskInfo,
        taskSceneCode: String,
    ): TaskFlowActionResult {
        val farm = AntFarm.instance ?: return TaskFlowActionResult.failure(
            TaskRpcFailureType.RETRYABLE_RPC, message = "庄园实例未就绪", stopCurrentRound = true,
        )
        val outBizNo =
            buildString {
                append(task.taskId)
                append("_")
                append(System.currentTimeMillis())
                append("_")
                append(0)
                append("_")
                append(Integer.toHexString((Math.random() * 0xFFFFFF).toInt()))
            }
        val response = AntFarmRpcCall.finishTask(task.taskId, taskSceneCode, outBizNo)
        val jo = JSONObject(response)
        if (ResChecker.checkRes(TAG, jo)) return TaskFlowActionResult.success(progressChanged = false)
        return farm.buildFarmTaskFailureResult(jo, task.taskId, task.title, "完成[$drawType]", "finishTask")
    }

    /**
     * 处理直连任务
     */
    private fun handleDirectFinishTask(
        drawType: String,
        task: TaskInfo,
        renderConfigKey: String?,
    ): TaskFlowActionResult {
        if (renderConfigKey != null && !waitForRenderTaskDuration(drawType, task, renderConfigKey)) {
            return TaskFlowActionResult.failure(
                TaskRpcFailureType.RETRYABLE_RPC, message = "广告时长查询未确认，原响应已记录",
                rpc = "adTaskApplayerQuery", continueCurrentRoundOnFailure = true,
            )
        }
        val taskSceneCode = if (drawType == "ipDraw") "ANTFARM_IP_DRAW_TASK" else "ANTFARM_DAILY_DRAW_TASK"
        return finishTaskDirectly(drawType, task, taskSceneCode)
    }

    private fun waitForRenderTaskDuration(
        drawType: String,
        task: TaskInfo,
        renderConfigKey: String,
    ): Boolean {
        val response = AntFarmRpcCall.adTaskApplayerQuery(renderConfigKey)
        val result = JSONObject(response)
        val durationSeconds = result.optJSONObject("resultData")?.optDouble("duration", -1.0) ?: -1.0
        if (!result.optBoolean("success") || result.optString("errCode") != "0" || durationSeconds <= 0.0) {
            val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "抽抽乐"
            Log.error(TAG, "${taskName}任务[${task.title}]广告时长查询未确认：$result")
            return false
        }
        GlobalThreadPools.sleepCompat((durationSeconds * 1000.0).toLong())
        return true
    }

    /**
     * 领取任务奖励
     */
    private fun receiveTaskAward(
        drawType: String,
        task: TaskInfo,
    ): TaskFlowActionResult {
        if (task.taskId.isBlank()) return TaskFlowActionResult.failure(
            TaskRpcFailureType.NON_RETRYABLE_INVALID, message = "抽抽乐奖励缺少 taskId",
            detail = "taskName=${task.title}", continueCurrentRoundOnFailure = true,
        )
        val farm = AntFarm.instance ?: return TaskFlowActionResult.failure(
            TaskRpcFailureType.RETRYABLE_RPC, message = "庄园实例未就绪", stopCurrentRound = true,
        )
        if (task.awardType == "ALLPURPOSE" && !farm.prepareFarmAwardCapacity(task.awardCount)) {
            return TaskFlowActionResult.defer(DeferredReason.CAPACITY_LIMIT, message = "饲料容量不足，保留后续领取")
        }
        val response = JSONObject(AntFarmRpcCall.chouchouleReceiveFarmTaskAward(drawType, task.taskId, task.awardType))
        if (ResChecker.checkRes(TAG, response)) return TaskFlowActionResult.success()
        return farm.buildFarmTaskFailureResult(response, task.taskId, task.title, "领奖", "receiveFarmTaskAward")
            .copy(refreshAfterAction = true)
    }

    /**
     * 执行IP抽抽乐抽奖
     */
    private fun handleIpDraw(): Boolean {
        try {
            val jo =
                JSONObject(
                    AntFarmRpcCall.queryDrawMachineActivity_New(
                        "ipDrawMachine",
                        "dailyDrawMachine",
                    ),
                )
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("IP抽抽乐活动查询失败，本轮停止")
                return false
            }

            val activity = jo.optJSONObject("drawMachineActivity") ?: return false
            val activityId = activity.optString("activityId")
            val endTime = activity.optLong("endTime", 0)
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.farm("该[${activity.optString("activityId")}]抽奖活动已结束")
                return true
            }

            var remainingTimes = extractDrawTimes(jo)
            if (remainingTimes < 0) return false
            var allSuccess = true
            if (remainingTimes <= 0) {
                Log.farm("IP抽抽乐当前无可用次数，跳过抽奖")
            } else {
                Log.farm("IP抽抽乐剩余次数: $remainingTimes")

                while (remainingTimes > 0) {
                    val batchCount = remainingTimes.coerceAtMost(10)
                    Log.farm("执行 IP 抽抽乐 $batchCount 连抽...")

                    val response = AntFarmRpcCall.drawMachineIP(batchCount)
                    val batchSuccess = drawPrize("IP抽抽乐", response)
                    if (!batchSuccess) {
                        Log.farm("IP抽抽乐提交结果未确认，本轮停止抽奖，避免重复扣减")
                        return false
                    }
                    allSuccess = allSuccess and batchSuccess

                    val refreshed = JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
                    if (!ResChecker.checkRes(TAG, refreshed)) return false
                    val afterTimes = extractDrawTimes(refreshed)
                    if (afterTimes < 0 || afterTimes >= remainingTimes) {
                        Log.farm("IP抽抽乐响应成功但余额未确认减少，本轮停止抽奖")
                        return false
                    }
                    remainingTimes = afterTimes
                    if (remainingTimes > 0) {
                        GlobalThreadPools.sleepCompat(1500L)
                    }
                }
            }
            if (activityId.isNotEmpty() && AntFarm.instance?.autoExchange?.value == true) {
                batchExchangeRewards(activityId, endTime)
            }
            return allSuccess
        } catch (t: Throwable) {
            Log.printStackTrace("handleIpDraw err:", t)
            return false
        }
    }

    /**
     * 执行普通抽抽乐抽奖
     */
    private fun handleDailyDraw(): Boolean {
        try {
            val jo =
                JSONObject(
                    AntFarmRpcCall.queryDrawMachineActivity_New(
                        "dailyDrawMachine",
                        "ipDrawMachine",
                    ),
                )
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("日常抽抽乐活动查询失败，本轮停止")
                return false
            }

            val activity = jo.optJSONObject("drawMachineActivity") ?: return false
            val endTime = activity.optLong("endTime", 0)
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.farm("该[${activity.optString("activityId")}]抽奖活动已结束")
                return true
            }

            var remainingTimes = extractDrawTimes(jo)
            if (remainingTimes < 0) return false
            if (remainingTimes <= 0) {
                Log.farm("日常抽抽乐当前无可用次数，跳过抽奖")
                return true
            }
            var allSuccess = true

            Log.farm("日常抽抽乐剩余次数: $remainingTimes")

            while (remainingTimes > 0) {
                val batchCount = remainingTimes.coerceAtMost(10)
                Log.farm("执行日常抽抽乐 $batchCount 连抽...")

                val response = AntFarmRpcCall.drawMachineDaily(batchCount)
                val batchSuccess = drawPrize("日常抽抽乐", response)
                if (!batchSuccess) {
                    Log.farm("日常抽抽乐提交结果未确认，本轮停止抽奖，避免重复扣减")
                    return false
                }
                allSuccess = allSuccess and batchSuccess

                val refreshed = JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("dailyDrawMachine", "ipDrawMachine"))
                if (!ResChecker.checkRes(TAG, refreshed)) return false
                val afterTimes = extractDrawTimes(refreshed)
                if (afterTimes < 0 || afterTimes >= remainingTimes) {
                    Log.farm("日常抽抽乐响应成功但余额未确认减少，本轮停止抽奖")
                    return false
                }
                remainingTimes = afterTimes
                if (remainingTimes > 0) {
                    GlobalThreadPools.sleepCompat(1500L)
                }
            }
            return allSuccess
        } catch (t: Throwable) {
            Log.printStackTrace("handleDailyDraw err:", t)
            return false
        }
    }

    /**
     * 领取抽抽乐奖品
     *
     * @param prefix   抽奖类型前缀
     * @param response 服务器返回的结果
     * 返回是否领取成功
     */
    private fun drawPrize(
        prefix: String,
        response: String,
    ): Boolean {
        try {
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val prizeList = jo.optJSONArray("drawMachinePrizeList")
                if (prizeList != null && prizeList.length() > 0) {
                    for (i in 0 until prizeList.length()) {
                        val prize = prizeList.getJSONObject(i)
                        val title = prize.optString("title", "未知奖品")
                        Log.farm("$prefix🎁[领取: $title]")
                    }
                } else {
                    Log.farm("$prefix🎁[抽奖成功，但未解析到具体奖品名称]")
                }
                return true
            }
        } catch (t: Throwable) {
            Log.printStackTrace("drawPrize err:", t)
        }
        return false
    }

    private fun exchangeIpRewards() {
        val farm = AntFarm.instance ?: return
        if (farm.enableChouchoule?.value != true || farm.autoExchange?.value != true || ApplicationHookConstants.isOffline()) return
        try {
            val response = JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
            if (!ResChecker.checkRes(TAG, response)) {
                return
            }
            val activity = response.optJSONObject("drawMachineActivity") ?: return
            val activityId = activity.optString("activityId")
            if (activityId.isNotBlank()) batchExchangeRewards(activityId, activity.optLong("endTime", 0))
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "IP兑换待办查询失败", t)
        }
    }

    /**
     * 批量兑换奖励
     */
    fun batchExchangeRewards(
        activityId: String,
        endTime: Long,
    ) {
        try {
            val daysBefore = AntFarm.instance?.exchangeDaysBeforeEndIp?.value ?: 0
            if (AntFarm.instance?.autoExchange?.value != true || ApplicationHookConstants.isOffline()) return
            if (endTime > 0 && endTime <= System.currentTimeMillis()) return
            if (daysBefore > 0 && endTime <= 0) {
                Log.farm("IP兑换结束时间未确认，保留待办")
                return
            }
            if (AntFarm.instance?.farmWorkAttempted?.add("exchange") == false) return
            if (daysBefore > 0 && endTime > 0) {
                val now = System.currentTimeMillis()
                val remainingMs = endTime - now
                val limitMs = daysBefore * 24 * 60 * 60 * 1000L

                if (remainingMs > limitMs) {
                    val remainingDays = remainingMs / (24 * 60 * 60 * 1000L)
                    AntFarm.instance?.deferFarmWork("exchange", endTime - limitMs)
                    Log.farm("[自动兑换]: 未到设定兑换时间：活动尚余 $remainingDays 天结束，设定为提前 $daysBefore 天兑换，跳过。")
                    return
                }
            }

            var snapshot = loadIpDrawMallSnapshot(IpDrawActivity(activityId, endTime))
            val userId = UserMap.currentUid
            val data = syncIpDrawShopSnapshot(snapshot)
            Log.farm("[自动兑换]: 当前持有总碎片: ${snapshot.balanceCent / 100}")

            val customMap = AntFarm.instance?.autoExchangeList?.value
            val isCustom = !customMap.isNullOrEmpty()
            val sequence =
                if (isCustom) {
                    buildCustomExchangeSequence(customMap, snapshot, data)
                } else {
                    buildDefaultExchangeSequence(snapshot)
                }
            if (sequence.isEmpty()) {
                Log.farm("IP抽抽乐商店当前没有需要兑换的奖励")
                return
            }

            for ((item, targetCount) in sequence) {
                var currentItem = snapshot.items.firstOrNull { it.skuId == item.skuId } ?: item
                if (currentItem.spuId.isBlank() || currentItem.skuId.isBlank()) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：缺少 spuId/skuId")
                    continue
                }
                val detail = queryIpDrawMallItemDetail(currentItem)
                if (detail == null) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：缺少详情复核闭环")
                    continue
                }
                currentItem = detail.item
                detail.balanceCent?.let { snapshot = snapshot.copy(balanceCent = it) }
                val detailBlockReason = blockedIpDrawMallItemReason(currentItem)
                if (detailBlockReason.isNotBlank()) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：$detailBlockReason")
                    if (!isCustom && shouldStopDefaultExchangeForNoEnoughPoint(currentItem)) {
                        return
                    }
                    continue
                }
                if (currentItem.safety != ExchangeSafety.AUTO) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：${currentItem.safetyReason.ifBlank { currentItem.safety.name }}")
                    continue
                }
                if (currentItem.userTotalLeftAmount == 0) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：已达兑换限制")
                    continue
                }
                if (currentItem.priceCent > 0 && snapshot.balanceCent < currentItem.priceCent) {
                    if (!isCustom) {
                        Log.farm("[自动兑换]: 最高价值项 [${currentItem.name}] 碎片不足(需 ${currentItem.priceCent / 100})，等攒够再换，终止本次兑换")
                        return
                    }
                    Log.farm("[自动兑换]: [${currentItem.name}] 碎片不足(需 ${currentItem.priceCent / 100}, 当前 ${snapshot.balanceCent / 100})，跳过")
                    continue
                }

                var confirmedCount = 0
                while (confirmedCount < targetCount) {
                    if (currentItem.priceCent > 0 && snapshot.balanceCent < currentItem.priceCent) {
                        Log.farm("[自动兑换]: 剩余碎片[${snapshot.balanceCent / 100}]，不足以兑换[${currentItem.name}]，兑换终止")
                        if (!isCustom) {
                            return
                        }
                        break
                    }

                    val exchangeJo =
                        runCatching {
                            JSONObject(
                                AntFarmRpcCall.exchangeBenefit(
                                    currentItem.spuId,
                                    currentItem.skuId,
                                    activityId,
                                    "ANTFARM_IP_DRAW_MALL",
                                    "antfarm_villa",
                                ),
                            )
                        }.onFailure {
                            Log.printStackTrace(TAG, "IP抽抽乐兑换异常", it)
                        }.getOrNull() ?: break

                    if (!isIpDrawExchangeSuccess(exchangeJo)) {
                        val resultCode = exchangeJo.optString("resultCode").ifBlank { exchangeJo.optString("code") }
                        if (resultCode == "NO_ENOUGH_POINT") {
                            Log.farm("[自动兑换]: [${currentItem.name}] 兑换过程中碎片不足，停止兑换 | ${formatIpDrawRpcResult(exchangeJo)}")
                            if (!isCustom) {
                                return
                            }
                            break
                        }
                        if (isIpDrawTerminalExchangeCode(resultCode)) {
                            Log.farm("[自动兑换]: [${currentItem.name}] 达到服务器上限，尝试兑换下一个物品 | ${formatIpDrawRpcResult(exchangeJo)}")
                            break
                        }
                        Log.farm("[自动兑换]: 跳过 [${currentItem.name}]: ${formatIpDrawRpcResult(exchangeJo)}")
                        break
                    }

                    GlobalThreadPools.sleepCompat(800L)
                    val beforeExchangeItem = currentItem
                    val refreshedSnapshot = runCatching {
                        loadIpDrawMallSnapshot(IpDrawActivity(activityId, endTime), liveRead = true)
                    }.onFailure { Log.printStackTrace(TAG, "IP抽抽乐兑换后回查失败", it) }.getOrNull()
                    if (refreshedSnapshot == null) {
                        Log.farm("[自动兑换]: 已调用兑换但未回查确认 [${currentItem.name}] | ${formatIpDrawRpcResult(exchangeJo)}")
                        break
                    }
                    val refreshedItem = refreshedSnapshot.items.firstOrNull { it.skuId == currentItem.skuId }
                    if (!isIpDrawExchangeConfirmed(snapshot, refreshedSnapshot, beforeExchangeItem, refreshedItem)) {
                        val statusText =
                            refreshedItem
                                ?.let {
                                    formatIpDrawStatus(
                                        it,
                                    ).ifBlank { it.itemStatus.ifBlank { it.skuStatus } }
                                }.orEmpty()
                        Log.farm("[自动兑换]: 已调用兑换但未回查确认 [${currentItem.name}] | 回查状态: ${statusText.ifBlank { "UNKNOWN" }}")
                        snapshot = refreshedSnapshot
                        break
                    }

                    confirmedCount++
                    val currentExchanged = data.exchangedCounts[currentItem.skuId] ?: 0
                    data.exchangedCounts[currentItem.skuId] = currentExchanged + 1
                    syncIpDrawShopSnapshot(refreshedSnapshot, data)
                    saveData(userId, data)
                    snapshot = refreshedSnapshot
                    Log.farm(
                        "IP抽抽乐商店兑换: ${currentItem.name} (本地累计已换 ${data.exchangedCounts[currentItem.skuId]} 次，剩余碎片: ${snapshot.balanceCent / 100})",
                    )
                    if (refreshedItem == null || isIpDrawTerminalExchangeStatus(refreshedItem)) {
                        break
                    }
                    currentItem = refreshedItem
                }
            }
            Log.farm("IP抽抽乐商店任务已处理完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "自动兑换异常", e)
        }
    }

    fun refreshIpChouChouLeExchangeOptionsFromRpc(forceRefresh: Boolean = false): List<ExchangeOptionRow> {
        val activity = queryActiveIpDrawActivity() ?: throw IllegalStateException("未获取到有效的IP抽抽乐活动")
        val snapshot = loadIpDrawMallSnapshot(activity, forceRefresh)
        syncIpDrawShopSnapshot(snapshot)
        return snapshot.items.map { buildIpChouChouLeExchangeOption(it).toOptionRow() }
    }

    private fun loadIpDrawMallSnapshot(
        activity: IpDrawActivity, forceRefresh: Boolean = false, liveRead: Boolean = false
    ): IpDrawMallSnapshot {
        check(isIpDrawActivityActive(activity)) { "IP抽抽乐活动已结束" }
        val context = JSONObject().put("activityId", activity.activityId).put("endTime", activity.endTime)
        fun toSnapshot(mall: IpDrawMallSnapshot): ExchangeOptionsSnapshot {
            val payload = JSONObject().put("activityId", mall.activityId).put("endTime", mall.endTime)
                .put("balanceCent", mall.balanceCent).put("items", mall.rawItems)
            return ExchangeOptionsSnapshot(mall.items.map { buildIpChouChouLeExchangeOption(it).toOptionRow() }, payload)
        }
        val snapshot = if (liveRead) {
            ExchangeOptionsCache.readAndReplaceFromLive(UserMap.currentUid,
                ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE, context) { checkCurrent ->
                toSnapshot(queryIpDrawMallSnapshot(activity, checkCurrent = checkCurrent)
                    ?: error("未获取到完整IP抽抽乐实时回查快照"))
            }
        } else {
            ExchangeOptionsCache.getOrFetch(UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE,
                forceRefresh, context = { context }) { session ->
                toSnapshot(queryIpDrawMallSnapshot(activity, session = session)
                    ?: error("未获取到完整IP抽抽乐商店快照"))
            }
        }
        val payload = snapshot.payload
        check(payload.getString("activityId") == activity.activityId && isIpDrawActivityActive(activity)) {
            "IP抽抽乐活动已变化，保留后续重新获取"
        }
        val rawItems = payload.getJSONArray("items")
        val items = LinkedHashMap<String, IpDrawMallItem>()
        for (i in 0 until rawItems.length()) {
            parseIpDrawMallItems(rawItems.getJSONObject(i)).forEach { items.putIfAbsent(it.skuId, it) }
        }
        return IpDrawMallSnapshot(activity.activityId, activity.endTime, payload.getInt("balanceCent"), items.values.toList(), rawItems)
    }

    private fun queryActiveIpDrawActivity(): IpDrawActivity? {
        val latest =
            runCatching {
                JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
            }.getOrNull()
        if (latest != null && ResChecker.checkRes(TAG, latest)) {
            latest.optJSONObject("drawMachineActivity")?.let { activity ->
                val activityId = activity.optString("activityId").trim()
                if (activityId.isNotEmpty()) {
                    val activeActivity = IpDrawActivity(activityId, activity.optLong("endTime", 0L))
                    if (isIpDrawActivityActive(activeActivity)) {
                        return activeActivity
                    }
                }
            }
        }

        return null
    }

    private fun queryIpDrawMallSnapshot(
        activity: IpDrawActivity,
        pageSize: Int = 10,
        session: io.github.aoguai.sesameag.task.exchange.ExchangeFetchSession? = null,
        checkCurrent: () -> Unit = {},
    ): IpDrawMallSnapshot? {
        if (activity.activityId.isBlank()) {
            Log.farm("IP抽抽乐商店💸[缺少 activityId，无法查询]")
            return null
        }
        if (!isIpDrawActivityActive(activity)) {
            return null
        }
        val items = mutableListOf<IpDrawMallItem>()
        val rawItems = JSONArray()
        val seenSkuIds = linkedSetOf<String>()
        val seenItemIds = linkedSetOf<String>()
        var balanceCent = 0
        var startIndex = 0

        while (true) {
            checkCurrent()
            if (!isIpDrawActivityActive(activity)) return null
            val jo = runCatching {
                if (session != null) {
                    session.read("items", JSONObject().put("startIndex", startIndex).put("pageSize", pageSize),
                        "商品列表偏移 $startIndex") {
                        JSONObject(AntFarmRpcCall.getItemList(activity.activityId, pageSize, startIndex))
                    }
                } else {
                    io.github.aoguai.sesameag.task.exchange.ExchangeFetchProgress.report(UserMap.currentUid.orEmpty(),
                        ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE, "实时回查商品列表偏移 $startIndex")
                    JSONObject(AntFarmRpcCall.getItemList(activity.activityId, pageSize, startIndex))
                }
            }.onFailure {
                Log.printStackTrace(TAG, "queryIpDrawMallSnapshot err:", it)
            }.getOrNull() ?: return null

            if (!isIpDrawExchangeSuccess(jo)) {
                Log.farm("IP抽抽乐商店💸[获取列表失败: startIndex=$startIndex | ${formatIpDrawRpcResult(jo)}]")
                return null
            }

            jo
                .optJSONObject("mallAccountInfoVO")
                ?.optJSONObject("holdingCount")
                ?.takeIf { it.has("cent") }
                ?.let { balanceCent = it.optInt("cent", balanceCent) }

            val itemInfoVOList = jo.optJSONArray("itemInfoVOList") ?: return null
            val seenCount = seenItemIds.size
            for (i in 0 until itemInfoVOList.length()) {
                val itemJo = itemInfoVOList.optJSONObject(i) ?: return null
                rawItems.put(itemJo)
                itemJo.optString("spuId").trim().takeIf { it.isNotBlank() }?.let { seenItemIds.add("spu:$it") }
                val skuList = itemJo.optJSONArray("skuModelList")
                if (skuList != null) {
                    for (j in 0 until skuList.length()) {
                        skuList.optJSONObject(j)?.optString("skuId")?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { seenItemIds.add("sku:$it") }
                    }
                }
                parseIpDrawMallItems(itemJo).forEach { item ->
                    if (seenSkuIds.add(item.skuId)) {
                        items.add(item)
                    }
                }
            }

            val hasMore = if (jo.has("hasMore")) jo.optBoolean("hasMore", false) else itemInfoVOList.length() >= pageSize
            if (!hasMore) {
                session?.commit(JSONObject().put("done", true).put("balanceCent", balanceCent), itemInfoVOList.length(),
                    "分页结束，保存完整列表")
                break
            }
            val nextStartIndex = if (jo.has("nextStartIndex")) jo.optInt("nextStartIndex", -1) else startIndex + itemInfoVOList.length()
            if (itemInfoVOList.length() == 0 || seenItemIds.size == seenCount || nextStartIndex <= startIndex) {
                Log.farm("IP抽抽乐商店💸[列表分页未前进: startIndex=$startIndex count=${itemInfoVOList.length()} hasMore=$hasMore nextStartIndex=$nextStartIndex]")
                return null
            }
            session?.commit(JSONObject().put("startIndex", nextStartIndex).put("balanceCent", balanceCent), itemInfoVOList.length(),
                "商品列表偏移 $nextStartIndex")
            startIndex = nextStartIndex
            if (session != null) session.pageTurnDelay() else ExchangeFetchPacing.pageTurnDelay()
        }
        checkCurrent()
        if (!isIpDrawActivityActive(activity)) return null
        return IpDrawMallSnapshot(activity.activityId, activity.endTime, balanceCent, items, rawItems)
    }

    private fun queryIpDrawMallItemDetail(item: IpDrawMallItem): IpDrawMallItemDetail? {
        if (item.spuId.isBlank()) {
            return null
        }
        val jo =
            runCatching {
                JSONObject(AntFarmRpcCall.getIpDrawMallItemDetail(item.spuId))
            }.onFailure {
                Log.printStackTrace(TAG, "queryIpDrawMallItemDetail err:", it)
            }.getOrNull() ?: return null

        if (!isIpDrawExchangeSuccess(jo)) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | ${formatIpDrawRpcResult(jo)}]")
            return null
        }

        val detailItemJo = jo.optJSONObject("spuItemInfoVO")
        if (detailItemJo == null) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | 缺少商品详情]")
            return null
        }

        val detailItem = parseIpDrawMallItems(detailItemJo).firstOrNull { it.skuId == item.skuId }
        if (detailItem == null) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | 未匹配 skuId=${item.skuId}]")
            return null
        }

        val balanceCent =
            jo
                .optJSONObject("mallAccountInfoVO")
                ?.optJSONObject("holdingCount")
                ?.takeIf { it.has("cent") }
                ?.optInt("cent")
        return IpDrawMallItemDetail(detailItem, balanceCent)
    }

    private fun isIpDrawActivityActive(activity: IpDrawActivity): Boolean {
        if (activity.endTime > 0 && System.currentTimeMillis() > activity.endTime) {
            Log.farm("IP抽抽乐商店💸[活动已结束: ${activity.activityId}]")
            return false
        }
        return true
    }

    private fun parseIpDrawMallItems(itemJo: JSONObject): List<IpDrawMallItem> {
        val spuId = itemJo.optString("spuId").trim()
        if (spuId.isBlank()) {
            return emptyList()
        }
        val spuName = itemJo.optString("spuName").trim().ifBlank { spuId }
        val itemStatus = itemJo.optString("itemStatus").trim()
        val itemStatusList = itemJo.optJSONArray("itemStatusList")
        val priceCent = itemJo.optJSONObject("minPrice")?.optInt("cent", 0) ?: 0
        val skuList = itemJo.optJSONArray("skuModelList") ?: return emptyList()
        val items = mutableListOf<IpDrawMallItem>()
        for (j in 0 until skuList.length()) {
            val sku = skuList.optJSONObject(j) ?: continue
            val skuId = sku.optString("skuId").trim()
            if (skuId.isBlank()) {
                continue
            }
            val skuName = sku.optString("skuName").trim()
            val displayName = mergeIpDrawItemName(spuName, skuName)
            val skuExtendInfo = sku.optString("skuExtendInfo")
            val limitCount = parseIpDrawLimitCount(skuExtendInfo)
            val skuStatus = sku.optString("itemStatus").trim().ifBlank { sku.optString("skuStatus").trim() }
            val skuStatusList = sku.optJSONArray("itemStatusList") ?: sku.optJSONArray("skuStatusList")
            val userTotalLeftAmount = if (sku.has("userTotalLeftAmount")) sku.optInt("userTotalLeftAmount", -1) else -1
            val statusText = formatIpDrawStatus(itemStatus, itemStatusList, skuStatus, skuStatusList)
            val blockedReason =
                if (userTotalLeftAmount == 0) {
                    "已达兑换限制"
                } else {
                    blockedIpDrawMallItemReason(itemStatus, itemStatusList, skuStatus, skuStatusList)
                }
            val safetyDecision =
                if (blockedReason.isNotBlank()) {
                    ExchangeSafety.UNAVAILABLE to blockedReason
                } else {
                    ExchangeSafetyRules.classify(
                        textValues = listOf(displayName, skuExtendInfo),
                        defaultReason = "涉及实付或下单链路",
                    )
                }
            items.add(
                IpDrawMallItem(
                    spuId = spuId,
                    skuId = skuId,
                    name = displayName,
                    priceCent = priceCent,
                    limitCount = limitCount,
                    itemStatus = itemStatus,
                    itemStatusList = itemStatusList,
                    skuStatus = skuStatus,
                    skuStatusList = skuStatusList,
                    skuExtendInfo = skuExtendInfo,
                    userTotalLeftAmount = userTotalLeftAmount,
                    isIp = skuExtendInfo.contains("\"controlTag\":\"IP限定装扮\"") || skuExtendInfo.contains("IP限定装扮"),
                    isNewEgg = displayName.contains("新蛋卡"),
                    safety = safetyDecision.first,
                    safetyReason = safetyDecision.second,
                ),
            )
        }
        return items
    }

    private fun mergeIpDrawItemName(
        spuName: String,
        skuName: String,
    ): String =
        when {
            skuName.isBlank() || spuName == skuName -> spuName
            skuName.contains(spuName) -> skuName
            spuName.contains(skuName) -> spuName
            else -> spuName + skuName
        }

    private fun parseIpDrawLimitCount(extendInfo: String): Int =
        runCatching {
            "(\\d+)次".toRegex().find(extendInfo)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }.getOrDefault(0)

    private fun syncIpDrawShopSnapshot(
        snapshot: IpDrawMallSnapshot,
        existingData: IpChouChouLeData? = null,
    ): IpChouChouLeData {
        val userId = UserMap.currentUid
        val data = existingData ?: loadData(userId)
        var changed = false
        if (data.activityId != snapshot.activityId) {
            Log.farm("[自动兑换]: 检测到活动变更 (${snapshot.activityId})，重置本地兑换记录并更新商店列表")
            data.activityId = snapshot.activityId
            data.exchangedCounts.clear()
            data.shopItems.clear()
            changed = true
        }
        val latestShopItems = LinkedHashMap<String, String>()
        snapshot.items.forEach { item ->
            latestShopItems[item.skuId] = "${item.name}|${item.limitCount}|${item.priceCent}"
        }
        if (data.shopItems != latestShopItems) {
            data.shopItems.clear()
            data.shopItems.putAll(latestShopItems)
            changed = true
        }
        if (changed || existingData == null) {
            saveData(userId, data)
        }
        return data
    }

    private fun buildCustomExchangeSequence(
        customMap: MutableMap<String?, Int?>?,
        snapshot: IpDrawMallSnapshot,
        data: IpChouChouLeData,
    ): List<Pair<IpDrawMallItem, Int>> {
        if (customMap.isNullOrEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<Pair<IpDrawMallItem, Int>>()
        val missingCustomSkuIds = linkedSetOf<String>()
        customMap.entries.forEach { entry ->
            val skuId = entry.key?.trim().orEmpty()
            val targetCount = entry.value ?: 0
            if (skuId.isBlank() || targetCount <= 0) {
                return@forEach
            }
            val item = snapshot.items.firstOrNull { it.skuId == skuId }
            if (item == null) {
                missingCustomSkuIds.add(skuId)
                return@forEach
            }
            val alreadyExchanged = data.exchangedCounts[skuId] ?: 0
            val needToExchange = targetCount - alreadyExchanged
            if (needToExchange > 0) {
                result.add(item to needToExchange)
            } else {
                Log.farm("[自动兑换]: [${item.name}] 已达到自定义兑换数量($targetCount)，跳过")
            }
        }
        missingCustomSkuIds.forEach { skuId ->
            Log.farm("[自动兑换]: 自定义商品[$skuId] 当前商店未找到，已跳过")
        }
        return result
    }

    private fun buildDefaultExchangeSequence(snapshot: IpDrawMallSnapshot): List<Pair<IpDrawMallItem, Int>> {
        val sortedItems =
            snapshot.items.sortedWith { a, b ->
                when {
                    a.isIp != b.isIp -> if (a.isIp) -1 else 1
                    a.isNewEgg != b.isNewEgg -> if (a.isNewEgg) 1 else -1
                    else -> b.priceCent.compareTo(a.priceCent)
                }
            }

        return sortedItems.map { it to (if (it.limitCount > 0) it.limitCount else 1) }
    }

    private fun buildIpChouChouLeExchangeOption(item: IpDrawMallItem): ExchangeItem {
        val effectTags = ExchangeEffectCatalog.tagsFor(ExchangeEffectCatalog.SOURCE_FARM_PARADISE, item.name)
        val statusText = formatIpDrawStatus(item)
        return ExchangeItem(
            id = item.skuId,
            name = item.name,
            cost = ExchangeCost(pointText = "${item.priceCent / 100}碎片"),
            limit =
                ExchangeLimit(
                    statusText =
                        listOf(
                            item.limitCount
                                .takeIf { it > 0 }
                                ?.let { "限购${it}次" }
                                .orEmpty(),
                            statusText,
                        ).filter { it.isNotBlank() }.joinToString("、"),
                ),
            safety = item.safety,
            safetyReason = item.safetyReason,
            effectTags = effectTags,
            displayMeta =
                ExchangeEffectCatalog
                    .displayMeta(
                        ExchangeEffectCatalog.SOURCE_FARM_PARADISE,
                        item.name,
                        item.safety,
                        item.safetyReason,
                        effectTags,
                    ).copy(sourceModule = "庄园装扮抽抽乐"),
        )
    }

    private fun isIpDrawExchangeConfirmed(
        beforeSnapshot: IpDrawMallSnapshot,
        afterSnapshot: IpDrawMallSnapshot,
        beforeItem: IpDrawMallItem,
        refreshedItem: IpDrawMallItem?,
    ): Boolean {
        if (afterSnapshot.balanceCent < beforeSnapshot.balanceCent) {
            return true
        }
        if (refreshedItem != null &&
            beforeItem.userTotalLeftAmount > 0 &&
            refreshedItem.userTotalLeftAmount in 0 until beforeItem.userTotalLeftAmount
        ) {
            return true
        }
        if (refreshedItem == null) {
            return true
        }
        return false
    }

    private fun isIpDrawTerminalExchangeCode(code: String): Boolean =
        code in setOf(
            "REACH_LIMIT",
            "REACH_USER_HOLD_LIMIT",
            "SOLD_OUT",
            "EXCHANGE_END",
            "END",
        )

    private fun isIpDrawTerminalExchangeStatus(item: IpDrawMallItem): Boolean {
        val statuses = collectIpDrawStatuses(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
        return statuses.any(::isIpDrawTerminalExchangeCode)
    }

    private fun isIpDrawNoEnoughPointStatus(item: IpDrawMallItem): Boolean {
        val statuses = collectIpDrawStatuses(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
        return statuses.any { it == "NO_ENOUGH_POINT" }
    }

    private fun shouldStopDefaultExchangeForNoEnoughPoint(item: IpDrawMallItem): Boolean =
        isIpDrawNoEnoughPointStatus(item) && !isIpDrawTerminalExchangeStatus(item)

    private fun blockedIpDrawMallItemReason(item: IpDrawMallItem): String {
        if (item.userTotalLeftAmount == 0) {
            return "已达兑换限制"
        }
        return blockedIpDrawMallItemReason(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
    }

    private fun blockedIpDrawMallItemReason(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?,
    ): String {
        val statuses = collectIpDrawStatuses(itemStatus, itemStatusList, skuStatus, skuStatusList)
        val blocked =
            statuses.firstOrNull { status ->
                status == "REACH_LIMIT" ||
                    status == "REACH_USER_HOLD_LIMIT" ||
                    status == "NO_ENOUGH_POINT" ||
                    status == "SOLD_OUT" ||
                    status == "EXCHANGE_END" ||
                    status == "END"
            } ?: return ""
        return ipDrawStatusName(blocked)
    }

    private fun formatIpDrawStatus(item: IpDrawMallItem): String =
        formatIpDrawStatus(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)

    private fun formatIpDrawStatus(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?,
    ): String =
        collectIpDrawStatuses(itemStatus, itemStatusList, skuStatus, skuStatusList)
            .map { ipDrawStatusName(it) }
            .joinToString("、")

    private fun collectIpDrawStatuses(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?,
    ): LinkedHashSet<String> {
        val statuses = linkedSetOf<String>()
        itemStatus.takeIf { it.isNotBlank() }?.let { statuses.add(it) }
        skuStatus.takeIf { it.isNotBlank() }?.let { statuses.add(it) }
        listOf(itemStatusList, skuStatusList).forEach { list ->
            if (list != null) {
                for (i in 0 until list.length()) {
                    list.optString(i).takeIf { it.isNotBlank() }?.let { statuses.add(it) }
                }
            }
        }
        return statuses
    }

    private fun ipDrawStatusName(status: String): String =
        when (status) {
            "REACH_USER_HOLD_LIMIT" -> "已拥有/已达持有限制"
            "REACH_LIMIT" -> "已达兑换限制"
            "NO_ENOUGH_POINT" -> "碎片不足"
            "SOLD_OUT" -> "已售罄"
            "EXCHANGE_END" -> "兑换已结束"
            "END" -> "已结束"
            else -> status
        }

    private fun isIpDrawExchangeSuccess(jo: JSONObject): Boolean = ExchangeSafetyRules.isSuccessResponse(jo) || ResChecker.checkRes(TAG, jo)

    private fun formatIpDrawRpcResult(jo: JSONObject): String {
        val parts = mutableListOf<String>()
        if (jo.has("success")) {
            parts.add("success=${jo.optBoolean("success")}")
        }
        listOf("resultCode", "code", "memo", "resultDesc", "desc").forEach { key ->
            jo.optString(key).takeIf { it.isNotBlank() }?.let { parts.add("$key=$it") }
        }
        return parts.joinToString(" | ").ifBlank { jo.toString() }
    }
}
