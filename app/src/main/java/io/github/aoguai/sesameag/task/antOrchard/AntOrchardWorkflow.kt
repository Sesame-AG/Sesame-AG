package io.github.aoguai.sesameag.task.antOrchard


import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.task.common.NonDurationGameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONObject

internal suspend fun AntOrchard.runOrchardRewardWorkflow(indexJson: JSONObject, userId: String) {
    tryReceiveSpreadManureActivityAward(indexJson)

    if (receiveSevenDayGift.value == true) {
        if (indexJson.has("lotteryPlusInfo")) {
            drawLotteryPlus(indexJson.getJSONObject("lotteryPlusInfo"))
        } else {
            checkLotteryPlus()
        }
    }

    extraInfoGet("entry")

    indexJson.optJSONObject("goldenEggInfo")?.let { initialEggInfo ->
        var actionKey = "goldenEgg"
        try {
            var eggInfo = initialEggInfo
            val attemptedSnapshots = mutableSetOf<String>()
            while (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) {
                val unsmashed = eggInfo.getInt("unsmashedGoldenEggs")
                val smashed = eggInfo.optInt("smashedGoldenEggs", -1)
                val limit = eggInfo.optInt("goldenEggLimit", -1)
                if (unsmashed < 0 || unsmashed == 0 && (smashed < 0 || limit < 0 || smashed >= limit)) break
                actionKey = if (unsmashed > 0) "smashedGoldenEgg" else "goldenEgg"
                if (TaskBlacklist.isTaskInBlacklist(getName(), actionKey)) break
                if (!attemptedSnapshots.add("$smashed:$unsmashed:$limit")) {
                    Log.error("AntOrchard", "金蛋数量回到已处理状态，本日停止该动作：$actionKey")
                    TaskBlacklist.addToBlacklist(getName(), actionKey, if (unsmashed > 0) "农场砸金蛋" else "农场金蛋游戏")
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordFlow(
                        io.github.aoguai.sesameag.task.common.TaskFlowExecutionState(), getName(), "农场金蛋",
                        io.github.aoguai.sesameag.task.common.TaskFlowRunResult(
                            false, false, false, 1, actionAttempted = true, failureCount = 1,
                        ),
                    )
                    break
                }
                if (unsmashed > 0) {
                    // 砸蛋失败或其成功后查询失败都立即结束，禁止外层再次回查。
                    if (!smashedGoldenEgg(unsmashed)) {
                        TaskBlacklist.addToBlacklist(getName(), actionKey, "农场砸金蛋")
                        io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                        break
                    }
                } else {
                    val action = NonDurationGameTask.advance(
                        GameTask.Orchard_ncscc, channel = GameTask.Orchard_ncscc.channel,
                        detail = "sceneCode=ORCHARD taskId=goldenEgg", logger = Log::orchard,
                        confirmProgress = {
                            val response = JSONObject(AntOrchardRpcCall.orchardIndex())
                            val current = response.optJSONObject("goldenEggInfo")
                            if (response.optString("resultCode") != "100" ||
                                current == null || !current.has("unsmashedGoldenEggs")
                            ) {
                                Log.error("AntOrchard", "金蛋上报后首页状态查询失败:$response")
                                null
                            } else current.optInt("unsmashedGoldenEggs") > unsmashed ||
                                current.has("smashedGoldenEggs") && current.has("goldenEggLimit") &&
                                current.optInt("smashedGoldenEggs") >= current.optInt("goldenEggLimit")
                        },
                    )
                    if (action.failureType != null) {
                        TaskBlacklist.addToBlacklist(getName(), actionKey, "农场金蛋游戏")
                        Log.error("AntOrchard", "金蛋游戏动作失败或未达业务进度 code=${action.code} ${action.message} raw=${action.raw}")
                        io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordFlow(
                            io.github.aoguai.sesameag.task.common.TaskFlowExecutionState(), getName(), "农场金蛋游戏",
                            io.github.aoguai.sesameag.task.common.TaskFlowRunResult(
                                false, false, action.stopCurrentRound, 1, actionAttempted = action.submissionStarted != false,
                                failureCount = 1,
                            ),
                        )
                        break
                    }
                    if (action.stopCurrentRound) return
                    if (action.submissionStarted == false || !action.refreshAfterAction) break
                }
                val refreshedIndex = JSONObject(AntOrchardRpcCall.orchardIndex())
                if (refreshedIndex.optString("resultCode") != "100") {
                    Log.error("AntOrchard", "金蛋动作后首页回查失败 raw=$refreshedIndex")
                    TaskBlacklist.addToBlacklist(getName(), actionKey, if (actionKey == "goldenEgg") "农场金蛋游戏" else "农场砸金蛋")
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                    break
                }
                val refreshedEggInfo = refreshedIndex.optJSONObject("goldenEggInfo")
                if (refreshedEggInfo == null || !refreshedEggInfo.has("unsmashedGoldenEggs")) {
                    Log.error("AntOrchard", "金蛋动作后首页回查缺少goldenEggInfo.unsmashedGoldenEggs")
                    TaskBlacklist.addToBlacklist(getName(), actionKey, if (actionKey == "goldenEgg") "农场金蛋游戏" else "农场砸金蛋")
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                    break
                }
                eggInfo = refreshedEggInfo
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
            if (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) {
                io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.ensureCurrent()
                TaskBlacklist.addToBlacklist(getName(), actionKey, if (actionKey == "goldenEgg") "农场金蛋游戏" else "农场砸金蛋")
                io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
            }
            Log.printStackTrace("AntOrchard", "金蛋奖励处理异常:", t)
        }
    }

    if (receiveOrchardTaskAward.value == true) {
        syncTaobaoLimitBalloon()
        doOrchardDailyTask(userId)
        doOrchardGameCenterTasks()
        doOrchardHomeGameTasks()
        receiveLeyuanDailyTaskAwards()
        runCampTakeover()
    }

    if (orchardChouChouLe.value == true) {
        runOrchardChouChouLe(userId)
    }

    receiveMoneyTreeReward()

    if (!Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_WIDGET_DAILY_AWARD)) {
        receiveOrchardVisitAward()
    }

    limitedTimeChallenge()
}

internal fun AntOrchard.runOrchardCultivationWorkflow() {
    if ((orchardSpreadManureCountMain.value ?: 0) != 0 || (orchardSpreadManureCountYeb.value ?: 0) != 0) {
        orchardSpreadManure()
        tryReceiveSpreadManureActivityAwardByQueryIndex()
    }

    val wateredMain = Status.getIntFlagToday(StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT) ?: 0
    if (wateredMain in 3..<10) {
        querySubplotsActivity(3)
    } else if (wateredMain >= 10) {
        querySubplotsActivity(10)
    }

    orchardAssistFriend()
    if (receiveOrchardTaskAward.value == true) runSupportAgriScratchCards()
}

private fun AntOrchard.runSupportAgriScratchCards() {
    var actionKey: String? = null
    try {
        val index = JSONObject(AntOrchardRpcCall.orchardLazyIndex())
        if (index.optString("resultCode") != "100") {
            Log.error("AntOrchard", "助农活动入口查询失败 raw=$index")
            return
        }
        val periodId = index.optJSONObject("supportAgriInfo")?.optJSONObject("activity")
            ?.optString("periodId").orEmpty()
        if (periodId.isBlank()) return
        var query = JSONObject(AntOrchardRpcCall.querySupportAgriActivity(periodId))
        val attemptedStages = mutableSetOf<String>()
        while (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) {
            if (query.optString("resultCode") != "100") {
                Log.error("AntOrchard", "助农活动查询失败 periodId=$periodId raw=$query")
                return
            }
            val stages = query.optJSONObject("personal")?.optJSONArray("stages")
            if (stages != null) for (i in 0 until stages.length()) {
                val stage = stages.optJSONObject(i) ?: continue
                val stageId = stage.optString("stageId")
                if (!stage.optBoolean("claimable") || stageId.isBlank() || !attemptedStages.add(stageId)) continue
                val rewardKey = "supportAgriReward:$periodId:$stageId"
                if (TaskBlacklist.isTaskInBlacklist(getName(), rewardKey)) continue
                actionKey = rewardKey
                val receipt = JSONObject(AntOrchardRpcCall.claimSupportAgriReward(periodId, stageId))
                if (io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) return
                // 警告：领奖失败不回查、不重领，独立奖励按原业务身份进入当日名单。
                if (!isOrchardRpcSuccessResponse(receipt)) {
                    Log.error("AntOrchard", "助农成就奖励领取失败 stageId=$stageId raw=$receipt")
                    TaskBlacklist.addToBlacklist(getName(), rewardKey, "助农成就奖励")
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                    actionKey = null
                    continue
                }
                query = JSONObject(AntOrchardRpcCall.querySupportAgriActivity(periodId))
                val refreshedStages = query.optJSONObject("personal")?.optJSONArray("stages")
                val received = query.optString("resultCode") == "100" && refreshedStages != null &&
                    (0 until refreshedStages.length()).any {
                        val current = refreshedStages.optJSONObject(it)
                        current?.optString("stageId") == stageId &&
                            current.optString("unclaimableReason") == "ALREADY_COMPLETED" && !current.optBoolean("claimable")
                    }
                if (received) Log.orchard("助农成就奖励🎖️[$stageId]#回查确认领取")
                else {
                    Log.error("AntOrchard", "助农成就奖励未确认领取 stageId=$stageId receipt=$receipt query=$query")
                    TaskBlacklist.addToBlacklist(getName(), rewardKey, "助农成就奖励")
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                }
                actionKey = null
                if (query.optString("resultCode") != "100") return
            }
            val personal = query.optJSONObject("personal")
            val available = personal?.optInt("availableDrawTimes", -1) ?: -1
            if (available < 0) {
                Log.error("AntOrchard", "助农刮卡缺少服务端库存 raw=$query")
                return
            }
            if (available == 0) return
            val activity = query.optJSONObject("activity")
            val activityId = activity?.optString("drawActivityId").orEmpty()
            val sceneCode = activity?.optString("drawScene").orEmpty()
            if (activityId.isBlank() || sceneCode.isBlank()) {
                Log.error("AntOrchard", "助农刮卡缺少活动参数 raw=$query")
                return
            }
            val drawKey = "supportAgriDraw:$periodId"
            if (TaskBlacklist.isTaskInBlacklist(getName(), drawKey)) return
            actionKey = drawKey
            val times = if (available >= 5) 5 else 1
            val draw = JSONObject(AntOrchardRpcCall.drawSupportAgriCard(activityId, sceneCode, times))
            if (!draw.optBoolean("success")) {
                Log.error("AntOrchard", "助农刮卡失败 periodId=$periodId raw=$draw")
                TaskBlacklist.addToBlacklist(getName(), drawKey, "助农刮卡")
                io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                return
            }
            if (io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) return
            query = JSONObject(AntOrchardRpcCall.querySupportAgriActivity(periodId))
            val remaining = query.optJSONObject("personal")?.optInt("availableDrawTimes", -1) ?: -1
            if (query.optString("resultCode") != "100" || remaining < 0 || remaining >= available) {
                Log.error("AntOrchard", "助农刮卡后库存未确认减少 before=$available raw=$query")
                TaskBlacklist.addToBlacklist(getName(), drawKey, "助农刮卡")
                io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
                return
            }
            actionKey = null
            Log.orchard("助农刮刮乐🎁[消耗${available - remaining}张，剩余${remaining}张]")
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
        if (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) {
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.ensureCurrent()
            actionKey?.let { TaskBlacklist.addToBlacklist(getName(), it, "助农奖励") }
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.recordException()
        }
        Log.printStackTrace("AntOrchard", "助农刮卡处理异常:", t)
    }
}
