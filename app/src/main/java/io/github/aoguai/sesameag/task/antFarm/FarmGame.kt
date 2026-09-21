package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
            while (true) {
                val beforeSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
                if (beforeSnapshot.level3Get == true) {
                    Log.farm("[${gameType.gameName()}]#今日奖励已领满")
                    return FarmGameCompletion.CONFIRMED_TERMINAL
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
                    delay(3000)

                    val afterSnapshot = queryFarmGameSnapshot(gameType) ?: return FarmGameCompletion.UNCONFIRMED
                    if (!hasConfirmedGameProgress(beforeSnapshot, afterSnapshot)) {
                        Log.farm("庄园游戏[${gameType.gameName()}]提交 ACK 但状态未推进，当前轮不再重复提交")
                        return FarmGameCompletion.UNCONFIRMED
                    }
                    continue
                }

                when (handleGameTasks(gameType)) {
                    GameTaskHandlingResult.NO_PENDING_TASK -> return FarmGameCompletion.CONFIRMED_TERMINAL
                    GameTaskHandlingResult.CONFIRMED_PROGRESS -> continue
                    GameTaskHandlingResult.UNCONFIRMED -> return FarmGameCompletion.UNCONFIRMED
                }
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
        delay(3000)

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
        val farmTaskList =
            when (gameType) {
                GameType.flyGame,
                GameType.hitGame -> loadGameTasks(gameType) ?: return GameTaskHandlingResult.UNCONFIRMED

                else -> return GameTaskHandlingResult.NO_PENDING_TASK
            }

        for (i in 0 until farmTaskList.length()) {
            val task = farmTaskList.optJSONObject(i) ?: run {
                Log.farm("庄园游戏[${gameType.gameName()}]任务列表包含无效任务项，保留下一轮重试")
                return GameTaskHandlingResult.UNCONFIRMED
            }
            val status = task.optString("taskStatus")
            val taskId = task.optString("taskId")
            val awardType = task.optString("awardType")
            when (status) {
                TaskStatus.RECEIVED.name -> continue

                TaskStatus.FINISHED.name -> {
                    if (taskId.isBlank()) {
                        Log.farm("庄园游戏[${gameType.gameName()}]待领奖任务缺少 taskId，保留下一轮重试")
                        return GameTaskHandlingResult.UNCONFIRMED
                    }
                    if (awardType == "ALLPURPOSE" &&
                        AntFarm.instance?.prepareFarmAwardCapacity(task.optInt("awardCount", 0)) != true
                    ) {
                        Log.farm("庄园游戏任务[$taskId]饲料容量不足，保留后续领取")
                        return GameTaskHandlingResult.UNCONFIRMED
                    }
                    val awardResponse = JSONObject(AntFarmRpcCall.receiveFarmTaskAward(taskId, awardType))
                    if (!ResChecker.checkRes(TAG, awardResponse)) {
                        Log.farm("庄园游戏任务[$taskId]领奖失败: $awardResponse")
                        return GameTaskHandlingResult.UNCONFIRMED
                    }
                    delay(3000)
                    val refreshedTask =
                        loadGameTasks(gameType)
                            ?.let { refreshedTaskList -> findGameTaskById(refreshedTaskList, taskId) }
                    if (refreshedTask?.optString("taskStatus") == TaskStatus.RECEIVED.name) {
                        return GameTaskHandlingResult.CONFIRMED_PROGRESS
                    }
                    Log.farm("庄园游戏任务[$taskId]领奖 ACK 后状态未确认，当前轮不再重复领取")
                    return GameTaskHandlingResult.UNCONFIRMED
                }

                TaskStatus.TODO.name -> {
                    val bizKey = task.optString("bizKey")
                    if (taskId.isBlank() || bizKey.isBlank()) {
                        Log.farm("庄园游戏[${gameType.gameName()}]待完成任务缺少 taskId 或 bizKey，保留下一轮重试")
                        return GameTaskHandlingResult.UNCONFIRMED
                    }
                    val outBizNo = "${bizKey}_${System.currentTimeMillis()}_${Integer.toHexString((Math.random() * 0xFFFFFF).toInt())}"
                    val finishResponse =
                        JSONObject(AntFarmRpcCall.finishTask(bizKey, "ANTFARM_GAME_TIMES_TASK", outBizNo))
                    if (!ResChecker.checkRes(TAG, finishResponse)) {
                        Log.farm("庄园游戏任务[$taskId]执行失败: $finishResponse")
                        return GameTaskHandlingResult.UNCONFIRMED
                    }
                    delay(3000)
                    val refreshedTask =
                        loadGameTasks(gameType)
                            ?.let { refreshedTaskList -> findGameTaskById(refreshedTaskList, taskId) }
                    if (refreshedTask != null && hasConfirmedGameTaskProgress(task, refreshedTask)) {
                        return GameTaskHandlingResult.CONFIRMED_PROGRESS
                    }
                    Log.farm("庄园游戏任务[$taskId]执行 ACK 但状态未推进，当前轮不再重复提交")
                    return GameTaskHandlingResult.UNCONFIRMED
                }

                else -> {
                    Log.farm("庄园游戏[${gameType.gameName()}]任务状态[$status]未确认，保留下一轮重试")
                    return GameTaskHandlingResult.UNCONFIRMED
                }
            }
        }
        return GameTaskHandlingResult.NO_PENDING_TASK
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

    private fun findGameTaskById(
        farmTaskList: JSONArray,
        taskId: String,
    ): JSONObject? {
        for (index in 0 until farmTaskList.length()) {
            val task = farmTaskList.optJSONObject(index) ?: return null
            if (task.optString("taskId") == taskId) {
                return task
            }
        }
        return null
    }

    private fun hasConfirmedGameTaskProgress(
        before: JSONObject,
        after: JSONObject,
    ): Boolean {
        when (after.optString("taskStatus")) {
            TaskStatus.FINISHED.name,
            TaskStatus.RECEIVED.name -> return true

            TaskStatus.TODO.name -> Unit
            else -> return false
        }
        val beforeTimes = (before.opt("rightsTimes") as? Number)?.toInt()
        val afterTimes = (after.opt("rightsTimes") as? Number)?.toInt()
        return beforeTimes != null && afterTimes != null && afterTimes > beforeTimes
    }

    internal suspend fun drawGameCenterAward() {
        var totalParadiseCoins = 0 // 🚀 统计总共获得的乐园币
        val attemptedCandidates = mutableSetOf<String>()
        var drawBlocked = false
        var previousDrawRights: Int? = null
        try {
            runCatching {
                val warmup = JSONObject(AntFarmRpcCall.refinedOperation("ENTERSELFWITHOUTPOP"))
                if (!warmup.optBoolean("success", false) && warmup.optString("resultCode") != "100") {
                    Log.farm("庄园游戏中心预热失败，继续尝试查询游戏列表")
                }
            }
            while (true) {
                if (ApplicationHookConstants.isOffline()) break
                val response = AntFarmRpcCall.queryGameList()
                val responseJo = JSONObject(response)
                val jo = responseJo.optJSONObject("resData") ?: responseJo

                if (!jo.optBoolean("success", responseJo.optBoolean("success"))) {
                    Log.farm("queryGameList 失败: $responseJo")
                    break
                }

                val currentRights =
                    findFirstObjectByKey(jo, "gameCenterDrawRights")
                        ?: findFirstObjectByKey(jo, "gameDrawAwardActivity")
                        ?: findFirstObjectByKey(jo, "gameEntryInfo")
                if (currentRights == null) {
                    Log.farm("未找到开宝箱权益，退出")
                    break
                }

                // Draw at most one server-bounded batch, then re-read authoritative rights.
                val quotaCanUse =
                    currentRights.optInt(
                        "quotaCanUse",
                        currentRights.optInt("canUseTimes", currentRights.optInt("drawRightsTimes", -1)),
                    )
                if (previousDrawRights != null && (quotaCanUse < 0 || quotaCanUse >= previousDrawRights)) {
                    drawBlocked = true
                    Log.farm("宝箱提交后权益未确认减少，停止本轮开箱，继续独立游戏")
                }
                previousDrawRights = null
                if (quotaCanUse > 0 && !drawBlocked) {
                    previousDrawRights = quotaCanUse
                    val batchDrawCount = quotaCanUse.coerceAtMost(10)
                    Log.farm("当前有 $quotaCanUse 个宝箱待开启，本次提交 $batchDrawCount 个")
                    val drawResponse = JSONObject(AntFarmRpcCall.drawGameCenterAward(batchDrawCount))
                    val drawRes = drawResponse.optJSONObject("resData") ?: drawResponse
                    if (drawRes.optBoolean("success", drawResponse.optBoolean("success"))) {
                        val awardList =
                            findFirstArrayByKey(drawRes, "gameCenterDrawAwardList")
                                ?: findFirstArrayByKey(drawRes, "drawAwardList")
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
                    val desc =
                        drawRes
                            .optString("desc")
                            .ifBlank { drawRes.optString("resultDesc") }
                            .ifBlank { drawResponse.optString("desc") }
                    if (isDrawQuotaExhausted(desc)) {
                        Log.farm("开宝箱权益已用完，停止本轮开箱: $desc")
                    } else {
                        Log.farm("开启宝箱失败: $desc")
                    }
                    drawBlocked = true
                }

                // Game rewards are independent from draw quota. A full draw quota only ends draw.
                val limit = currentRights.optInt("quotaLimit", currentRights.optInt("limit"))
                val used = currentRights.optInt("usedQuota", currentRights.optInt("usedTimes"))
                val remainingDraws = if (drawBlocked || quotaCanUse < 0) 0 else (limit - used - quotaCanUse).coerceAtLeast(0)
                val candidates = buildFarmGameCenterCandidates(jo, remainingDraws)
                var actionSubmitted = false
                while (advanceFarmGameCenterCandidate(candidates, attemptedCandidates, remainingDraws)) {
                    actionSubmitted = true
                }
                if (actionSubmitted) {
                    continue
                }

                if (limit > 0 && used >= limit) {
                    Log.farm("庄园乐园今日 $limit 个宝箱已满额，独立游戏无可验证动作")
                } else {
                    Log.farm("庄园乐园当前无可验证的补任务动作，保留后续快照重试")
                }
                break
            }
            receiveLeyuanLimitedBenefitAwards()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(AntFarm.TAG, "drawGameCenterAward 流程异常", t)
        } finally {
            if (totalParadiseCoins > 0) {
                Log.farm("庄园小鸡🎁[本次任务总计获得乐园币: $totalParadiseCoins]")
            }
        }
    }

    private fun buildFarmGameCenterCandidates(
        queryResponse: JSONObject,
        remainingDraws: Int,
    ): List<GameCenterPlayRpcCall.DeliveryBenefitCandidate> =
        GameCenterPlayRpcCall.collectDeliveryBenefitCandidates(queryResponse)
            .filter { candidate ->
                candidate.hasPendingReward ||
                    (remainingDraws > 0 &&
                        farmGameCenterDecision(candidate).action == GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT)
            }

    private fun farmGameCenterDecision(
        candidate: GameCenterPlayRpcCall.DeliveryBenefitCandidate,
    ): GameCenterPlayRpcCall.TaskActionDecision =
        GameCenterPlayRpcCall.resolveTaskAction(candidate.rawBenefit, candidate.rawGame)

    private suspend fun advanceFarmGameCenterCandidate(
        candidates: List<GameCenterPlayRpcCall.DeliveryBenefitCandidate>,
        attemptedCandidates: MutableSet<String>,
        remainingDraws: Int,
    ): Boolean {
        val candidate = candidates.firstOrNull { item ->
            item.snapshotKey !in attemptedCandidates &&
                farmGameCenterDecision(item).action != GameCenterPlayRpcCall.TaskAction.DEFERRED
        } ?: return false
        attemptedCandidates += candidate.snapshotKey
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
        return when (decision.action) {
            GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT -> {
                val gameTask = decision.mappedTask ?: return false
                val remaining = candidate.remainingRewards.coerceAtLeast(remainingDraws)
                val result =
                    gameTask.reportDetailed(
                        remaining,
                        actionFinishChannel = GameTask.Farm_ddply.channel,
                    )
                if (!result.completed) {
                    Log.farm("庄园乐园旧外部上报未确认成功，等待后续快照: ${result.failureMessage}")
                }
                result.completed
            }

            GameCenterPlayRpcCall.TaskAction.DURATION_ONLY -> {
                val contract = decision.contract ?: return false
                val acknowledgement = GameCenterPlayRpcCall.submitForAck(contract)
                if (!acknowledgement.accepted) {
                    Log.farm("庄园乐园时长上报未接受，等待后续快照")
                }
                acknowledgement.accepted
            }

            GameCenterPlayRpcCall.TaskAction.DIRECT_FINISH,
            GameCenterPlayRpcCall.TaskAction.CLICK_THEN_DURATION,
            GameCenterPlayRpcCall.TaskAction.OWNER_BUSINESS,
            GameCenterPlayRpcCall.TaskAction.DEFERRED -> false
        }
    }

    private fun receiveLeyuanLimitedBenefitAwards() {
        try {
            TaskFlowEngine(object : TaskFlowAdapter {
                override val moduleName = "AntFarm"
                override val flowName = "小鸡乐园任务"

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
                        "RECEIVED" -> if (item.actionType == "VIEW" && item.limit != null &&
                            (item.current ?: 0) < item.limit) TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.TERMINAL
                        "TODO" -> if (item.actionType == "VIEW") TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.BUSINESS_ACTION
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
                    Log.error(TAG, "小鸡乐园[${item.title}]领奖失败:$response")
                    return TaskFlowActionResult.defer(
                        DeferredReason.STATE_CONFIRMATION,
                        message = "领奖失败，保留原响应并回查领取状态",
                        raw = response.toString(), rpc = "com.alipay.antieptask.receiveTaskAwardantfarm",
                        refreshAfterAction = true,
                    )
                }

                override fun onQueryFailed(response: JSONObject) {
                    Log.error(TAG, "小鸡乐园任务查询失败:$response")
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
