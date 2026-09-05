package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

object FarmGame {
    private const val TAG = "FarmGame"
    private const val LEYUAN_DAILY_TASK_SCENE_CODE = "ANTFARM_LEYUAN_DAILY_TASK"
    private const val LEYUAN_SIGN_TASK_TYPE = "2026cc_lyqd"
    private const val LEYUAN_OPEN_BOX_TASK_TYPE = "2026cc_GAME_ljkbx"
    private const val LEYUAN_OPEN_BOX_TARGET_COUNT = 10
    private val LEYUAN_LIMITED_TASK_TYPES = setOf(LEYUAN_SIGN_TASK_TYPE, LEYUAN_OPEN_BOX_TASK_TYPE)

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

        val isAccelEnabled = antFarm.useAccelerateTool!!.value == true
        val isInsideTimeRange =
            antFarm.farmGameTrigger?.getTriggerSpec()?.let {
                TimeTriggerEvaluator.evaluateNow(it).allowNow
            } == true
        val ignoreAcceLimitMode = antFarm.ignoreAcceLimit!!.value == true
        val isAccelLimitReached = isAccelEnabled && antFarm.hasReachedAccelerateToolLimit()

        when {
            ignoreAcceLimitMode -> {
                if (isInsideTimeRange == true) {
                    if (Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED)) {
                        antFarm.receiveFarmAwards()
                    }
                    playAllFarmGames()
                } else {
                    Log.farm("当前处于按时游戏改分模式，未到设定时间，跳过")
                }
            }

            isAccelLimitReached || antFarm.accelerateToolCount <= 0 -> {
                antFarm.syncAnimalStatus(antFarm.ownerFarmId)
                val foodStockThreshold = AntFarm.foodStockLimit - antFarm.gameRewardMax!!.value!!
                val reserveMin = 180
                val ceilingStock = AntFarm.foodStockLimit - reserveMin

                if (AntFarm.foodStock < foodStockThreshold) {
                    antFarm.receiveFarmAwards()
                }

                var isSatisfied: Boolean
                if (reserveMin <= antFarm.gameRewardMax!!.value!!) {
                    isSatisfied = AntFarm.foodStock in foodStockThreshold..ceilingStock
                } else {
                    isSatisfied = AntFarm.foodStock >= foodStockThreshold
                }
                val isTaskEnabled = antFarm.doFarmTask?.value == true
                val isTaskFinished = Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED)

                when {
                    isSatisfied -> {
                        playAllFarmGames()
                    }

                    AntFarm.foodStock > ceilingStock -> {
                        Log.farm("当前饲料${AntFarm.foodStock}g（空间不足180g），等待小鸡进食后再执行游戏改分")
                    }

                    !isTaskEnabled -> {
                        Log.farm("未开启饲料任务，虽然尝试领取了奖励，但饲料缺口仍超过${antFarm.gameRewardMax!!.value}g，直接执行游戏")
                        playAllFarmGames()
                    }

                    isTaskFinished -> {
                        Log.farm(
                            "已开启饲料任务且今日已完成，但领取奖励后缺口仍超过${antFarm.gameRewardMax!!.value}g，暂不执行游戏改分。" +
                                "请确认饲料奖励完成情况，可以关闭设置里的“做饲料任务”选项直接进行游戏改分",
                        )
                    }

                    else -> {
                        Log.farm("已开启饲料任务但尚未完成，现有饲料缺口超过${antFarm.gameRewardMax!!.value}g，等待任务完成后再执行")
                    }
                }
            }

            // 加速卡还没用完，等待加速卡用完
            antFarm.accelerateToolCount > 0 -> {
                Log.farm(
                    "加速卡有${antFarm.accelerateToolCount}张，${antFarm.getAccelerateToolUsageSummary()}，" +
                        "尚未达到今日设定/系统上限，等待加速完成后再改分",
                )
            }
        }
    }

    suspend fun playAllFarmGames() {
        var allGamesConfirmed = true
        for (gameType in
            listOf(
                GameType.flyGame,
                GameType.hitGame,
                GameType.starGame,
                GameType.jumpGame,
            )
        ) {
            if (recordFarmGame(gameType) != FarmGameCompletion.CONFIRMED_TERMINAL) {
                allGamesConfirmed = false
            }
        }
        if (!allGamesConfirmed) {
            Log.error(TAG, "庄园游戏本轮未形成完整确认状态，保留下一轮重试")
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
                    val recordResult = AntFarmRpcCall.recordFarmGame(gameType.name)
                    val joRecord = JSONObject(recordResult)
                    if (!ResChecker.checkRes(TAG, joRecord)) {
                        Log.farm("庄园游戏提交失败: $joRecord")
                        return FarmGameCompletion.UNCONFIRMED
                    }
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
        try {
            runCatching {
                val warmup = JSONObject(AntFarmRpcCall.refinedOperation("ENTERSELFWITHOUTPOP"))
                if (!warmup.optBoolean("success", false) && warmup.optString("resultCode") != "100") {
                    Log.farm("庄园游戏中心预热失败，继续尝试查询游戏列表")
                }
            }
            while (true) {
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
                        currentRights.optInt("canUseTimes", currentRights.optInt("drawRightsTimes", 0)),
                    )
                if (quotaCanUse > 0) {
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
                    break
                }

                // Game rewards are independent from draw quota. A full draw quota only ends draw.
                val limit = currentRights.optInt("quotaLimit", currentRights.optInt("limit"))
                val used = currentRights.optInt("usedQuota", currentRights.optInt("usedTimes"))
                val candidates = buildFarmGameCenterCandidates(jo)
                var actionSubmitted = false
                while (advanceFarmGameCenterCandidate(candidates, attemptedCandidates)) {
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
    ): List<GameCenterPlayRpcCall.DeliveryBenefitCandidate> =
        GameCenterPlayRpcCall.collectDeliveryBenefitCandidates(queryResponse)
            .filter { it.hasPendingReward }

    private fun farmGameCenterDecision(
        candidate: GameCenterPlayRpcCall.DeliveryBenefitCandidate,
    ): GameCenterPlayRpcCall.TaskActionDecision =
        if (GameTask.fromAppId(candidate.appId) != null) {
            GameCenterPlayRpcCall.legacyExternalReportDecision("verified GameTask mapping")
        } else {
            GameCenterPlayRpcCall.decideDurationAction(false, candidate.rawBenefit, candidate.rawGame)
        }

    private suspend fun advanceFarmGameCenterCandidate(
        candidates: List<GameCenterPlayRpcCall.DeliveryBenefitCandidate>,
        attemptedCandidates: MutableSet<String>,
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
                val gameTask = GameTask.fromAppId(candidate.appId) ?: return false
                val result =
                    gameTask.reportDetailed(
                        candidate.remainingRewards,
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
            val attemptedTaskTypes = mutableSetOf<String>()
            repeat(LEYUAN_LIMITED_TASK_TYPES.size + 1) {
                val response = JSONObject(AntFarmRpcCall.queryOptionalPlay())
                if (!ResChecker.checkRes(TAG, response)) {
                    Log.farm("小鸡乐园限时福利查询失败: $response")
                    return
                }

                val taskList =
                    response
                        .optJSONObject("taskTriggerPlayInfo")
                        ?.optJSONArray("taskList")
                        ?: return
                val task = findNextLeyuanLimitedBenefitTask(taskList, attemptedTaskTypes) ?: return
                val taskType = task.optString("taskType")
                attemptedTaskTypes.add(taskType)

                val title =
                    task
                        .optJSONObject("bizInfo")
                        ?.optString("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: taskType
                if (taskType == LEYUAN_OPEN_BOX_TASK_TYPE && !hasOpenedEnoughGameCenterBoxes()) {
                    Log.farm("小鸡乐园限时福利[$title]已完成但开箱数未确认达到${LEYUAN_OPEN_BOX_TARGET_COUNT}个，暂不领奖")
                    return@repeat
                }

                val sceneCode = task.optString("sceneCode")
                val awardCount =
                    task.optInt("awardCount").takeIf { it > 0 }
                        ?: task.optInt("totalAwardCount").takeIf { it > 0 }
                        ?: task.optInt("nextStageAwardCount").takeIf { it > 0 }
                if (sceneCode.isBlank() || awardCount == null) {
                    Log.farm("小鸡乐园限时福利[$title]跳过：缺少 sceneCode 或 awardCount | raw=$task")
                    return@repeat
                }

                val awardResp =
                    JSONObject(
                        AntFarmRpcCall.receiveTaskAwardAntFarm(sceneCode, taskType, awardCount),
                    )
                if (ResChecker.checkRes(TAG, awardResp)) {
                    Log.farm("小鸡乐园限时福利🎁[$title]#${awardCount}乐园币")
                } else {
                    Log.farm("小鸡乐园限时福利[$title]领取失败: $awardResp")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveLeyuanLimitedBenefitAwards err:", t)
        }
    }

    private fun findNextLeyuanLimitedBenefitTask(
        taskList: JSONArray,
        attemptedTaskTypes: Set<String>,
    ): JSONObject? {
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val taskType = task.optString("taskType")
            if (task.optString("sceneCode") != LEYUAN_DAILY_TASK_SCENE_CODE) continue
            if (!LEYUAN_LIMITED_TASK_TYPES.contains(taskType)) continue
            if (task.optString("taskStatus") != "FINISHED") continue
            if (attemptedTaskTypes.contains(taskType)) continue
            return task
        }
        return null
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
