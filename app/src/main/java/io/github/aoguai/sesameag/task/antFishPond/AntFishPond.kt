package io.github.aoguai.sesameag.task.antFishPond

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.VipDataIdMap
import org.json.JSONObject

class AntFishPond : ModelTask() {
    private data class FishProgressSnapshot(
        val current: String,
        val target: String,
        val diff: String,
        val rodCount: Int,
    )

    private lateinit var fishPondTask: BooleanModelField
    private lateinit var autoFish: BooleanModelField
    private lateinit var fishDailyLimit: IntegerModelField

    private val handledTaskAwards = LinkedHashSet<String>()
    private val handledVisitFinishes = LinkedHashSet<String>()
    private var lastLoggedFishProgress: FishProgressSnapshot? = null
    private var rewardExchangeStoppedForCurrentRun = false

    override fun getName(): String = "福气鱼池"

    override fun getGroup(): ModelGroup = ModelGroup.FISHPOND

    override fun getIcon(): String = "AntFishPond.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("fishPondTask", "鱼池任务 | 领奖", false)
                .withDesc(
                    "开启后自动处理福气鱼池签到、宝箱、明日钓竿和已验证任务奖励。",
                ).also { fishPondTask = it },
        )
        modelFields.addField(
            BooleanModelField("autoFish", "自动钓鱼 | 开启", false)
                .withDesc(
                    "开启后使用最近捕获的鱼池令牌自动钓鱼；没有令牌时提示原因并跳过。",
                ).also { autoFish = it },
        )
        modelFields.addField(
            IntegerModelField("fishDailyLimit", "自动钓鱼 | 每日次数", DEFAULT_FISH_LIMIT, 0, 200)
                .withDesc(
                    "限制当天最多钓鱼的次数，0 表示不限制；默认限制为 30 次。需开启“自动钓鱼 | 开启”。",
                ).also { fishDailyLimit = it },
        )
        return modelFields
    }

    override fun runJava() {
        try {
            Log.fishpond("执行开始-${getName()}")
            handledTaskAwards.clear()
            handledVisitFinishes.clear()
            lastLoggedFishProgress = null
            rewardExchangeStoppedForCurrentRun = false

            val indexJson = queryIndex(logProgress = true)
            if (indexJson != null && exchangeRewardAndReloadIndex(indexJson, "首页") == null) {
                return
            }

            val delayTaskDoneFlag = autoFish.value == true
            var subplotChanged = false
            var initialTaskListDone = false
            if (fishPondTask.value == true) {
                subplotChanged = handleSubplots()
                initialTaskListDone = handleTaskList(allowMarkDone = !delayTaskDoneFlag)
            }

            val autoFishChanged =
                if (autoFish.value == true) {
                    runAutoFish()
                } else {
                    false
                }
            if (autoFishChanged == null) {
                return
            }

            if (fishPondTask.value == true && autoFish.value == true) {
                if (autoFishChanged) {
                    var latestTaskListDone = initialTaskListDone
                    while (true) {
                        handleSubplots()
                        latestTaskListDone = handleTaskList(skipIfHandledToday = false, allowMarkDone = false)
                        val followUpIndex = queryIndex()
                        if (followUpIndex == null || extractRodCount(followUpIndex) <= 0) {
                            break
                        }
                        val followUpFishChanged = runAutoFish()
                        if (followUpFishChanged == null) {
                            return
                        }
                        if (!followUpFishChanged) {
                            break
                        }
                    }
                    if (latestTaskListDone) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE)
                    }
                } else if (subplotChanged) {
                    Log.fishpond("本轮未实际钓鱼，但鱼池子活动有进展，补刷新任务状态")
                    handleTaskList(skipIfHandledToday = false, allowMarkDone = true)
                } else {
                    if (initialTaskListDone) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE)
                    }
                    Log.fishpond("本轮未实际钓鱼，跳过钓鱼后任务刷新")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "start.run err:", t)
        } finally {
            Log.fishpond("执行结束-${getName()}")
        }
    }

    private fun queryIndex(logProgress: Boolean = false): JSONObject? {
        val response = AntFishPondRpcCall.fishpondIndex()
        if (response.isBlank()) {
            Log.runtime(TAG, "fishpondIndex返回空")
            return null
        }
        val jo = JSONObject(response)
        if (!isRpcSuccess(jo)) {
            Log.fishpond("福气鱼池首页查询失败：${formatFailure(jo)}")
            return null
        }

        val payload = payloadOf(jo)
        if (!payload.optBoolean("open", true)) {
            Log.fishpond("福气鱼池未开通，本轮跳过")
            return jo
        }
        if (logProgress) {
            logFishProgress(jo)
        }
        return jo
    }

    private fun handleSubplots(): Boolean {
        try {
            val response = AntFishPondRpcCall.querySubplotsActivity()
            if (response.isBlank()) {
                Log.runtime(TAG, "querySubplotsActivity返回空")
                return false
            }
            val jo = JSONObject(response)
            if (!isRpcSuccess(jo)) {
                Log.fishpond("鱼池活动查询失败：${formatFailure(jo)}")
                return false
            }

            val activityList = payloadOf(jo).optJSONArray("subplotsActivityList") ?: return false
            var progressed = false
            for (i in 0 until activityList.length()) {
                val item = activityList.optJSONObject(i) ?: continue
                val activityType =
                    item
                        .optString("activityType")
                        .ifBlank { item.optString("activityId") }
                val status = item.optString("status")
                val extend = parseObject(item.optString("extend"))
                val extendStatus = extend?.optString("status").orEmpty()

                when (activityType) {
                    ACTIVITY_GIFT_BOX -> progressed = handleGiftBox(status, extendStatus) || progressed
                    ACTIVITY_TOMORROW_ROD -> progressed = handleTomorrowRod(status) || progressed
                    ACTIVITY_FISH -> progressed = handleFishActivity(status, extend) || progressed
                }
            }
            return progressed
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleSubplots err:", t)
        }
        return false
    }

    private fun handleGiftBox(
        status: String,
        extendStatus: String,
    ): Boolean {
        if (status == STATUS_FINISHED || extendStatus == STATUS_FINISHED) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_GIFT_BOX_DONE)
            return false
        }
        if (status != STATUS_TODO && extendStatus != STATUS_TODO) {
            return false
        }
        val trigger = AntFishPondRpcCall.triggerSubplotsActivity(ACTIVITY_GIFT_BOX, ACTION_RECEIVE_AWARD)
        val jo = JSONObject(trigger)
        if (isRpcSuccess(jo)) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_GIFT_BOX_DONE)
            Log.fishpond("每日宝箱🎁领取成功")
            AntFishPondRpcCall.fishpondSyncIndex(listOf("GIFT_BOX", "TASK_DISPLAY"))
            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
            return true
        } else {
            Log.fishpond("每日宝箱领取失败：${formatFailure(jo)}")
        }
        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        return false
    }

    private fun handleTomorrowRod(status: String): Boolean {
        if (status == "TODAY_FINISH") {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_TOMORROW_ROD_DONE)
            return false
        }
        if (status != "TODAY_TODO") {
            return false
        }
        val trigger = AntFishPondRpcCall.triggerSubplotsActivity(ACTIVITY_TOMORROW_ROD, ACTION_FINISH)
        val jo = JSONObject(trigger)
        if (isRpcSuccess(jo)) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_TOMORROW_ROD_DONE)
            Log.fishpond("明日钓竿🎣领取成功")
            AntFishPondRpcCall.fishpondSyncIndex(listOf("TOMORROW_ROD"))
            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
            return true
        } else {
            Log.fishpond("明日钓竿领取失败：${formatFailure(jo)}")
        }
        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        return false
    }

    private fun handleFishActivity(
        status: String,
        extend: JSONObject?,
    ): Boolean {
        val extendStatus = extend?.optString("status").orEmpty()
        val leftFishTimes = extend?.optInt("leftFishTimes", Int.MAX_VALUE) ?: Int.MAX_VALUE
        val claimable =
            status in CLAIMABLE_STATUS ||
                extendStatus in CLAIMABLE_STATUS ||
                leftFishTimes <= 0
        if (!claimable) {
            return false
        }

        val trigger = AntFishPondRpcCall.triggerSubplotsActivity(ACTIVITY_FISH, ACTION_RECEIVE_AWARD)
        val jo = JSONObject(trigger)
        if (isRpcSuccess(jo)) {
            Log.fishpond("钓鱼活动奖励🎣领取成功")
            handleFishActivityRewardRefresh(jo)
            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
            return true
        } else {
            Log.fishpond("钓鱼活动奖励领取失败：${formatFailure(jo)}")
        }
        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        return false
    }

    private fun handleTaskList(
        skipIfHandledToday: Boolean = true,
        allowMarkDone: Boolean = true,
    ): Boolean {
        try {
            if (skipIfHandledToday &&
                Status.hasFlagToday(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE) &&
                Status.hasFlagToday(StatusFlags.FLAG_ANTFISHPOND_SIGN_DONE)
            ) {
                Log.fishpond("福气鱼池任务[今日已处理，跳过]")
                return true
            }
            val listJson = queryTaskList() ?: return false
            handleSign(listJson)

            val taskFlowAdapter = FishPondTaskFlowAdapter(skipIfHandledToday, listJson)
            val result =
                TaskFlowEngine(
                    taskFlowAdapter,
                    roundSleepMs = SHORT_INTERVAL_MS,
                ).run()
            val canMarkDone = !result.stopped && taskFlowAdapter.canMarkTasksDone()
            if (allowMarkDone && canMarkDone) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE)
            }
            return canMarkDone
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleTaskList err:", t)
        }
        return false
    }

    private fun queryTaskList(): JSONObject? {
        val response = AntFishPondRpcCall.listTask()
        if (response.isBlank()) {
            Log.runtime(TAG, "listTask返回空")
            return null
        }
        val jo = JSONObject(response)
        if (!isRpcSuccess(jo)) {
            Log.fishpond("钓竿任务查询失败：${formatFailure(jo)}")
            return null
        }
        return jo
    }

    private fun handleSign(listJson: JSONObject): Boolean {
        val signList =
            payloadOf(listJson)
                .optJSONObject("signInfo")
                ?.optJSONArray("list")
                ?: return false
        for (i in 0 until signList.length()) {
            val signItem = signList.optJSONObject(i) ?: continue
            if (!signItem.optBoolean("today")) {
                continue
            }
            if (signItem.optBoolean("signed")) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_SIGN_DONE)
                return false
            }

            val signKey = signItem.optString("signKey")
            val response =
                if (signKey.isBlank()) {
                    AntFishPondRpcCall.sign()
                } else {
                    AntFishPondRpcCall.sign(signKey)
                }
            val jo = JSONObject(response)
            if (isRpcSuccess(jo)) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_SIGN_DONE)
                val awardCount = signItem.optInt("awardCount", 1)
                Log.fishpond("每日签到🎣领取${awardCount}根钓竿")
                AntFishPondRpcCall.fishpondSyncIndex(listOf("TASK_DISPLAY"))
                GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
                return true
            }
            Log.fishpond("每日签到失败：${formatFailure(jo)}")
            return false
        }
        return false
    }

    private inner class FishPondTaskFlowAdapter(
        private val skipIfHandledToday: Boolean,
        initialResponse: JSONObject,
    ) : TaskFlowAdapter {
        private var firstResponse: JSONObject? = initialResponse
        private val loggedSkipKeys = LinkedHashSet<String>()
        private var latestItems: List<TaskFlowItem> = emptyList()
        private var querySucceeded = false
        private var unknownPhaseSeen = false
        private var unknownFailureSeen = false

        override val moduleName: String = TASK_BLACKLIST_MODULE
        override val flowName: String = "福气鱼池任务"

        override fun isFlowHandledToday(): Boolean =
            skipIfHandledToday && Status.hasFlagToday(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE)

        override fun query(): JSONObject {
            firstResponse?.let { response ->
                firstResponse = null
                return response
            }
            val response = AntFishPondRpcCall.listTask()
            if (response.isBlank()) {
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "listTask返回空")
            }
            return parseObject(response) ?: JSONObject()
                .put("success", false)
                .put("resultDesc", "listTask返回无法解析")
                .put("raw", response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean = isRpcSuccess(response)

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            querySucceeded = true
            val taskList =
                payloadOf(response).optJSONArray("taskList") ?: run {
                    latestItems = emptyList()
                    return emptyList()
                }
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val taskId = task.optString("taskId").trim()
                if (taskId.isBlank()) {
                    Log.error(TAG, "${flowName}任务缺少taskId，保留后续人工核查：$task")
                    continue
                }
                val title = taskTitle(task).trim().ifBlank { "未知任务" }
                val taskRequire = task.optInt("taskRequire", 0)
                val taskProgress = task.optInt("taskProgress", 0)
                val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
                val rightsTimes = task.optInt("rightsTimes", 0)
                val current =
                    when {
                        taskRequire > 0 -> taskProgress
                        rightsTimesLimit > 0 -> rightsTimes
                        else -> null
                    }
                val limit =
                    when {
                        taskRequire > 0 -> taskRequire
                        rightsTimesLimit > 0 -> rightsTimesLimit
                        else -> null
                    }

                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = title,
                        status = task.optString("taskStatus").trim(),
                        type = taskId,
                        sceneCode = task.optString("sceneCode", TASK_SCENE).trim().ifBlank { TASK_SCENE },
                        actionType = task.optString("actionType").trim(),
                        blacklistKeys = listOf(taskId),
                        raw = task,
                        progress = buildFishPondTaskProgress(task),
                        current = current,
                        limit = limit,
                    ),
                )
            }
            latestItems = items
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            val task = item.raw
            if (task != null && shouldClaimGoFishAward(task)) {
                return TaskFlowPhase.REWARD_READY
            }
            return when (item.status.uppercase()) {
                "RECEIVED",
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED",
                "SUCCESS",
                "COMPLETE",
                -> {
                    TaskFlowPhase.TERMINAL
                }

                "FINISHED",
                "RECEIVABLE",
                "TODO_RECEIVE",
                "WAIT_RECEIVE",
                "TO_RECEIVE",
                -> {
                    TaskFlowPhase.REWARD_READY
                }

                STATUS_TODO,
                "WAIT_COMPLETE",
                "NOT_DONE",
                -> {
                    when {
                        item.actionType == ACTION_VISIT && taskHasDirectAdBizNo(task) -> {
                            TaskFlowPhase.READY_TO_COMPLETE
                        }

                        item.id == TASK_JINDOU_TREASURE && item.actionType == "TRIGGER" -> {
                            TaskFlowPhase.READY_TO_COMPLETE
                        }

                        item.actionType == ACTION_GO_FISH -> {
                            TaskFlowPhase.BUSINESS_ACTION
                        }

                        else -> {
                            TaskFlowPhase.UNSUPPORTED
                        }
                    }
                }

                else -> {
                    TaskFlowPhase.UNKNOWN
                }
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val phase = mapPhase(item)
            when (phase) {
                TaskFlowPhase.BUSINESS_ACTION -> {
                    logTaskSkipOnce(item, "action=${item.actionType} 需通过钓鱼业务动作推进，跳过任务中心直完成")
                    return true
                }

                TaskFlowPhase.UNSUPPORTED -> {
                    logTaskSkipOnce(
                        item,
                        "action=${item.actionType.ifBlank { "UNKNOWN" }} status=${item.status.ifBlank { "UNKNOWN" }} 暂未支持自动闭环",
                    )
                    return true
                }

                else -> {
                    Unit
                }
            }

            val handled =
                when (phase) {
                    TaskFlowPhase.REWARD_READY -> handledTaskAwards.contains(buildFishPondAwardKey(item))
                    TaskFlowPhase.READY_TO_COMPLETE -> handledVisitFinishes.contains(buildFishPondVisitKey(item))
                    else -> false
                }
            if (handled) {
                logTaskSkipOnce(item, "本轮已推进，等待刷新后再处理")
            }
            return handled
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logTaskSkipOnce(item, "已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            return claimTaskAward(item)
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (item.id == TASK_JINDOU_TREASURE && item.actionType == "TRIGGER") {
                return completeFishPondNormalTask(item)
            }
            if (item.actionType != ACTION_VISIT) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                    code = "UNSUPPORTED_ACTION",
                    message = "仅支持鱼池IEP浏览任务",
                    rpc = "FishPondTaskFlowAdapter.complete",
                    detail = fishPondTaskActionDetail(item, "complete"),
                )
            }
            val task = item.raw ?: return missingRawResult(item, "complete")
            val adBizNo = taskAdBizNo(task)
            if (adBizNo.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                    message = "浏览任务缺少直接adBizNo",
                    rpc = "FishPondTaskFlowAdapter.complete",
                    detail = fishPondTaskActionDetail(item, "complete"),
                )
            }
            return completeFishPondAdTask(
                item = item,
                adBizNo = adBizNo,
                syncTypeList = VISIT_TASK_SYNC_TYPES,
                successMessage = "浏览任务🧾[${item.title}]完成",
                terminalMessage = "浏览任务🧾[${item.title}]已完成，刷新状态",
            )
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String =
            when (action) {
                TaskFlowAction.RECEIVE -> "receive:${buildFishPondAwardKey(item)}"
                TaskFlowAction.COMPLETE -> "complete:${buildFishPondVisitKey(item)}"
                else -> super<TaskFlowAdapter>.actionKey(item, action)
            }

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (result.progressChanged) {
                rememberHandledTask(item, action)
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision,
        ) {
            if (result.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW) {
                unknownFailureSeen = true
            }
            if (decision == TaskFlowDecision.MARK_HANDLED ||
                decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN ||
                decision == TaskFlowDecision.BLACKLIST
            ) {
                rememberHandledTask(item, action)
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            Log.fishpond("福气鱼池任务列表已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.fishpond("钓竿任务查询失败：${formatFailure(response)} raw=$response")
        }

        override fun onUnknownPhase(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ) {
            unknownPhaseSeen = true
            Log.error(
                TAG,
                "$flowName[未知状态：${item.title}] taskId=${item.id} status=${item.status.ifBlank { "UNKNOWN" }} " +
                    "actionType=${item.actionType.ifBlank { "UNKNOWN" }} sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}",
            )
        }

        override fun logInfo(message: String) {
            Log.fishpond(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        fun canMarkTasksDone(): Boolean {
            if (!querySucceeded || unknownPhaseSeen || unknownFailureSeen) {
                return false
            }
            for (item in latestItems) {
                if (super<TaskFlowAdapter>.isBlacklisted(item)) {
                    continue
                }
                val phase = mapPhase(item)
                if (phase == TaskFlowPhase.UNKNOWN) {
                    return false
                }
                if (phase == TaskFlowPhase.REWARD_READY &&
                    !handledTaskAwards.contains(buildFishPondAwardKey(item))
                ) {
                    return false
                }
                if (phase == TaskFlowPhase.READY_TO_COMPLETE &&
                    !handledVisitFinishes.contains(buildFishPondVisitKey(item))
                ) {
                    return false
                }
            }
            return true
        }

        private fun rememberHandledTask(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ) {
            when (action) {
                TaskFlowAction.RECEIVE -> handledTaskAwards.add(buildFishPondAwardKey(item))
                TaskFlowAction.COMPLETE -> handledVisitFinishes.add(buildFishPondVisitKey(item))
                else -> Unit
            }
        }

        private fun missingRawResult(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少任务原始数据",
                rpc = "FishPondTaskFlowAdapter.$action",
                detail = fishPondTaskActionDetail(item, action),
            )

        private fun logTaskSkipOnce(
            item: TaskFlowItem,
            reason: String,
        ) {
            val key = "${item.id}:${item.actionType}:${item.status}:$reason"
            if (loggedSkipKeys.add(key)) {
                Log.fishpond(
                    "鱼池任务跳过[${item.title}] $reason " +
                        "taskId=${item.id} sceneCode=${item.sceneCode} progress=${item.progress.ifBlank { "UNKNOWN" }}",
                )
            }
        }
    }

    private fun shouldClaimGoFishAward(task: JSONObject): Boolean {
        if (task.optString("taskId") != TASK_GO_FISH) {
            return false
        }
        val taskStatus = task.optString("taskStatus")
        if (taskStatus == "RECEIVED" || taskStatus == "COMPLETE") {
            return false
        }
        if (taskStatus in CLAIMABLE_STATUS) {
            return true
        }
        val taskRequire = task.optInt("taskRequire", -1)
        return taskRequire > 0 && task.optInt("taskProgress", 0) >= taskRequire
    }

    private fun claimTaskAward(item: TaskFlowItem): TaskFlowActionResult {
        val response = AntFishPondRpcCall.receiveTaskAward(item.type, item.sceneCode)
        val jo =
            parseObject(response) ?: return emptyFishPondTaskResponse(
                rpc = "AntFishPondRpcCall.receiveTaskAward",
                item = item,
                action = "receiveTaskAward",
                raw = response,
            )
        if (isRpcSuccess(jo)) {
            val requiresStateConfirmation = item.id == TASK_JINDOU_TREASURE
            Log.fishpond(
                if (requiresStateConfirmation) {
                    "任务奖励🎖️[${item.title}]已提交，等待任务列表确认"
                } else {
                    "任务奖励🎖️[${item.title}]领取成功"
                },
            )
            AntFishPondRpcCall.fishpondSyncIndex(listOf("TASK_DISPLAY"))
            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
            return TaskFlowActionResult.success(
                refreshAfterAction = requiresStateConfirmation,
                progressChanged = !requiresStateConfirmation,
            )
        }
        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        return fishPondTaskActionFailureResult(
            response = jo,
            rpc = "AntFishPondRpcCall.receiveTaskAward",
            item = item,
            action = "receiveTaskAward",
        )
    }

    private fun completeFishPondAdTask(
        item: TaskFlowItem,
        adBizNo: String,
        syncTypeList: List<String>,
        successMessage: String,
        terminalMessage: String = successMessage,
        syncVerifier: (JSONObject) -> Boolean = { true },
        syncFailureMessage: String = "广告任务回查未见预期状态",
    ): TaskFlowActionResult {
        val noticeRaw = AntFishPondRpcCall.fishpondAdNotice(adBizNo)
        val notice =
            parseObject(noticeRaw) ?: return emptyFishPondTaskResponse(
                rpc = "AntFishPondRpcCall.fishpondAdNotice",
                item = item,
                action = "fishpondAdNotice",
                raw = noticeRaw,
            )
        if (!isRpcSuccess(notice)) {
            return fishPondTaskActionFailureResult(
                response = notice,
                rpc = "AntFishPondRpcCall.fishpondAdNotice",
                item = item,
                action = "fishpondAdNotice",
            )
        }

        val finishRaw = AntFishPondRpcCall.finishTask(item.type, adBizNo, item.sceneCode)
        val finish =
            parseObject(finishRaw) ?: return emptyFishPondTaskResponse(
                rpc = "AntFishPondRpcCall.finishTask",
                item = item,
                action = "finishTask",
                raw = finishRaw,
            )
        val finishFailureType = classifyFishPondTaskFailure(finish)
        if (isRpcSuccess(finish) || finishFailureType == TaskRpcFailureType.TERMINAL_DONE) {
            val syncRaw = AntFishPondRpcCall.fishpondSyncIndex(syncTypeList)
            val sync =
                parseObject(syncRaw) ?: return emptyFishPondTaskResponse(
                    rpc = "AntFishPondRpcCall.fishpondSyncIndex",
                    item = item,
                    action = "fishpondSyncIndex",
                    raw = syncRaw,
                )
            if (!isRpcSuccess(sync)) {
                return fishPondTaskActionFailureResult(
                    response = sync,
                    rpc = "AntFishPondRpcCall.fishpondSyncIndex",
                    item = item,
                    action = "fishpondSyncIndex",
                )
            }
            if (!syncVerifier(sync)) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = syncFailureMessage,
                    rpc = "AntFishPondRpcCall.fishpondSyncIndex",
                    raw = sync.toString(),
                    detail = fishPondTaskActionDetail(item, "fishpondSyncIndex"),
                )
            }
            Log.fishpond(if (finishFailureType == TaskRpcFailureType.TERMINAL_DONE) terminalMessage else successMessage)
            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
            return TaskFlowActionResult.success()
        }

        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        return fishPondTaskActionFailureResult(
            response = finish,
            rpc = "AntFishPondRpcCall.finishTask",
            item = item,
            action = "finishTask",
        )
    }

    private fun completeFishPondNormalTask(item: TaskFlowItem): TaskFlowActionResult {
        val finishRaw = AntFishPondRpcCall.finishNormalTask(item.type, item.sceneCode)
        val finish =
            parseObject(finishRaw) ?: return emptyFishPondTaskResponse(
                rpc = "AntFishPondRpcCall.finishNormalTask",
                item = item,
                action = "finishTask",
                raw = finishRaw,
            )
        val finishFailureType = classifyFishPondTaskFailure(finish)
        if (!isRpcSuccess(finish) && finishFailureType != TaskRpcFailureType.TERMINAL_DONE) {
            return fishPondTaskActionFailureResult(
                response = finish,
                rpc = "AntFishPondRpcCall.finishNormalTask",
                item = item,
                action = "finishTask",
            )
        }

        Log.fishpond("鱼池任务🧾[${item.title}]已提交，等待任务列表确认")
        return TaskFlowActionResult.success(refreshAfterAction = true, progressChanged = false)
    }

    private fun runAutoFish(): Boolean? {
        val riskToken = loadRiskToken()
        if (riskToken.isBlank()) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_RISK_TOKEN_MISSING)
            Log.fishpond("缺少 fishpondAngle riskToken，跳过自动钓鱼；请先手动进入鱼池钓鱼以捕获 token")
            return false
        }
        Status.removeFlag(StatusFlags.FLAG_ANTFISHPOND_RISK_TOKEN_MISSING)

        var indexJson = queryIndex(logProgress = true) ?: return false
        indexJson = exchangeRewardAndReloadIndex(indexJson, "自动钓鱼首页") ?: return null
        if (isFishRoundTargetReached(indexJson)) {
            Log.fishpond("当前鱼池轮次已达目标，跳过自动钓鱼")
            return false
        }

        var rodCount = extractRodCount(indexJson)
        if (rodCount <= 0) {
            Log.fishpond("当前无可用钓竿，跳过自动钓鱼")
            return false
        }

        val limit = fishDailyLimit.value ?: DEFAULT_FISH_LIMIT
        var usedToday = Status.getIntFlagToday(StatusFlags.FLAG_ANTFISHPOND_FISH_COUNT) ?: 0
        if (limit <= 0 || usedToday < limit) {
            Status.removeFlag(StatusFlags.FLAG_ANTFISHPOND_FISH_LIMIT_REACHED)
        }
        if (limit > 0 && usedToday >= limit) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_FISH_LIMIT_REACHED)
            Log.fishpond("今日自动钓鱼已达每日上限${limit}次，当前累计${usedToday}次，剩余钓竿${rodCount}根")
            return false
        }

        var handledCount = 0
        while (rodCount > 0 && !Thread.currentThread().isInterrupted) {
            if (limit > 0 && usedToday >= limit) {
                break
            }
            indexJson = exchangeRewardAndReloadIndex(indexJson, "自动钓鱼首页") ?: return null
            if (isFishRoundTargetReached(indexJson)) {
                Log.fishpond("当前鱼池轮次已达目标，停止自动钓鱼")
                break
            }
            rodCount = extractRodCount(indexJson)
            if (rodCount <= 0) {
                break
            }

            val angleResponse = AntFishPondRpcCall.fishpondAngle(riskToken)
            if (angleResponse.isBlank()) {
                Log.runtime(TAG, "fishpondAngle返回空")
                break
            }
            var angleJson = JSONObject(angleResponse)
            val angleExchangeSource = angleJson
            if (!isRpcSuccess(angleJson)) {
                Log.fishpond("钓鱼失败：${formatFailure(angleJson)}")
                break
            }

            handledCount++
            usedToday += 1
            Status.setIntFlagToday(StatusFlags.FLAG_ANTFISHPOND_FISH_COUNT, usedToday)

            val angleInfo = angleInfoOf(angleJson)
            if (angleInfo.optString("fishType") == FISH_TYPE_WELFARE) {
                val bizNo = angleInfo.optString("bizNo")
                if (bizNo.isNotBlank()) {
                    positionBigFish(bizNo)?.let { angleJson = it }
                } else {
                    Log.fishpond("触发福利鱼但缺少 bizNo，跳过大鱼定位")
                }
            }

            logAngleResult(angleJson)
            val exchangeSource =
                when {
                    canExchange(angleJson) -> angleJson
                    canExchange(angleExchangeSource) -> angleExchangeSource
                    else -> null
                }
            if (exchangeSource != null) {
                indexJson = exchangeRewardAndReloadIndex(exchangeSource, "钓鱼结果") ?: return null
                rodCount = extractRodCount(indexJson)
                GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
                continue
            }

            val syncJson = syncAfterFish()
            if (syncJson != null) {
                indexJson = exchangeRewardAndReloadIndex(syncJson, "钓鱼后刷新") ?: return null
                rodCount = extractRodCount(indexJson)
                logFishProgress(indexJson)
            } else {
                rodCount = extractRodCount(angleJson).takeIf { it >= 0 } ?: (rodCount - 1)
            }

            GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        }

        if (limit > 0 && usedToday >= limit) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFISHPOND_FISH_LIMIT_REACHED)
            if (rodCount > 0) {
                Log.fishpond("今日自动钓鱼已达每日上限${limit}次，本轮执行${handledCount}次，剩余钓竿${rodCount}根")
            }
        }
        return handledCount > 0
    }

    private fun positionBigFish(bizNo: String): JSONObject? {
        val special = JSONObject(AntFishPondRpcCall.fishpondAngleRodPositioning(bizNo, AREA_SPECIAL_BIG))
        if (isRpcSuccess(special)) {
            Log.fishpond("福利鱼定位命中[$AREA_SPECIAL_BIG]")
            return special
        }

        Log.fishpond("福利鱼定位[$AREA_SPECIAL_BIG]失败：${formatFailure(special)}，尝试[$AREA_SUPER_BIG]")
        val fallback = JSONObject(AntFishPondRpcCall.fishpondAngleRodPositioning(bizNo, AREA_SUPER_BIG))
        if (isRpcSuccess(fallback)) {
            Log.fishpond("福利鱼定位命中[$AREA_SUPER_BIG]")
            return fallback
        }

        Log.fishpond("福利鱼定位[$AREA_SUPER_BIG]失败：${formatFailure(fallback)}")
        return null
    }

    private fun syncAfterFish(): JSONObject? {
        val response = AntFishPondRpcCall.fishpondSyncIndex(FISH_ACTIVITY_SYNC_TYPES)
        if (response.isBlank()) {
            Log.runtime(TAG, "fishpondSyncIndex返回空")
            return null
        }
        val jo = JSONObject(response)
        if (!isRpcSuccess(jo)) {
            Log.fishpond("钓鱼后刷新失败：${formatFailure(jo)}")
            return null
        }
        return jo
    }

    private fun loadRiskToken(): String {
        val userId = UserMap.currentUid
        val vipData = IdMapManager.getInstance(VipDataIdMap::class.java)
        vipData.load(userId)
        return vipData[VIP_RISK_TOKEN_KEY].orEmpty()
    }

    private fun logFishProgress(jo: JSONObject) {
        val payload = payloadOf(jo)
        val fishAsset =
            payload
                .optJSONObject("roundInfo")
                ?.optJSONObject("fishAssetInfo")
                ?: return
        val current = fishAsset.optString("currentFishWeight")
        val target = fishAsset.optString("targetFishWeight")
        val diff = fishAsset.optString("diffFishWeight")
        val rodCount = extractRodCount(jo)
        val snapshot = FishProgressSnapshot(current, target, diff, rodCount)
        if (lastLoggedFishProgress == snapshot) {
            return
        }
        lastLoggedFishProgress = snapshot
        Log.fishpond("鱼池进度：当前${current}斤 / 目标${target}斤，还差${diff}斤，钓竿${rodCount}根")
    }

    private fun isFishRoundTargetReached(jo: JSONObject): Boolean {
        val fishAsset =
            payloadOf(jo)
                .optJSONObject("roundInfo")
                ?.optJSONObject("fishAssetInfo")
                ?: return false
        val remainingWeight = fishAsset.optString("diffFishWeight").toDoubleOrNull()
        if (remainingWeight != null) {
            return remainingWeight <= 0
        }
        val currentWeight = fishAsset.optString("currentFishWeight").toDoubleOrNull()
        val targetWeight = fishAsset.optString("targetFishWeight").toDoubleOrNull()
        return currentWeight != null && targetWeight != null && currentWeight >= targetWeight
    }

    private fun canExchange(jo: JSONObject): Boolean {
        val payload = payloadOf(jo)
        return payload.optBoolean("canExchange", false) ||
            payload.optJSONObject("roundInfo")?.optBoolean("canExchange", false) == true ||
            payload.optJSONObject("angleResultInfo")?.optBoolean("canExchange", false) == true ||
            payload.optJSONObject("fishResultInfo")?.optBoolean("canExchange", false) == true
    }

    private fun exchangeRewardAndReloadIndex(
        jo: JSONObject,
        sourceLabel: String,
    ): JSONObject? {
        if (rewardExchangeStoppedForCurrentRun || !canExchange(jo)) {
            return jo
        }
        val response = AntFishPondRpcCall.fishpondExchangeReward()
        val exchange = parseObject(response)
        if (exchange == null) {
            Log.fishpond("鱼池红包兑换失败：返回空或无法解析，停止当前链路 source=$sourceLabel")
            return null
        }
        if (exchange.optBoolean("success", true) == false && exchange.optString("resultCode") == "C15") {
            rewardExchangeStoppedForCurrentRun = true
            Log.fishpond("鱼池红包兑换被服务端拒绝，本轮停止兑换并保留后续流程：${formatFailure(exchange)} source=$sourceLabel")
            return queryIndex(logProgress = true) ?: jo
        }
        val failureType = classifyFishPondTaskFailure(exchange)
        when {
            isRpcSuccess(exchange) -> {
                logExchangeRewardSuccess(exchange)
            }

            failureType == TaskRpcFailureType.TERMINAL_DONE -> {
                Log.fishpond("鱼池红包兑换已处理，回查首页：${formatFailure(exchange)}")
            }

            else -> {
                Log.fishpond("鱼池红包兑换失败，停止当前链路：${formatFailure(exchange)} source=$sourceLabel")
                return null
            }
        }
        GlobalThreadPools.sleepCompat(SHORT_INTERVAL_MS)
        val refreshedIndex = queryIndex(logProgress = true)
        if (refreshedIndex == null) {
            Log.fishpond("鱼池红包兑换后刷新首页失败，停止当前链路 source=$sourceLabel")
            return null
        }
        return refreshedIndex
    }

    private fun handleFishActivityRewardRefresh(triggerResponse: JSONObject) {
        val adInfo = extractFishActivityResultAdInfo(triggerResponse)
        if (adInfo == null) {
            refreshFishActivityState()
            return
        }
        val item = buildFishActivityResultAdItem(adInfo)
        val adBizNo = taskAdBizNo(adInfo)
        if (item == null || adBizNo.isBlank()) {
            refreshFishActivityState()
            return
        }
        val result =
            completeFishPondAdTask(
                item = item,
                adBizNo = adBizNo,
                syncTypeList = FISH_ACTIVITY_RESULT_AD_SYNC_TYPES,
                successMessage = "钓鱼活动额外钓竿🎣领取成功",
                terminalMessage = "钓鱼活动额外钓竿🎣已完成，刷新状态",
                syncVerifier = { sync ->
                    payloadOf(sync).optJSONObject("lastAdInfo")?.optBoolean("complete", false) == true
                },
                syncFailureMessage = "钓鱼活动额外广告回查未见lastAdInfo.complete=true",
            )
        if (!result.success) {
            val code = result.code.ifBlank { "UNKNOWN" }
            val message = result.message.ifBlank { result.raw.ifBlank { "未知失败" } }
            Log.fishpond("钓鱼活动额外钓竿领取失败：code=$code msg=$message")
            refreshFishActivityState()
        }
    }

    private fun refreshFishActivityState() {
        AntFishPondRpcCall.fishpondSyncIndex(FISH_ACTIVITY_SYNC_TYPES)
    }

    private fun extractFishActivityResultAdInfo(triggerResponse: JSONObject): JSONObject? {
        val payload = payloadOf(triggerResponse)
        val triggerPayload =
            payload.optJSONObject("triggerSubplotsActivity")
                ?: parseObject(payload.optString("triggerSubplotsActivity"))
                ?: payload
        val extend = triggerPayload.optJSONObject("extend") ?: parseObject(triggerPayload.optString("extend"))
        val adInfo = extend?.optJSONObject("adInfo") ?: parseObject(extend?.optString("adInfo").orEmpty())
        if (adInfo?.optString("taskId") != TASK_FISH_ACTIVITY_RESULT_AD) {
            return null
        }
        return adInfo
    }

    private fun buildFishActivityResultAdItem(adInfo: JSONObject): TaskFlowItem? {
        val taskType = adInfo.optString("taskId").trim()
        val sceneCode = adInfo.optString("sceneCode").trim()
        if (taskType.isBlank() || sceneCode.isBlank()) {
            return null
        }
        return TaskFlowItem(
            id = taskType,
            title = "钓鱼活动额外钓竿",
            status = STATUS_TODO,
            type = taskType,
            sceneCode = sceneCode,
            actionType = ACTION_VISIT,
            raw = adInfo,
        )
    }

    private fun logExchangeRewardSuccess(exchange: JSONObject) {
        val reward = payloadOf(exchange).optJSONObject("exchangeRewardResult")
        val title = reward?.optString("title").orEmpty()
        val targetRewardCount = reward?.optString("targetRewardCount").orEmpty()
        val suffix =
            buildString {
                if (title.isNotBlank()) {
                    append(title)
                }
                if (targetRewardCount.isNotBlank()) {
                    if (isNotEmpty()) {
                        append(' ')
                    }
                    append("x")
                    append(targetRewardCount)
                }
            }
        if (suffix.isNotBlank()) {
            Log.fishpond("鱼池红包兑换🧧成功[$suffix]")
        } else {
            Log.fishpond("鱼池红包兑换🧧成功")
        }
    }

    private fun logAngleResult(jo: JSONObject) {
        val angleInfo = angleInfoOf(jo)
        val fishType = angleInfo.optString("fishType", "UNKNOWN")
        val fishName = angleInfo.optString("fishName").ifBlank { fishType }
        val fishWeight =
            angleInfo.optString("fishWeight").ifBlank {
                payloadOf(jo).optString("fishWeight")
            }
        val rodCount = extractRodCount(jo)
        val rodText = if (rodCount >= 0) "，剩余钓竿${rodCount}根" else ""
        Log.fishpond("钓鱼🎣[$fishName/$fishType]#${fishWeight}斤$rodText")
    }

    private fun extractRodCount(jo: JSONObject): Int {
        val payload = payloadOf(jo)
        if (payload.has("rodSumCount")) {
            return payload.optInt("rodSumCount", 0)
        }
        val rodList = payload.optJSONArray("rodAssetInfoList") ?: return -1
        var sum = 0
        for (i in 0 until rodList.length()) {
            sum += rodList.optJSONObject(i)?.optInt("rodCount", 0) ?: 0
        }
        return sum
    }

    private fun angleInfoOf(jo: JSONObject): JSONObject {
        val payload = payloadOf(jo)
        return payload.optJSONObject("angleResultInfo")
            ?: payload.optJSONObject("fishResultInfo")
            ?: payload
    }

    private fun payloadOf(jo: JSONObject): JSONObject = jo.optJSONObject("data") ?: jo

    private fun parseObject(raw: String): JSONObject? {
        if (raw.isBlank()) {
            return null
        }
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun taskTitle(task: JSONObject): String =
        task
            .optJSONObject("taskDisplayConfig")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: task
                .optString("taskTitle")
                .ifBlank { task.optString("title") }
                .ifBlank { task.optString("taskId") }

    private fun taskAdBizNo(task: JSONObject?): String = task?.optString("adBizNo")?.trim().orEmpty()

    private fun taskHasDirectAdBizNo(task: JSONObject?): Boolean = taskAdBizNo(task).isNotBlank()

    private fun buildFishPondAwardKey(item: TaskFlowItem): String =
        "${item.sceneCode}|${item.type}|${item.progress.ifBlank { item.status.ifBlank { "NO_PROGRESS" } }}"

    private fun buildFishPondVisitKey(item: TaskFlowItem): String {
        val task = item.raw
        val adBizNo = taskAdBizNo(task).ifBlank { "NO_AD" }
        val rightsTimes = task?.optInt("rightsTimes", 0) ?: 0
        return "${item.sceneCode}|${item.type}|$adBizNo|$rightsTimes|${item.progress.ifBlank { item.status.ifBlank { "NO_PROGRESS" } }}"
    }

    private fun buildFishPondTaskProgress(task: JSONObject): String {
        val parts = mutableListOf<String>()
        val taskRequire = task.optInt("taskRequire", 0)
        if (taskRequire > 0) {
            parts.add("task=${task.optInt("taskProgress", 0)}/$taskRequire")
        }
        val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
        if (rightsTimesLimit > 0) {
            parts.add("rights=${task.optInt("rightsTimes", 0)}/$rightsTimesLimit")
        }
        return parts.joinToString("|").ifBlank {
            "status=${task.optString("taskStatus").ifBlank { "UNKNOWN" }}"
        }
    }

    private fun fishPondTaskActionDetail(
        item: TaskFlowItem,
        action: String,
    ): String =
        "taskType=${item.type.ifBlank { item.id }} sceneCode=${item.sceneCode} " +
            "actionType=${item.actionType.ifBlank { "UNKNOWN" }} status=${item.status.ifBlank { "UNKNOWN" }} " +
            "action=$action progress=${item.progress.ifBlank { "UNKNOWN" }}"

    private fun emptyFishPondTaskResponse(
        rpc: String,
        item: TaskFlowItem,
        action: String,
        raw: String,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "${action}返回空或无法解析",
            rpc = rpc,
            raw = raw,
            detail = fishPondTaskActionDetail(item, action),
            stopCurrentRound = true,
        )

    private fun fishPondTaskActionFailureResult(
        response: JSONObject,
        rpc: String,
        item: TaskFlowItem,
        action: String,
    ): TaskFlowActionResult {
        val failureType = classifyFishPondTaskFailure(response)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = extractFishPondTaskFailureCode(response),
            message = extractFishPondTaskFailureMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = fishPondTaskActionDetail(item, action),
            stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC,
        )
    }

    private fun isRpcSuccess(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode")
        return jo.optBoolean("success") ||
            jo.optBoolean("isSuccess") ||
            resultCode == "100" ||
            resultCode.equals("SUCCESS", ignoreCase = true)
    }

    private fun formatFailure(jo: JSONObject): String {
        val code = extractFishPondTaskFailureCode(jo).ifBlank { "UNKNOWN" }
        val desc = extractFishPondTaskFailureMessage(jo).ifBlank { jo.toString() }
        return "code=$code msg=$desc"
    }

    private fun classifyFishPondTaskFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractFishPondTaskFailureCode(response)
        return when {
            code in FISHPOND_TERMINAL_TASK_CODES -> {
                TaskRpcFailureType.TERMINAL_DONE
            }

            code in FISHPOND_BUSINESS_LIMIT_CODES -> {
                TaskRpcFailureType.BUSINESS_LIMIT
            }

            code == "400000040" -> {
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
            }

            code in FISHPOND_NON_RETRYABLE_INVALID_CODES -> {
                TaskRpcFailureType.NON_RETRYABLE_INVALID
            }

            code in FISHPOND_RETRYABLE_TASK_CODES ||
                isFishPondFailureMarkedRetryable(response) -> {
                TaskRpcFailureType.RETRYABLE_RPC
            }

            else -> {
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }
    }

    private fun extractFishPondTaskFailureCode(response: JSONObject): String {
        val payload = response.optJSONObject("data")
        return response
            .optString("code")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("errCode") }
            .ifBlank { response.optString("resultCode") }
            .ifBlank { payload?.optString("code").orEmpty() }
            .ifBlank { payload?.optString("errorCode").orEmpty() }
            .ifBlank { payload?.optString("resultCode").orEmpty() }
    }

    private fun extractFishPondTaskFailureMessage(response: JSONObject): String {
        val payload = response.optJSONObject("data")
        return response
            .optString("desc")
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("message") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("memo") }
            .ifBlank { payload?.optString("desc").orEmpty() }
            .ifBlank { payload?.optString("errorMsg").orEmpty() }
            .ifBlank { payload?.optString("message").orEmpty() }
            .ifBlank { payload?.optString("resultDesc").orEmpty() }
            .ifBlank { response.toString() }
    }

    private fun isFishPondFailureMarkedRetryable(response: JSONObject): Boolean =
        listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }

    companion object {
        private val TAG = AntFishPond::class.java.simpleName
        private const val TASK_BLACKLIST_MODULE = "福气鱼池"
        private const val VIP_RISK_TOKEN_KEY = "antfishpond_riskToken"
        private const val TASK_SCENE = "ANTFISHPOND_TASK"
        private const val TASK_GO_FISH = "FISH_TASK_14"
        private const val TASK_JINDOU_TREASURE = "NORMAL_JINDOUDUOBAONEW_TASK"
        private const val DEFAULT_FISH_LIMIT = 30
        private const val SHORT_INTERVAL_MS = 500L
        private const val ACTIVITY_GIFT_BOX = "GIFT_BOX"
        private const val ACTIVITY_TOMORROW_ROD = "TOMORROW_ROD"
        private const val ACTIVITY_FISH = "FISH_ACTIVITY"
        private const val TASK_FISH_ACTIVITY_RESULT_AD = "FISH_ACTIVITY_RESULT_AD"
        private const val ACTION_RECEIVE_AWARD = "receiveAward"
        private const val ACTION_FINISH = "FINISH"
        private const val ACTION_VISIT = "VISIT"
        private const val ACTION_GO_FISH = "GOFISH"
        private const val STATUS_TODO = "TODO"
        private const val STATUS_FINISHED = "FINISHED"
        private const val FISH_TYPE_WELFARE = "WELFARE_FISH"
        private const val AREA_SPECIAL_BIG = "SPECIAL_BIG_ZONE"
        private const val AREA_SUPER_BIG = "SUPER_BIG_ZONE"

        private val FISH_ACTIVITY_SYNC_TYPES =
            listOf(
                "FISH_ACTIVITY",
                "TASK_DISPLAY",
                "TOMORROW_ROD",
            )
        private val VISIT_TASK_SYNC_TYPES =
            listOf(
                "FISH_ACTIVITY",
                "TASK_DISPLAY",
                "TOMORROW_ROD",
                "LOTTERY_PLUS",
            )
        private val FISH_ACTIVITY_RESULT_AD_SYNC_TYPES =
            listOf(
                "FISH_ACTIVITY",
                "TASK_DISPLAY",
                "TOMORROW_ROD",
                "LOTTERY_PLUS",
                "AD_INFO",
            )
        private val CLAIMABLE_STATUS = setOf("FINISHED", "RECEIVABLE", "TODO_RECEIVE")
        private val FISHPOND_TERMINAL_TASK_CODES =
            setOf(
                "400000030",
                "400000012",
                "RECEIVE_REWARD_REPEATED",
                "TASK_ALREADY_FINISHED",
                "TASK_HAS_FINISHED",
                "REPEAT_FINISH",
                "REPEAT_REWARD",
            )
        private val FISHPOND_BUSINESS_LIMIT_CODES =
            setOf(
                "CAMP_TRIGGER_ERROR",
                "PROMISE_TODAY_FINISH_TIMES_LIMIT",
            )
        private val FISHPOND_NON_RETRYABLE_INVALID_CODES =
            setOf(
                "20020012",
                "TASK_ID_INVALID",
                "ILLEGAL_ARGUMENT",
                "PROMISE_TEMPLATE_NOT_EXIST",
            )
        private val FISHPOND_RETRYABLE_TASK_CODES =
            setOf(
                "3000",
                "400000004",
                "REMOTE_INVOKE_EXCEPTION",
                "OP_REPEAT_CHECK",
                "SYSTEM_BUSY",
                "NETWORK_ERROR",
                "USER_FREQUENTLY_LOCK",
                "I07",
            )
    }
}
