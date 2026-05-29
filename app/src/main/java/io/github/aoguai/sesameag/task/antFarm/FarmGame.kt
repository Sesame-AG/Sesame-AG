package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
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
    private const val QUERY_GAME_LIST_RPC = "com.alipay.charitygamecenter.queryGameList"
    private const val QUERY_OPTIONAL_PLAY_RPC = "com.alipay.charitygamecenter.queryOptionalPlay"
    private const val LEYUAN_DAILY_TASK_SCENE_CODE = "ANTFARM_LEYUAN_DAILY_TASK"
    private const val LEYUAN_SIGN_TASK_TYPE = "2026cc_lyqd"
    private const val LEYUAN_OPEN_BOX_TASK_TYPE = "2026cc_GAME_ljkbx"
    private const val LEYUAN_OPEN_BOX_TARGET_COUNT = 10
    private val LEYUAN_LIMITED_TASK_TYPES = setOf(LEYUAN_SIGN_TASK_TYPE, LEYUAN_OPEN_BOX_TASK_TYPE)

    private fun isDrawQuotaExhausted(message: String): Boolean {
        return message.contains("抽奖次数不足") ||
            message.contains("无可用抽奖次数") ||
            message.contains("暂无抽奖次数")
    }

    enum class GameType {
        flyGame, hitGame, starGame, jumpGame;
        fun gameName(): String = when(this) {
            flyGame -> "飞行赛"
            hitGame -> "欢乐揍小鸡"
            starGame -> "星星球"
            jumpGame -> "登山赛"
        }
    }

    /**
     * 外部入口：处理游戏改分逻辑
     */
    suspend fun run(antFarm: AntFarm) {
        if (Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)) {
            Log.farm("今日庄园游戏改分已完成")
            return
        }

        val isAccelEnabled = antFarm.useAccelerateTool!!.value == true
        val isInsideTimeRange = antFarm.farmGameTrigger?.getTriggerSpec()?.let {
            TimeTriggerEvaluator.evaluateNow(it).allowNow
        } == true
        val ignoreAcceLimitMode = !isAccelEnabled || antFarm.ignoreAcceLimit!!.value == true
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
                } else{
                    isSatisfied = AntFarm.foodStock >= foodStockThreshold
                }
                val isTaskEnabled = antFarm.doFarmTask?.value == true
                val isTaskFinished = Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED)

                when {
                    isSatisfied -> playAllFarmGames()

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
                                    "请确认饲料奖励完成情况，可以关闭设置里的“做饲料任务”选项直接进行游戏改分"
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
                        "尚未达到今日设定/系统上限，等待加速完成后再改分"
                )
            }
        }
    }
    suspend fun playAllFarmGames() {
        recordFarmGame(GameType.flyGame)
        recordFarmGame(GameType.hitGame)
        recordFarmGame(GameType.starGame)
        recordFarmGame(GameType.jumpGame)
        Status.setFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)
        Log.farm("今日庄园游戏改分已完成")
    }

    private suspend fun recordFarmGame(gameType: GameType) {
        try {
            while (true) {
                val initRes = AntFarmRpcCall.initFarmGame(gameType.name)
                val joInit = JSONObject(initRes)
                if (!ResChecker.checkRes(TAG, joInit)) break

                val gameAward = joInit.optJSONObject("gameAward")
                if (gameAward?.optBoolean("level3Get") == true) {
                    Log.farm("[${gameType.gameName()}]#今日奖励已领满")
                    break
                }

                val remainingCount = joInit.optInt("remainingGameCount", 1)
                if (remainingCount > 0) {
                    val recordResult = AntFarmRpcCall.recordFarmGame(gameType.name)
                    val joRecord = JSONObject(recordResult)
                    if (ResChecker.checkRes(TAG, joRecord)) {
                        val awardStr = parseGameAward(joRecord)
                        Log.farm("庄园游戏🎮[${gameType.gameName()}]#$awardStr")

                        if (joRecord.optInt("remainingGameCount", 0) > 0) {
                            delay(3000)
                            continue
                        }
                    } else {
                        Log.farm("庄园游戏提交失败: $joRecord")
                    }
                }

                if (handleGameTasks(gameType)) {
                    delay(3000)
                    continue
                }
                break
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.farm("recordFarmGame 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "recordFarmGame err:",t)
        }
    }

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

    private fun handleGameTasks(gameType: GameType): Boolean {
        // 仅飞行赛和揍小鸡有独立任务列表
        val listResponse = when (gameType) {
            GameType.flyGame -> AntFarmRpcCall.FlyGameListFarmTask()
            GameType.hitGame -> AntFarmRpcCall.HitGameListFarmTask()
            else -> return false
        }
        if (listResponse.isEmpty()) return false
        val farmTaskList = JSONObject(listResponse).optJSONArray("farmTaskList") ?: return false

        for (i in 0 until farmTaskList.length()) {
            val task = farmTaskList.getJSONObject(i)
            val status = task.optString("taskStatus")
            val taskId = task.optString("taskId")
            val awardType = task.optString("awardType")
            if (TaskStatus.RECEIVED.name == status) continue
            if (TaskStatus.FINISHED.name == status) {
                AntFarmRpcCall.receiveFarmTaskAward(taskId, awardType)
                return true
            }
            if (TaskStatus.TODO.name == status) {
                val bizKey = task.optString("bizKey")
                val outBizNo = "${bizKey}_${System.currentTimeMillis()}_${Integer.toHexString((Math.random() * 0xFFFFFF).toInt())}"
                AntFarmRpcCall.finishTask(bizKey, "ANTFARM_GAME_TIMES_TASK", outBizNo)
                return true
            }
        }
        return false
    }

    internal suspend fun drawGameCenterAward() {
        var totalParadiseCoins = 0 // 🚀 统计总共获得的乐园币
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

                val currentRights = findFirstObjectByKey(jo, "gameCenterDrawRights")
                    ?: findFirstObjectByKey(jo, "gameDrawAwardActivity")
                    ?: findFirstObjectByKey(jo, "gameEntryInfo")
                if (currentRights == null) {
                    Log.farm("未找到开宝箱权益，退出")
                    break
                }

                // 1. 处理当前可开的宝箱 (对应你说的 canUse)
                var quotaCanUse = currentRights.optInt(
                    "quotaCanUse",
                    currentRights.optInt("canUseTimes", currentRights.optInt("drawRightsTimes", 0))
                )
                if (quotaCanUse > 0) {
                    Log.farm("当前有 $quotaCanUse 个宝箱待开启...")
                    while (quotaCanUse > 0) {
                        val batchDrawCount = quotaCanUse.coerceAtMost(10)
                        val drawResponse = JSONObject(AntFarmRpcCall.drawGameCenterAward(batchDrawCount))
                        val drawRes = drawResponse.optJSONObject("resData") ?: drawResponse
                        if (drawRes.optBoolean("success", drawResponse.optBoolean("success"))) {
                            quotaCanUse = (quotaCanUse - batchDrawCount).coerceAtLeast(0)

                            val awardList = findFirstArrayByKey(drawRes, "gameCenterDrawAwardList")
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
                        } else {
                            val desc = drawRes.optString("desc")
                                .ifBlank { drawRes.optString("resultDesc") }
                                .ifBlank { drawResponse.optString("desc") }
                            if (isDrawQuotaExhausted(desc)) {
                                Log.farm("开宝箱权益已用完，停止本轮开箱: $desc")
                            } else {
                                Log.farm("开启宝箱失败: $desc")
                            }
                            return
                        }
                    }
                    continue
                }

                // 2. 处理剩余任务 (判断是否需要去刷任务)
                val limit = currentRights.optInt("quotaLimit", currentRights.optInt("limit")) // 总上限，比如 10
                val used = currentRights.optInt("usedQuota", currentRights.optInt("usedTimes"))   // 今日已获得的总数，比如 2

                // 计算逻辑：如果 已获得 < 总上限，且当前没机会了，就去刷
                val remainToTask = limit - used
                if (remainToTask > 0 && quotaCanUse == 0) {
                    // Log.farm("宝箱进度: $used/$limit，开始自动刷任务补齐...")
                    // 根据游戏类型选择上报任务
                    GameTask.Farm_ddply.report(remainToTask)
                    continue
                } else if (remainToTask <= 0) {
                    Log.farm("今日 $limit 个金蛋任务已全部满额")
                    break
                }
            }
            receiveLeyuanLimitedBenefitAwards()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(AntFarm.TAG, "drawGameCenterAward 流程异常", t)
        } finally {
            if (totalParadiseCoins > 0) {
                Log.farm("庄园小鸡🎁[本次任务总计获得乐园币: ${totalParadiseCoins}]")
            }
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

                val taskList = response.optJSONObject("taskTriggerPlayInfo")
                    ?.optJSONArray("taskList")
                    ?: return
                val task = findNextLeyuanLimitedBenefitTask(taskList, attemptedTaskTypes) ?: return
                val taskType = task.optString("taskType")
                attemptedTaskTypes.add(taskType)

                val title = task.optJSONObject("bizInfo")
                    ?.optString("title")
                    ?.takeIf { it.isNotBlank() }
                    ?: taskType
                if (taskType == LEYUAN_OPEN_BOX_TASK_TYPE && !hasOpenedEnoughGameCenterBoxes()) {
                    Log.farm("小鸡乐园限时福利[$title]已完成但开箱数未确认达到${LEYUAN_OPEN_BOX_TARGET_COUNT}个，暂不领奖")
                    return@repeat
                }

                val sceneCode = task.optString("sceneCode")
                val awardCount = task.optInt("awardCount").takeIf { it > 0 }
                    ?: task.optInt("totalAwardCount").takeIf { it > 0 }
                    ?: task.optInt("nextStageAwardCount").takeIf { it > 0 }
                if (sceneCode.isBlank() || awardCount == null) {
                    Log.farm("小鸡乐园限时福利[$title]跳过：缺少 sceneCode 或 awardCount | raw=$task")
                    return@repeat
                }

                val awardResp = JSONObject(
                    AntFarmRpcCall.receiveTaskAwardAntFarm(sceneCode, taskType, awardCount)
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
        attemptedTaskTypes: Set<String>
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
            val rights = findFirstObjectByKey(jo, "gameCenterDrawRights")
                ?: findFirstObjectByKey(jo, "gameDrawAwardActivity")
                ?: findFirstObjectByKey(jo, "gameEntryInfo")
                ?: return null
            maxOf(
                rights.optInt("usedQuota", -1),
                rights.optInt("usedTimes", -1),
                rights.optInt("drawUsedTimes", -1),
                rights.optInt("totalUsedTimes", -1)
            ).takeIf { it >= 0 }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryGameCenterOpenedBoxCount err:", t)
            null
        }
    }


    internal fun findFirstObjectByKey(source: Any?, targetKey: String): JSONObject? {
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

            else -> null
        }
    }

    internal fun findFirstArrayByKey(source: Any?, targetKey: String): JSONArray? {
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

            else -> null
        }
    }

}
