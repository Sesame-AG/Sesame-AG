package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.NonDurationGameTask
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object FarmGame {
    private const val TAG = "FarmGame"
    private const val LEYUAN_DAILY_TASK_SCENE_CODE = "ANTFARM_LEYUAN_DAILY_TASK"
    private const val LEYUAN_OPEN_BOX_TASK_TYPE = "2026cc_GAME_ljkbx"
    private const val LEYUAN_OPEN_BOX_TARGET_COUNT = 10

    private fun isDrawQuotaExhausted(message: String): Boolean =
        message.contains("抽奖次数不足") ||
            message.contains("无可用抽奖次数") ||
            message.contains("暂无抽奖次数")

    enum class GameType {
        flyGame,
        hitGame,
        starGame,
        jumpGame,
        ;

        fun gameName(): String =
            when (this) {
                flyGame -> "飞行赛"
                hitGame -> "欢乐揍小鸡"
                starGame -> "星星球"
                jumpGame -> "登山赛"
            }
    }

    private enum class FarmGameCompletion {
        CONFIRMED_TERMINAL,
        BUSINESS_LIMIT,
        UNCONFIRMED,
    }

    private enum class GameTaskHandlingResult {
        NO_PENDING_TASK,
        CONFIRMED_PROGRESS,
        UNCONFIRMED,
    }

    private data class FarmGameSnapshot(
        val remainingGameCount: Int?,
        val level3Get: Boolean?,
    )

    /**
     * 外部入口：处理游戏改分逻辑
     */
    suspend fun run(antFarm: AntFarm) {
        if (Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)) {
            Log.farm("今日庄园游戏改分已完成")
            return
        }

        if (antFarm.recordFarmGame?.value != true || ApplicationHookConstants.isOffline()) return
        antFarm.syncAnimalStatus(antFarm.ownerFarmId)
        playAllFarmGames()
    }

    suspend fun playAllFarmGames() {
        val results = listOf(
            GameType.flyGame,
            GameType.hitGame,
            GameType.starGame,
            GameType.jumpGame,
        ).map { gameType ->
            if (ApplicationHookConstants.isOffline()) return
            recordFarmGame(gameType)
        }
        if (FarmGameCompletion.UNCONFIRMED in results) {
            Log.error(TAG, "庄园游戏本轮未形成完整确认状态，保留下一轮重试")
            return
        }
        if (FarmGameCompletion.BUSINESS_LIMIT in results) {
            Log.farm("庄园游戏受饲料容量限制，等待正常消费后继续")
            return
        }
        Status.setFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)
        Log.farm("今日庄园游戏改分已完成")
    }

    private suspend fun recordFarmGame(gameType: GameType): FarmGameCompletion {
        try {
            if (gameType == GameType.starGame || gameType == GameType.jumpGame) {
                return recordLevelAwardGameOnce(gameType)
            }
            var taskHandling: GameTaskHandlingResult? = null
            while (true) {
                val beforeSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
                if (beforeSnapshot.level3Get == true) {
                    Log.farm("[${gameType.gameName()}]#今日奖励已领满")
                    return if (taskHandling == GameTaskHandlingResult.UNCONFIRMED) FarmGameCompletion.UNCONFIRMED
                        else FarmGameCompletion.CONFIRMED_TERMINAL
                }
                if (beforeSnapshot.level3Get == null) {
                    Log.farm("庄园游戏[${gameType.gameName()}]缺少gameAward.level3Get，保留下一轮重试")
                    return FarmGameCompletion.UNCONFIRMED
                }

                val remainingCount = beforeSnapshot.remainingGameCount
                if (remainingCount == null || remainingCount < 0) {
                    Log.farm("庄园游戏[${gameType.gameName()}]缺少可确认的剩余次数，保留下一轮重试")
                    return FarmGameCompletion.UNCONFIRMED
                }
                if (remainingCount > 0) {
                    if (ApplicationHookConstants.isOffline()) return FarmGameCompletion.UNCONFIRMED
                    // 飞行赛的请求分数为4500..7450，按score / 50发放饲料。
                    if (gameType == GameType.flyGame && AntFarm.instance?.prepareFarmAwardCapacity(149) != true) {
                        Log.farm("飞行赛待办：不足149g单局空间，其他小游戏继续执行")
                        return FarmGameCompletion.BUSINESS_LIMIT
                    }
                    val recordResult = AntFarmRpcCall.recordFarmGame(gameType.name)
                    val joRecord = JSONObject(recordResult)
                    if (!ResChecker.checkRes(TAG, joRecord)) {
                        Log.farm("庄园游戏提交失败: $joRecord")
                        return FarmGameCompletion.UNCONFIRMED
                    }
                    AntFarm.instance?.let { it.syncAnimalStatus(it.ownerFarmId) }
                    val awardStr = parseGameAward(joRecord)
                    Log.farm("庄园游戏🎮[${gameType.gameName()}]#$awardStr")

                    val afterSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
                    if (!hasConfirmedGameProgress(beforeSnapshot, afterSnapshot)) {
                        Log.farm("庄园游戏[${gameType.gameName()}]提交 ACK 但状态未推进，当前轮不再重复提交")
                        return FarmGameCompletion.UNCONFIRMED
                    }
                    continue
                }

                if (taskHandling != null) {
                    return if (taskHandling == GameTaskHandlingResult.UNCONFIRMED) FarmGameCompletion.UNCONFIRMED
                        else FarmGameCompletion.CONFIRMED_TERMINAL
                }
                taskHandling = handleGameTasks(gameType)
                if (ApplicationHookConstants.isOffline()) return FarmGameCompletion.UNCONFIRMED
                val afterTasks = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
                if ((afterTasks.remainingGameCount ?: -1) > 0) continue
                if (afterTasks.remainingGameCount == null || afterTasks.remainingGameCount < 0 ||
                    taskHandling == GameTaskHandlingResult.UNCONFIRMED
                ) return FarmGameCompletion.UNCONFIRMED
                return FarmGameCompletion.CONFIRMED_TERMINAL
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.farm("recordFarmGame 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "recordFarmGame err:", t)
            return FarmGameCompletion.UNCONFIRMED
        }
    }

    private suspend fun recordLevelAwardGameOnce(gameType: GameType): FarmGameCompletion {
        val beforeSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
        when (beforeSnapshot.level3Get) {
            true -> {
                Log.farm("[${gameType.gameName()}]#今日奖励已领满")
                return FarmGameCompletion.CONFIRMED_TERMINAL
            }

            null -> {
                Log.farm("庄园游戏[${gameType.gameName()}]缺少gameAward.level3Get，保留下一轮重试")
                return FarmGameCompletion.UNCONFIRMED
            }

            false -> Unit
        }

        val recordResponse = JSONObject(AntFarmRpcCall.recordFarmGame(gameType.name))
        if (!ResChecker.checkRes(TAG, recordResponse)) {
            Log.farm("庄园游戏提交失败: $recordResponse")
            return FarmGameCompletion.UNCONFIRMED
        }
        val award = parseGameAward(recordResponse)
        Log.farm("庄园游戏🎮[${gameType.gameName()}]#$award")

        val afterSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
        return when (afterSnapshot.level3Get) {
            true -> {
                Log.farm("[${gameType.gameName()}]#今日奖励已领满")
                FarmGameCompletion.CONFIRMED_TERMINAL
            }

            null -> {
                Log.farm("庄园游戏[${gameType.gameName()}]提交后回查缺少gameAward.level3Get，当前轮不再重复提交")
                FarmGameCompletion.UNCONFIRMED
            }

            false -> {
                Log.farm("庄园游戏[${gameType.gameName()}]提交 ACK 但level3Get未推进，当前轮不再重复提交")
                FarmGameCompletion.UNCONFIRMED
            }
        }
    }

    private fun queryFarmGameSnapshot(gameType: GameType): FarmGameSnapshot? {
        return try {
            val initJo = JSONObject(AntFarmRpcCall.initFarmGame(gameType.name))
            if (!ResChecker.checkRes(TAG, initJo)) {
                Log.farm("庄园游戏[${gameType.gameName()}]初始化状态查询失败: $initJo")
                null
            } else {
                val gameAward = initJo.optJSONObject("gameAward")
                val level3Get =
                    gameAward
                        ?.takeIf { it.has("level3Get") && !it.isNull("level3Get") }
                        ?.optBoolean("level3Get")
                val remainingGameCount =
                    initJo
                        .takeIf { it.has("remainingGameCount") && !it.isNull("remainingGameCount") }
                        ?.optInt("remainingGameCount")
                FarmGameSnapshot(remainingGameCount, level3Get)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "查询庄园游戏状态失败:", t)
            null
        }
    }

    private fun hasConfirmedGameProgress(
        before: FarmGameSnapshot,
        after: FarmGameSnapshot,
    ): Boolean =
        (before.remainingGameCount != null &&
            after.remainingGameCount != null &&
            after.remainingGameCount < before.remainingGameCount) ||
            (before.level3Get == false && after.level3Get == true)

    private fun parseGameAward(jo: JSONObject): String {
        val award = StringBuilder()
        jo.optJSONArray("awardInfos")?.let { ja ->
            for (i in 0 until ja.length()) {
                val info = ja.getJSONObject(i)
                if (award.isNotEmpty()) award.append(",")
                award.append(info.optString("awardName")).append("*").append(info.optInt("awardCount"))
            }
        }
        val foodCount = jo.optString("receiveFoodCount", "")
        if (foodCount.isNotEmpty()) {
            if (award.isNotEmpty()) award.append(";")
            award.append("饲料*").append(foodCount)
        }
        return award.toString()
    }

    private suspend fun handleGameTasks(gameType: GameType): GameTaskHandlingResult {
        if (gameType != GameType.flyGame && gameType != GameType.hitGame) {
            return GameTaskHandlingResult.NO_PENDING_TASK
        }
        val result = TaskFlowEngine(object : TaskFlowAdapter {
            override val moduleName = "蚂蚁庄园"
            override val flowName = "庄园游戏[${gameType.gameName()}]"
            override fun query(): JSONObject = JSONObject().apply {
                val tasks = loadGameTasks(gameType)
                put("success", tasks != null)
                if (tasks != null) put("farmTaskList", tasks)
            }
            override fun isQuerySuccess(response: JSONObject) = response.optBoolean("success")
            override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                val tasks = response.getJSONArray("farmTaskList")
                return (0 until tasks.length()).map { index ->
                    val task = tasks.getJSONObject(index)
                    TaskFlowItem(
                        id = task.optString("taskId"), title = task.optString("title"),
                        status = task.optString("taskStatus"), type = task.optString("bizKey"), raw = task,
                        current = task.optInt("rightsTimes"), limit = task.optInt("rightsTimesLimit", 1),
                        progress = "${task.optInt("alreadyReceiveStageAwardCount")}:${task.optInt("awardCount")}",
                    )
                }
            }
            override fun mapPhase(item: TaskFlowItem) = when {
                item.id.isBlank() -> TaskFlowPhase.UNKNOWN
                item.status == "FINISHED" -> TaskFlowPhase.REWARD_READY
                item.status == "RECEIVED" -> TaskFlowPhase.TERMINAL
                item.status == "TODO" && item.type.isNotBlank() -> TaskFlowPhase.READY_TO_COMPLETE
                else -> TaskFlowPhase.UNKNOWN
            }
            override fun complete(item: TaskFlowItem): TaskFlowActionResult {
                val response = JSONObject(AntFarmRpcCall.finishTask(
                    item.type, "ANTFARM_GAME_TIMES_TASK", "${item.type}_${System.currentTimeMillis()}",
                ))
                if (ResChecker.checkRes(TAG, response)) return TaskFlowActionResult.success()
                return TaskFlowActionResult.failure(
                    AntFarm.instance?.classifyFarmRpcFailure(response) ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = response.optString("resultCode"), message = response.optString("memo"),
                    raw = response.toString(), rpc = "finishTask", continueCurrentRoundOnFailure = true,
                )
            }
            override fun receive(item: TaskFlowItem): TaskFlowActionResult {
                val task = item.raw!!
                val awardType = task.optString("awardType")
                if (awardType == "ALLPURPOSE" &&
                    AntFarm.instance?.prepareFarmAwardCapacity(task.optInt("awardCount")) != true
                ) return TaskFlowActionResult.defer(DeferredReason.CAPACITY_LIMIT, message = "饲料容量不足")
                val response = JSONObject(AntFarmRpcCall.receiveFarmTaskAward(item.id, awardType))
                if (ResChecker.checkRes(TAG, response)) return TaskFlowActionResult.success()
                return TaskFlowActionResult.failure(
                    AntFarm.instance?.classifyFarmRpcFailure(response) ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = response.optString("resultCode"), message = response.optString("memo"),
                    raw = response.toString(), rpc = "receiveFarmTaskAward", continueCurrentRoundOnFailure = true,
                ).copy(refreshAfterAction = true)
            }
            override fun logInfo(message: String) = Log.farm(message)
            override fun logError(message: String) = Log.error(TAG, message)
        }, roundSleepMs = 255L).run()
        return when {
            !result.completed -> GameTaskHandlingResult.UNCONFIRMED
            result.progressChanged -> GameTaskHandlingResult.CONFIRMED_PROGRESS
            else -> GameTaskHandlingResult.NO_PENDING_TASK
        }
    }

    private fun loadGameTasks(gameType: GameType): JSONArray? {
        return try {
            val listResponse =
                when (gameType) {
                    GameType.flyGame -> AntFarmRpcCall.FlyGameListFarmTask()
                    GameType.hitGame -> AntFarmRpcCall.HitGameListFarmTask()
                    else -> return null
                }
            if (listResponse.isEmpty()) {
                Log.farm("庄园游戏[${gameType.gameName()}]任务列表响应为空")
                return null
            }
            val listJo = JSONObject(listResponse)
            if (!ResChecker.checkRes(TAG, listJo)) {
                Log.farm("庄园游戏[${gameType.gameName()}]任务列表查询失败: $listJo")
                return null
            }
            listJo.optJSONArray("farmTaskList") ?: run {
                Log.farm("庄园游戏[${gameType.gameName()}]任务列表缺少 farmTaskList")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "查询庄园游戏任务列表失败:", t)
            null
        }
    }

    internal suspend fun drawGameCenterAward() {
        val session = AccountSessionCoordinator.currentSession() ?: return
        val identity = AccountSessionIdentity(session.userId, session.sessionEpoch)
        AccountSessionCoordinator.withCurrentSession(identity) {
            UserDataStoreManager.getInstance(session.userId)?.remove("farmGameCenterAttempts")
        }
        val day = System.currentTimeMillis()
        val module = "蚂蚁庄园"
        val drawKey = "gameCenter:draw"
        val failures = mutableSetOf<String>()
        var pending: Pair<GameCenterPlayRpcCall.DeliveryBenefitCandidate, TaskFlowActionResult>? = null
        var beforeGameQuota = -1
        var totalParadiseCoins = 0 // 🚀 统计总共获得的乐园币
        val attemptedCandidates = mutableSetOf<String>()
        var drawBlocked = drawKey in TaskBlacklist.getBlacklist(module)
        var previousDrawRights: Int? = null
        try {
            runCatching {
                val warmup = JSONObject(AntFarmRpcCall.refinedOperation("ENTERSELFWITHOUTPOP"))
                if (!warmup.optBoolean("success", false) && warmup.optString("resultCode") != "100") {
                    Log.farm("庄园游戏中心预热失败，继续尝试查询游戏列表")
                }
            }
            while (true) {
                if (ApplicationHookConstants.isOffline() ||
                    !AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) ||
                    !TimeUtil.isSameDay(day, System.currentTimeMillis())) break
                val response = AntFarmRpcCall.queryGameList()
                val responseJo = JSONObject(response)
                val jo = responseJo.optJSONObject("resData") ?: responseJo

                if (!jo.optBoolean("success", responseJo.optBoolean("success"))) {
                    Log.error(TAG, "庄园乐园列表查询失败: $responseJo")
                    failures += "query"
                    break
                }

                val currentRights =
                    findFirstObjectByKey(jo, "gameCenterDrawRights")
                        ?: findFirstObjectByKey(jo, "gameDrawAwardActivity")
                        ?: findFirstObjectByKey(jo, "gameEntryInfo")
                if (currentRights == null) {
                    Log.farm("未找到开宝箱权益，继续独立游戏")
                }

                // Draw at most one server-bounded batch, then re-read authoritative rights.
                val quotaCanUse =
                    currentRights?.optInt(
                        "quotaCanUse",
                        currentRights.optInt("canUseTimes", currentRights.optInt("drawRightsTimes", -1)),
                    ) ?: -1
                if (previousDrawRights != null && (quotaCanUse < 0 || quotaCanUse >= previousDrawRights)) {
                    drawBlocked = true
                    failures += drawKey
                    Log.error(TAG, "宝箱提交后权益未确认减少，今日停止开箱")
                    AccountSessionCoordinator.withCurrentSession(identity) {
                        TaskBlacklist.addToBlacklist(module, drawKey, "庄园乐园开宝箱")
                    }
                }
                previousDrawRights = null
                pending?.let { (candidate, result) ->
                    val current = GameCenterPlayRpcCall.collectDeliveryBenefitCandidates(jo).firstOrNull { it.key == candidate.key }
                    val confirmed = result.progressChanged || beforeGameQuota >= 0 && quotaCanUse > beforeGameQuota ||
                        current != null && (current.rightTimes > candidate.rightTimes ||
                            current.taskStatus != candidate.taskStatus && current.taskStatus in setOf("FINISHED", "RECEIVED"))
                    if (!confirmed) {
                        val key = "gameCenter:${candidate.key}"
                        failures += key
                        Log.error(TAG, "庄园乐园动作回查未确认[taskId=${candidate.taskId}] raw=${result.raw}")
                        AccountSessionCoordinator.withCurrentSession(identity) {
                            TaskBlacklist.addToBlacklist(module, key, candidate.title)
                        }
                    }
                    pending = null
                }
                if (quotaCanUse > 0 && !drawBlocked) {
                    val batchDrawCount = quotaCanUse.coerceAtMost(10)
                    Log.farm("当前有 $quotaCanUse 个宝箱待开启，本次提交 $batchDrawCount 个")
                    val raw = try {
                        AntFarmRpcCall.drawGameCenterAward(batchDrawCount)
                    } catch (e: Exception) {
                        if (e is CancellationException || e is InterruptedException) throw e
                        Log.printStackTrace(TAG, "庄园乐园开宝箱调用失败", e)
                        ""
                    }
                    val drawResponse = runCatching { JSONObject(raw) }.getOrNull()
                    val drawRes = drawResponse?.optJSONObject("resData") ?: drawResponse
                    if (drawResponse != null && drawRes?.optBoolean("success", drawResponse.optBoolean("success")) == true) {
                        previousDrawRights = quotaCanUse
                        val accepted = requireNotNull(drawRes)
                        val awardList =
                            findFirstArrayByKey(accepted, "gameCenterDrawAwardList")
                                ?: findFirstArrayByKey(accepted, "drawAwardList")
                        val awardStrings = mutableListOf<String>()
                        if (awardList != null) {
                            for (i in 0 until awardList.length()) {
                                val item = awardList.getJSONObject(i)
                                val awardName = item.optString("awardName")
                                val awardCount = item.optInt("awardCount")
                                awardStrings.add("$awardName*$awardCount")
                                if (awardName.contains("乐园币")) {
                                    totalParadiseCoins += awardCount
                                }
                            }
                        }
                        Log.farm("庄园小鸡🎁[获得奖品: ${awardStrings.joinToString(",")}]")
                        continue
                    }
                    val desc = drawRes?.optString("desc").orEmpty()
                        .ifBlank { drawRes?.optString("resultDesc").orEmpty() }
                        .ifBlank { drawResponse?.optString("desc").orEmpty() }
                    if (isDrawQuotaExhausted(desc)) {
                        Log.farm("开宝箱权益已用完: $desc")
                    } else if (!ApplicationHookConstants.isOffline()) {
                        failures += drawKey
                        Log.error(TAG, "庄园乐园开宝箱失败 raw=$raw")
                        AccountSessionCoordinator.withCurrentSession(identity) {
                            TaskBlacklist.addToBlacklist(module, drawKey, "庄园乐园开宝箱")
                        }
                    }
                    drawBlocked = true
                }

                // Game rewards are independent from draw quota. A full draw quota only ends draw.
                val limit = currentRights?.optInt("quotaLimit", currentRights.optInt("limit")) ?: 0
                val used = currentRights?.optInt("usedQuota", currentRights.optInt("usedTimes")) ?: 0
                val remainingDraws = if (drawBlocked || quotaCanUse < 0) 0 else (limit - used - quotaCanUse).coerceAtLeast(0)
                val blacklist = TaskBlacklist.getBlacklist(module)
                val candidates = buildFarmGameCenterCandidates(jo, remainingDraws)
                    .filterNot { "gameCenter:${it.key}" in blacklist || "gameCenter:${it.key}" in failures }
                val action = advanceFarmGameCenterCandidate(candidates, attemptedCandidates, quotaCanUse) ?: break
                val (candidate, result) = action
                if (ApplicationHookConstants.isOffline()) break
                val key = "gameCenter:${candidate.key}"
                when {
                    result.failureType != null -> {
                        failures += key
                        Log.error(TAG, "庄园游戏执行失败[taskId=${candidate.taskId}] code=${result.code} ${result.message} raw=${result.raw}")
                        AccountSessionCoordinator.withCurrentSession(identity) {
                            TaskBlacklist.addToBlacklist(module, key, candidate.title)
                        }
                    }
                    result.deferredReason == DeferredReason.STATE_CONFIRMATION && result.submissionStarted != false -> {
                        pending = action
                        beforeGameQuota = quotaCanUse
                    }
                }
                if (result.stopCurrentRound && pending == null) break
            }
            if (!ApplicationHookConstants.isOffline()) receiveLeyuanLimitedBenefitAwards()
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (t: Throwable) {
            failures += "query"
            Log.printStackTrace(AntFarm.TAG, "drawGameCenterAward 流程异常", t)
        }
        if (!ApplicationHookConstants.isOffline() &&
            AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) &&
            TimeUtil.isSameDay(day, System.currentTimeMillis())
        ) {
            AccountSessionCoordinator.withCurrentSession(identity) {
                if (previousDrawRights != null) {
                    failures += drawKey
                    TaskBlacklist.addToBlacklist(module, drawKey, "庄园乐园开宝箱")
                }
                pending?.let { (candidate, _) ->
                    val key = "gameCenter:${candidate.key}"
                    failures += key
                    TaskBlacklist.addToBlacklist(module, key, candidate.title)
                }
            }
            if (failures.isNotEmpty()) {
                TaskExecutionReport.current()?.recordFlow(TaskFlowExecutionState(), module, "庄园乐园",
                    TaskFlowRunResult(false, false, false, 1, failureCount = failures.size))
            }
        }
        if (totalParadiseCoins > 0) {
            Log.farm("庄园小鸡🎁[本次任务总计获得乐园币: $totalParadiseCoins]")
        }
    }

    private fun buildFarmGameCenterCandidates(
        queryResponse: JSONObject,
        remainingDraws: Int,
    ): List<GameCenterPlayRpcCall.DeliveryBenefitCandidate> =
        GameCenterPlayRpcCall.collectDeliveryBenefitCandidates(queryResponse)
            .filter { candidate ->
                candidate.hasPendingReward ||
                    (remainingDraws > 0 && farmGameCenterDecision(candidate).action in setOf(
                        GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT,
                        GameCenterPlayRpcCall.TaskAction.DURATION_ONLY,
                    ))
            }

    private fun farmGameCenterDecision(
        candidate: GameCenterPlayRpcCall.DeliveryBenefitCandidate,
    ): GameCenterPlayRpcCall.TaskActionDecision =
        GameCenterPlayRpcCall.resolveTaskAction(candidate.rawBenefit, candidate.rawGame)

    private suspend fun advanceFarmGameCenterCandidate(
        candidates: List<GameCenterPlayRpcCall.DeliveryBenefitCandidate>,
        attemptedCandidates: MutableSet<String>,
        beforeQuota: Int,
    ): Pair<GameCenterPlayRpcCall.DeliveryBenefitCandidate, TaskFlowActionResult>? {
        val candidate = candidates.firstOrNull { item ->
            "${item.key}|${item.snapshotKey}" !in attemptedCandidates &&
                farmGameCenterDecision(item).action != GameCenterPlayRpcCall.TaskAction.DEFERRED
        } ?: return null
        attemptedCandidates += "${candidate.key}|${candidate.snapshotKey}"
        val decision = farmGameCenterDecision(candidate)
        Log.farm(
            "庄园乐园候选[${candidate.title}] " +
                GameCenterPlayRpcCall.auditDetail(
                    decision,
                    candidate.taskId,
                    candidate.appId,
                    "ANTFARM",
                    candidate.rightTimes,
                    candidate.rightTimesLimit,
                ),
        )
        val result = try {
            when (decision.action) {
                GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT -> {
                    NonDurationGameTask.advance(
                        requireNotNull(decision.mappedTask), candidate.rawBenefit, candidate.rawGame,
                        channel = GameTask.Farm_ddply.channel,
                        detail = "taskId=${candidate.taskId} sceneCode=ANTFARM", logger = Log::farm,
                        confirmProgress = {
                            val response = JSONObject(AntFarmRpcCall.queryGameList())
                            val data = response.optJSONObject("resData") ?: response
                            if (!data.optBoolean("success", response.optBoolean("success"))) {
                                Log.error(TAG, "庄园游戏上报后列表查询失败:$response")
                                null
                            } else {
                                val current = GameCenterPlayRpcCall.collectDeliveryBenefitCandidates(data)
                                    .firstOrNull { it.key == candidate.key }
                                val rights = findFirstObjectByKey(data, "gameCenterDrawRights")
                                    ?: findFirstObjectByKey(data, "gameDrawAwardActivity")
                                    ?: findFirstObjectByKey(data, "gameEntryInfo")
                                val quota = rights?.optInt("quotaCanUse",
                                    rights.optInt("canUseTimes", rights.optInt("drawRightsTimes", -1))) ?: -1
                                when {
                                    beforeQuota >= 0 && quota > beforeQuota -> true
                                    current == null -> {
                                        Log.error(TAG, "庄园游戏候选消失且权益未确认增加[${candidate.key}]")
                                        null
                                    }
                                    current.rightTimes > candidate.rightTimes ||
                                        current.taskStatus != candidate.taskStatus &&
                                        current.taskStatus in setOf("FINISHED", "RECEIVED") -> true
                                    else -> false
                                }
                            }
                        },
                    )
                }

                GameCenterPlayRpcCall.TaskAction.DURATION_ONLY -> {
                    val acknowledgement = GameCenterPlayRpcCall.submitForAck(requireNotNull(decision.contract))
                    if (!acknowledgement.accepted) {
                        Log.error(TAG, "庄园乐园时长上报失败 raw=${acknowledgement.raw}")
                        TaskFlowActionResult.failure(
                            acknowledgement.failureType, message = "庄园乐园时长上报失败",
                            raw = acknowledgement.raw, stopCurrentRound = ApplicationHookConstants.isOffline(),
                            continueCurrentRoundOnFailure = true,
                        )
                    } else TaskFlowActionResult.defer(DeferredReason.STATE_CONFIRMATION, refreshAfterAction = true)
                }

                GameCenterPlayRpcCall.TaskAction.DIRECT_FINISH,
                GameCenterPlayRpcCall.TaskAction.CLICK_THEN_DURATION,
                GameCenterPlayRpcCall.TaskAction.OWNER_BUSINESS,
                GameCenterPlayRpcCall.TaskAction.DEFERRED -> TaskFlowActionResult.defer(DeferredReason.PREREQUISITE_PENDING, decision.reason)
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            Log.printStackTrace(TAG, "庄园乐园任务调用失败 taskId=${candidate.taskId}", e)
            TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, message = e.message.orEmpty())
        }
        return candidate to result
    }

    private fun receiveLeyuanLimitedBenefitAwards() {
        try {
            TaskFlowEngine(object : TaskFlowAdapter {
                override val moduleName = "AntFarm"
                override val flowName = "小鸡乐园任务"
                override val continueCurrentRoundOnRetryableFailure = true

                override fun query() = JSONObject(AntFarmRpcCall.queryOptionalPlay())

                override fun isQuerySuccess(response: JSONObject): Boolean =
                    ResChecker.checkRes(TAG, response) &&
                        response.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList") != null

                override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                    val tasks = response.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList")
                        ?: return emptyList()
                    return (0 until tasks.length()).mapNotNull { index ->
                        val task = tasks.optJSONObject(index) ?: return@mapNotNull null
                        if (task.optString("sceneCode") != LEYUAN_DAILY_TASK_SCENE_CODE) return@mapNotNull null
                        val bizInfo = task.optJSONObject("bizInfo")
                        val taskType = task.optString("taskType")
                        TaskFlowItem(
                            id = taskType,
                            title = bizInfo?.optString("title")?.takeIf { it.isNotBlank() } ?: taskType,
                            status = task.optString("taskStatus"),
                            sceneCode = task.optString("sceneCode"),
                            actionType = bizInfo?.optString("actionType").orEmpty(),
                            raw = task,
                            current = task.optInt("rightsTimes", 0),
                            limit = task.optInt("rightsTimesLimit", 0).takeIf { it > 0 },
                            progress = "rights=${task.optInt("rightsTimes")}/${task.optInt("rightsTimesLimit")} received=${task.optInt("alreadyReceiveAwardCount")} total=${task.optInt("totalAwardCount")}",
                        )
                    }
                }

                override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when {
                    item.id.isBlank() -> TaskFlowPhase.UNKNOWN
                    else -> when (item.status) {
                        "FINISHED" -> TaskFlowPhase.REWARD_READY
                        "RECEIVED" -> if (item.actionType in setOf("VIEW", "COUNT_DOWN") && item.limit != null &&
                            (item.current ?: 0) < item.limit) TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.TERMINAL
                        "TODO" -> if (item.actionType in setOf("VIEW", "COUNT_DOWN")) TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.BUSINESS_ACTION
                        else -> TaskFlowPhase.UNKNOWN
                    }
                }

                override fun complete(item: TaskFlowItem): TaskFlowActionResult {
                    val response = JSONObject(AntFarmRpcCall.finishLeyuanTask(item.sceneCode, item.id))
                    if (ResChecker.checkRes(TAG, response)) {
                        Log.farm("小鸡乐园🧾[${item.title}]已提交，刷新任务领奖")
                        return TaskFlowActionResult.success()
                    }
                    return TaskFlowActionResult.failure(
                        failureType = if (response.optBoolean("retriable", response.optBoolean("canRetry", true)))
                            TaskRpcFailureType.RETRYABLE_RPC else TaskRpcFailureType.NON_RETRYABLE_INVALID,
                        code = response.optString("code"), message = response.optString("desc"),
                        raw = response.toString(), rpc = "com.alipay.antieptask.finishTaskantfarm",
                        continueCurrentRoundOnFailure = true,
                    )
                }

                override fun receive(item: TaskFlowItem): TaskFlowActionResult {
                    if (item.id == LEYUAN_OPEN_BOX_TASK_TYPE && !hasOpenedEnoughGameCenterBoxes()) {
                        return TaskFlowActionResult.defer(
                            DeferredReason.PREREQUISITE_PENDING,
                            message = "开箱数未确认达到${LEYUAN_OPEN_BOX_TARGET_COUNT}个，保留待领取",
                        )
                    }
                    val task = item.raw ?: JSONObject()
                    val unreceived = task.optInt("totalAwardCount") - task.optInt("alreadyReceiveAwardCount")
                    val awardCount = unreceived.takeIf { it > 0 }
                        ?: task.optInt("awardCount").takeIf { it > 0 }
                        ?: task.optInt("nextStageAwardCount").takeIf { it > 0 }
                        ?: return TaskFlowActionResult.failure(
                            TaskRpcFailureType.NON_RETRYABLE_INVALID, message = "缺少可领取奖励数量",
                        )
                    val response = JSONObject(AntFarmRpcCall.receiveTaskAwardAntFarm(item.sceneCode, item.id, awardCount))
                    if (ResChecker.checkRes(TAG, response)) {
                        Log.farm("小鸡乐园🎁[${item.title}]#${awardCount}乐园币")
                        return TaskFlowActionResult.success()
                    }
                    return TaskFlowActionResult.failure(
                        AntFarm.instance?.classifyFarmRpcFailure(response) ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = response.optString("resultCode").ifBlank { response.optString("code") },
                        message = response.optString("memo").ifBlank { response.optString("desc") },
                        raw = response.toString(), rpc = "com.alipay.antieptask.receiveTaskAwardantfarm",
                        continueCurrentRoundOnFailure = true,
                    ).copy(refreshAfterAction = true)
                }

                override fun logInfo(message: String) = Log.farm(message)
                override fun logError(message: String) = Log.error(TAG, message)
            }).run()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveLeyuanLimitedBenefitAwards err:", t)
        }
    }

    private fun hasOpenedEnoughGameCenterBoxes(): Boolean {
        val openedCount = queryGameCenterOpenedBoxCount()
        if (openedCount == null) {
            Log.farm("小鸡乐园限时福利[玩游戏累计开宝箱]无法确认已开箱数量，暂不领奖")
            return false
        }
        return openedCount >= LEYUAN_OPEN_BOX_TARGET_COUNT
    }

    private fun queryGameCenterOpenedBoxCount(): Int? {
        return try {
            val response = JSONObject(AntFarmRpcCall.queryGameList())
            val jo = response.optJSONObject("resData") ?: response
            if (!jo.optBoolean("success", response.optBoolean("success"))) {
                Log.farm("小鸡乐园开箱进度查询失败: $response")
                return null
            }
            val rights =
                findFirstObjectByKey(jo, "gameCenterDrawRights")
                    ?: findFirstObjectByKey(jo, "gameDrawAwardActivity")
                    ?: findFirstObjectByKey(jo, "gameEntryInfo")
                    ?: return null
            maxOf(
                rights.optInt("usedQuota", -1),
                rights.optInt("usedTimes", -1),
                rights.optInt("drawUsedTimes", -1),
                rights.optInt("totalUsedTimes", -1),
            ).takeIf { it >= 0 }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryGameCenterOpenedBoxCount err:", t)
            null
        }
    }

    internal fun findFirstObjectByKey(
        source: Any?,
        targetKey: String,
    ): JSONObject? {
        return when (source) {
            is JSONObject -> {
                source.optJSONObject(targetKey)?.let { return it }
                val keys = source.keys()
                while (keys.hasNext()) {
                    val child = source.opt(keys.next())
                    findFirstObjectByKey(child, targetKey)?.let { return it }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until source.length()) {
                    findFirstObjectByKey(source.opt(index), targetKey)?.let { return it }
                }
                null
            }

            else -> {
                null
            }
        }
    }

    internal fun findFirstArrayByKey(
        source: Any?,
        targetKey: String,
    ): JSONArray? {
        return when (source) {
            is JSONObject -> {
                source.optJSONArray(targetKey)?.let { return it }
                val keys = source.keys()
                while (keys.hasNext()) {
                    val child = source.opt(keys.next())
                    findFirstArrayByKey(child, targetKey)?.let { return it }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until source.length()) {
                    findFirstArrayByKey(source.opt(index), targetKey)?.let { return it }
                }
                null
            }

            else -> {
                null
            }
        }
    }
}
