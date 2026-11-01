package io.github.aoguai.sesameag.task.antForestPatrol

import android.net.Uri
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.reserve.Reserve
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object MonopolyPatrolWorkflow {
    private const val TAG = "AntForestPatrol"

    fun run(tasksEnabled: Boolean, onMapCompleted: (() -> Unit)? = null) {
        var state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "查询新巡护入口失败:", state)) return
        val region = state.optJSONObject("regionInfo")
        val map = state.optJSONObject("mapInfo")
        if (region == null || map == null || state.optJSONObject("userInfo") == null) {
            Log.error(TAG, "新巡护入口缺少区域、地图或用户状态:$state")
            return
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            isLenient = false
        }
        for (activity in listOf(region, map)) {
            val start = activity.optString("startDate").takeIf { it.isNotBlank() }?.let { dateFormat.parse(it)?.time }
            val end = activity.optString("endDate").takeIf { it.isNotBlank() }?.let { dateFormat.parse(it)?.time }
            val now = System.currentTimeMillis()
            if ((start != null && now < start) || (end != null && now >= end)) {
                Log.forestPatrol("新巡护当前区域或地图不在开放期，停止本轮")
                return
            }
        }
        val taskState = TaskFlowExecutionState()
        var guideRoll = state.optJSONObject("userInfo")?.optBoolean("firstEnterMonopoly", false) == true
        var pendingEvent = state.optJSONObject("eventInfo")
        if (tasksEnabled) runTasks(region.optString("regionCode"), taskState)
        if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
        val props = parsePatrolResponse(AntForestPatrolRpcCall.triggerMonopolyHomeProps())
        if (!ResChecker.checkRes(TAG, "新巡护首页道具处理失败:", props)) return
        state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "刷新新巡护状态失败:", state)) return
        pendingEvent = pendingEvent ?: state.optJSONObject("eventInfo")
        val confirmedEvents = mutableSetOf<String>()
        val completedMaps = mutableSetOf<String>()
        var tasksMayHaveChanged = false
        while (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted) {
            val event = pendingEvent
            if (event?.optBoolean("needConfirm", false) == true) {
                val eventId = event.optString("eventId")
                val action = when (event.optString("eventType")) {
                    "CHARITY" -> "confirm"
                    "SPECIAL" -> "skip"
                    else -> null
                }
                if (eventId.isBlank() || action == null ||
                    event.optJSONObject("displayInfo")?.optString("flowType") != "SINGLE_ACTION_CONFIRM") {
                    Log.error(TAG, "新巡护事件缺少可执行决策，保留当前事件:$event")
                    return
                }
                if (eventId in confirmedEvents) {
                    Log.forestPatrol("新巡护事件[$eventId]已提交确认，等待后续调度刷新")
                    return
                }
                val confirmation = parsePatrolResponse(AntForestPatrolRpcCall.confirmMonopolyEvent(eventId, action))
                if (!ResChecker.checkRes(TAG, "新巡护事件确认失败:", confirmation)) return
                confirmedEvents.add(eventId)
                Log.forestPatrol("新巡护事件完成[$eventId] 奖励=${confirmation.optJSONArray("eventResult") ?: JSONArray()}")
                state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
                if (!ResChecker.checkRes(TAG, "刷新新巡护事件状态失败:", state)) return
                pendingEvent = state.optJSONObject("eventInfo")
                tasksMayHaveChanged = true
                continue
            }
            val mapInfo = state.optJSONObject("mapInfo")
            val mapCode = mapInfo?.optString("mapCode").orEmpty()
            val creatures = mapInfo?.optJSONArray("creatureList")
            if (onMapCompleted != null && mapCode.isNotBlank() && mapCode !in completedMaps &&
                creatures != null && creatures.length() > 0 && (0 until creatures.length()).all { index ->
                    val animal = creatures.optJSONObject(index)
                    animal != null && animal.optInt("maxLevel", -1) > 0 &&
                        animal.optInt("level", -1) >= animal.optInt("maxLevel")
                }) {
                completedMaps.add(mapCode)
                onMapCompleted()
                if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
                state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
                if (!ResChecker.checkRes(TAG, "切图后刷新巡护入口失败:", state)) return
                pendingEvent = state.optJSONObject("eventInfo")
                if (state.optJSONObject("mapInfo")?.optString("mapCode") != mapCode) {
                    guideRoll = state.optJSONObject("userInfo")?.optBoolean("firstEnterMonopoly", false) == true
                    if (tasksEnabled) runTasks(state.optJSONObject("regionInfo")?.optString("regionCode").orEmpty(), taskState)
                    tasksMayHaveChanged = true
                    continue
                }
            }
            val diceCount = state.optInt("totalDiceCount", -1)
            if (diceCount < 0) {
                Log.error(TAG, "新巡护缺少可用骰子数量:$state")
                return
            }
            if (diceCount == 0) {
                if (tasksEnabled && tasksMayHaveChanged) {
                    runTasks(state.optJSONObject("regionInfo")?.optString("regionCode").orEmpty(), taskState)
                    tasksMayHaveChanged = false
                    if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
                    state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
                    if (!ResChecker.checkRes(TAG, "刷新新巡护任务奖励失败:", state)) return
                    pendingEvent = state.optJSONObject("eventInfo")
                    continue
                }
                Log.forestPatrol("新巡护当前无可用骰子，后续调度继续查询")
                return
            }
            val previousSteps = state.optJSONObject("userInfo")?.optInt("stepCount", -1) ?: -1
            val roll = parsePatrolResponse(AntForestPatrolRpcCall.rollMonopolyDice(guideRoll))
            if (!ResChecker.checkRes(TAG, "新巡护掷骰失败:", roll)) return
            guideRoll = false
            pendingEvent = roll.optJSONObject("eventInfo")
            val steps = roll.optJSONObject("userInfo")?.optInt("stepCount", -1) ?: -1
            if (roll.optInt("totalDiceCount", -1) == diceCount && steps == previousSteps && pendingEvent == null) {
                Log.error(TAG, "新巡护掷骰后未确认状态变化，保留后续调度:$roll")
                return
            }
            tasksMayHaveChanged = true
            Log.forestPatrol("新巡护掷骰🎲[${roll.optInt("diceNumber")}] 剩余${roll.optInt("totalDiceCount")}次")
            state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
            if (!ResChecker.checkRes(TAG, "刷新新巡护掷骰状态失败:", state)) return
            pendingEvent = pendingEvent ?: state.optJSONObject("eventInfo")
        }
    }

    private fun runTasks(regionCode: String, executionState: TaskFlowExecutionState) {
        val sceneCode = "ANTFOREST_MONOPOLY_TASK_HSDWY"
        try {
            TaskFlowEngine(MonopolyTaskAdapter(regionCode, sceneCode), executionState = executionState).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "新版巡护任务失败，保留地图机会处理", e)
        }
    }

    class RegionRestoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    fun updateMaps(
        exchangeEnabled: Boolean,
        earlyExchange: Boolean,
        switchEnabled: Boolean,
        energyReserve: Int,
        showRanking: Boolean,
    ) {
        val store = UserDataStoreManager.getCurrentInstance() ?: return
        val epoch = AccountSessionCoordinator.currentSessionEpoch()
        val pendingKey = "patrol_map_region"
        try {
            if (store.get(pendingKey, String::class.java).orEmpty().isNotBlank()) {
                Log.forestPatrol("先恢复上次目录查询的巡护区域")
                return
            }
            if (ApplicationHookConstants.isOffline() ||
                AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current
            ) return
            val page = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyMapPage())
            if (!ResChecker.checkRes(TAG, "查询巡护地图失败:", page)) return
            val originalRegion = page.getString("currentRegionCode")
            updateRegionMaps(page, exchangeEnabled, earlyExchange, switchEnabled, energyReserve, showRanking)
            if (!exchangeEnabled) return
            val regions = page.getJSONArray("regionList")
            for (index in 0 until regions.length()) {
                val region = regions.getJSONObject(index)
                val regionCode = region.getString("regionCode")
                if (regionCode == originalRegion || region.optString("regionAvailability") != "AVAILABLE") continue
                if (ApplicationHookConstants.isOffline() ||
                    AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current
                ) return
                store.put(pendingKey, originalRegion, requirePersistence = true)
                val switched = parsePatrolResponse(AntForestPatrolRpcCall.switchMonopolyRegion(regionCode))
                ResChecker.checkRes(TAG, "查询其他区域时切换失败:", switched)
                if (ApplicationHookConstants.isOffline() ||
                    AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current
                ) return
                val regionPage = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyMapPage())
                check(ResChecker.checkRes(TAG, regionPage) && regionPage.optString("currentRegionCode") == regionCode) {
                    "巡护区域切换未确认[$regionCode] raw=$regionPage"
                }
                updateRegionMaps(regionPage, exchangeEnabled, earlyExchange, false, energyReserve, showRanking)
            }
        } finally {
            if (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted &&
                AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) is AccountSessionCheck.Current
            ) {
                val originalRegion = store.get(pendingKey, String::class.java).orEmpty()
                if (originalRegion.isNotBlank()) {
                    try {
                        val restored = parsePatrolResponse(AntForestPatrolRpcCall.switchMonopolyRegion(originalRegion))
                        ResChecker.checkRes(TAG, "恢复巡护区域返回失败:", restored)
                        if (ApplicationHookConstants.isOffline() ||
                            AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current
                        ) {
                            throw RegionRestoreException("巡护区域[$originalRegion]尚待恢复")
                        }
                        val confirmed = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyMapPage())
                        if (!ResChecker.checkRes(TAG, confirmed) || confirmed.optString("currentRegionCode") != originalRegion) {
                            throw RegionRestoreException("恢复巡护区域[$originalRegion]未确认 raw=$confirmed")
                        }
                        store.remove(pendingKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        throw RegionRestoreException("巡护区域恢复失败，停止本轮巡护", e)
                    }
                }
            }
        }
    }

    private fun updateRegionMaps(
        initialPage: JSONObject,
        exchangeEnabled: Boolean,
        earlyExchange: Boolean,
        switchEnabled: Boolean,
        energyReserve: Int,
        showRanking: Boolean,
    ) {
        var page = initialPage
        if (!ResChecker.checkRes(TAG, "查询巡护地图失败:", page)) return
        val regionCode = page.getString("currentRegionCode")
        val mapCode = page.getString("currentMapCode")
        if (regionCode.isBlank() || mapCode.isBlank() || energyReserve < 0) {
            Log.error(TAG, "巡护区域、地图或保留能量无效:$page")
            return
        }
        var maps = page.getJSONArray("currentRegionMapList")
        if (showRanking) {
            val regions = page.getJSONArray("regionList")
            for (index in 0 until regions.length()) {
                val region = regions.getJSONObject(index)
                Log.forestPatrol("区域[${region.optString("regionName", region.optString("regionCode"))}]状态[${region.optString("regionAvailability")}]，进入${region.optInt("enteredMapCount")}/${region.optInt("totalMapCount")}张地图")
            }
            for (index in 0 until maps.length()) {
                val map = maps.getJSONObject(index)
                val animals = map.optJSONArray("creatureList")
                val maxed = if (animals == null) 0 else (0 until animals.length()).count { animalIndex ->
                    val animal = animals.optJSONObject(animalIndex)
                    animal != null && animal.optInt("maxLevel", -1) > 0 && animal.optInt("level", -1) >= animal.optInt("maxLevel")
                }
                val name = map.optJSONObject("displayInfo")?.optJSONObject("mapDisplay")?.optString("mapNameText")
                    ?.takeIf { it.isNotBlank() } ?: map.optString("mapCode")
                Log.forestPatrol("地图[$name]${if (map.optBoolean("currentMap")) "（当前）" else ""}，状态[${map.optString("mapAvailability")}]，动物${map.opt("ownedCreatureCount")}/${map.opt("totalCreatureCount")}，满星$maxed，证书[${map.optString("certificateStatus")}]")
            }
            for (rankType in listOf("TOTAL", "DAY")) {
                try {
                    val ranking = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyRanking(rankType))
                    if (!ResChecker.checkRes(TAG, "查询巡护排行失败[$rankType]:", ranking)) continue
                    if (ranking.optBoolean("degraded")) {
                        Log.forestPatrol("巡护排行[$rankType]暂不可用")
                        continue
                    }
                    val myself = ranking.optJSONObject("myself")
                    if (myself == null) {
                        Log.error(TAG, "巡护排行缺少本人数据[$rankType]:$ranking")
                        continue
                    }
                    val values = listOf("rank" to "名次", "stepCount" to "步数", "mapCount" to "证书", "medalCount" to "勋章")
                        .mapNotNull { (key, label) -> if (myself.has(key)) "$label${myself.getLong(key)}" else null }
                    Log.forestPatrol("${if (rankType == "TOTAL") "累计" else "今日"}巡护排行：${values.joinToString("，")}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "巡护排行查询异常[$rankType]", e)
                }
            }
        }
        if (exchangeEnabled) {
            val mapCodes = (0 until maps.length()).map { maps.getJSONObject(it).getString("mapCode") }.distinct()
            for (candidateCode in mapCodes) {
                if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
                val currentMaps = (0 until maps.length()).map { maps.getJSONObject(it) }
                val candidate = currentMaps.firstOrNull { it.optString("mapCode") == candidateCode } ?: continue
                when (val status = candidate.optString("certificateStatus")) {
                    "OBTAINED" -> continue
                    "PENDING" -> Unit
                    else -> {
                        Log.error(TAG, "巡护证书状态未知[$candidateCode] status=$status")
                        continue
                    }
                }
                if (!earlyExchange && candidate.optString("mapAvailability") != "AVAILABLE") continue
                val bindings = currentMaps.associate { map ->
                    val display = map.optJSONObject("displayInfo")?.optJSONObject("mapDisplay")
                    val redirect = display?.optJSONObject("protectionGuideDisplay")?.optString("redirectUrl")
                        ?.takeIf { it.isNotBlank() } ?: display?.optString("lockedRedirectUrl").orEmpty()
                    val uri = redirect.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    val id = if (uri?.isHierarchical == true) {
                        uri.getQueryParameter("projectId") ?: uri.getQueryParameter("url")?.let {
                            val nested = Uri.parse(it)
                            if (nested.isHierarchical) nested.getQueryParameter("projectId") else null
                        }
                    } else null
                    map.getString("mapCode") to id?.toLongOrNull()?.takeIf { it > 0L }
                }
                val projectId = bindings[candidateCode]
                if (projectId == null) {
                    Log.forestPatrol("地图[$candidateCode]未下发明确的证书项目，保留后续查询")
                    continue
                }
                if (candidateCode != mapCode && bindings.any { (code, id) -> code != candidateCode && id == projectId }) {
                    val currentMap = currentMaps.firstOrNull { it.optString("mapCode") == mapCode }
                    val targetGuideUrl = candidate.optJSONObject("displayInfo")?.optJSONObject("mapDisplay")
                        ?.optJSONObject("protectionGuideDisplay")?.optString("redirectUrl").orEmpty()
                    val currentGuideUrl = currentMap?.optJSONObject("displayInfo")?.optJSONObject("mapDisplay")
                        ?.optJSONObject("protectionGuideDisplay")?.optString("redirectUrl").orEmpty()
                    if (candidate.optString("mapAvailability") == "LOCKED" && targetGuideUrl.isBlank() &&
                        currentGuideUrl.isNotBlank() && currentMap?.optString("certificateStatus") == "OBTAINED" &&
                        bindings[mapCode] == projectId
                    ) {
                        Log.forestPatrol("地图[$candidateCode]尚未解锁，锁定引导指向当前地图已获证书项目[$projectId]，等待解锁条件满足，不执行兑换")
                    } else {
                        Log.error(TAG, "地图[$candidateCode]证书链接与其他地图共用项目[$projectId]，未确认归属，不执行兑换")
                    }
                    continue
                }
                val before = parsePatrolResponse(AntForestPatrolRpcCall.queryCertificate(projectId.toString()))
                if (!ResChecker.checkRes(TAG, "查询巡护证书资格失败:", before)) continue
                val project = before.optJSONObject("exchangeableTree")
                if (project == null || project.optLong("projectId", -1L) != projectId) {
                    Log.error(TAG, "巡护证书项目身份不符[$candidateCode] projectId=$projectId raw=$before")
                    continue
                }
                if (Reserve.exchangeTree(projectId.toString(), project.optString("projectName", candidateCode),
                        count = 1, targetCount = 1, energyReserve = energyReserve, initialDetail = before)) {
                    page = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyMapPage())
                    if (!ResChecker.checkRes(TAG, "回查巡护证书地图失败:", page)) return
                    maps = page.getJSONArray("currentRegionMapList")
                    val confirmed = (0 until maps.length()).map { maps.getJSONObject(it) }
                        .firstOrNull { it.optString("mapCode") == candidateCode }
                    if (confirmed?.optString("certificateStatus") == "OBTAINED") {
                        Log.forestPatrol("已确认获得地图[$candidateCode]保护证书")
                    } else {
                        Log.error(TAG, "地图[$candidateCode]证书回查尚未确认，不重复消耗")
                    }
                }
            }
        }
        if (!switchEnabled || ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
        val current = (0 until maps.length()).map { maps.getJSONObject(it) }.firstOrNull { it.optString("mapCode") == mapCode }
        if (current == null) {
            Log.error(TAG, "巡护地图目录缺少当前地图[$mapCode]")
            return
        }
        val total = current.optInt("totalCreatureCount", -1)
        val animals = current.optJSONArray("creatureList")
        if (total <= 0 || current.optInt("ownedCreatureCount", -1) < total || animals == null || animals.length() != total ||
            !(0 until animals.length()).all { index ->
                val animal = animals.optJSONObject(index)
                animal != null && animal.optInt("maxLevel", -1) > 0 && animal.optInt("level", -1) >= animal.optInt("maxLevel")
            }) return
        val currentLevel = current.optInt("level", -1)
        if (currentLevel <= 0) {
            Log.error(TAG, "当前巡护地图缺少等级[$mapCode]")
            return
        }
        val target = (0 until maps.length()).map { maps.getJSONObject(it) }
            .filter { it.optString("mapAvailability") == "AVAILABLE" && it.optString("mapCode").isNotBlank() && it.optInt("level", -1) > currentLevel }
            .maxByOrNull { it.getInt("level") }
        if (target == null) {
            Log.forestPatrol("当前地图动物已全部满星，服务端未下发更新的已开放地图，保持当前地图")
            return
        }
        val targetCode = target.getString("mapCode")
        val switched = parsePatrolResponse(AntForestPatrolRpcCall.switchMonopolyMap(targetCode, regionCode))
        if (!ResChecker.checkRes(TAG, "切换巡护地图失败:", switched)) return
        val refreshed = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "回查巡护切图失败:", refreshed)) return
        val user = refreshed.optJSONObject("userInfo")
        if (user?.optString("currentMapCode") == targetCode && user.optString("currentRegionCode") == regionCode) {
            Log.forestPatrol("巡护地图已切换[$mapCode → $targetCode]")
        } else {
            Log.error(TAG, "巡护切图结果未确认，目标[$targetCode] raw=$refreshed")
        }
    }

    private class MonopolyTaskAdapter(
        private val regionCode: String,
        private val sceneCode: String,
    ) : TaskFlowAdapter {
        override val moduleName: String = "AntForestPatrol"
        override val flowName: String = "新版巡护任务"

        override fun query(): JSONObject = parsePatrolResponse(AntForestPatrolRpcCall.listMonopolyTasks(regionCode, sceneCode))
        override fun isQuerySuccess(response: JSONObject): Boolean =
            response.optBoolean("success") && response.optJSONArray("taskInfoList") != null

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val tasks = response.getJSONArray("taskInfoList")
            return (0 until tasks.length()).map { index ->
                val task = tasks.getJSONObject(index)
                val base = task.getJSONObject("taskBaseInfo")
                val rights = task.getJSONObject("taskRights")
                val type = base.getString("taskType")
                val taskScene = base.getString("sceneCode")
                require(type.isNotBlank() && taskScene.isNotBlank()) { "巡护任务场景或标识无效:$task" }
                val bizInfo = base.optString("bizInfo").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                TaskFlowItem(
                    id = type,
                    title = bizInfo?.optString("title")?.takeIf { it.isNotBlank() } ?: type,
                    status = base.getString("taskStatus"),
                    type = type,
                    sceneCode = taskScene,
                    actionType = base.optString("taskProdPlayType"),
                    raw = task,
                    current = rights.optInt("rightsTimes"),
                    limit = rights.optInt("rightsTimesLimit"),
                    progress = "rights=${rights.optInt("rightsTimes")}/${rights.optInt("rightsTimesLimit")} " +
                        "received=${rights.optInt("alreadyReceiveAwardCount")} " +
                        "task=${base.optInt("taskProgress")}/${base.optInt("taskRequire")}",
                )
            }
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when (item.status) {
            "FINISHED" -> TaskFlowPhase.REWARD_READY
            "RECEIVED" -> TaskFlowPhase.TERMINAL
            "TODO" -> TaskFlowPhase.READY_TO_COMPLETE
            else -> TaskFlowPhase.UNKNOWN
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) {
                return TaskFlowActionResult.failure(TaskRpcFailureType.RETRYABLE_RPC, message = "巡护已中断", stopCurrentRound = true)
            }
            return actionResult(AntForestPatrolRpcCall.finishMonopolyTask(item.type, item.sceneCode), "finishTaskopengreen")
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult =
            actionResult(AntForestPatrolRpcCall.receiveMonopolyTask(item.type, item.sceneCode), "receiveTaskAwardopengreen")

        private fun actionResult(raw: String, rpc: String): TaskFlowActionResult {
            val response = parsePatrolResponse(raw)
            if (response.optBoolean("success")) return TaskFlowActionResult.success(progressChanged = false)
            val code = response.optString("resultCode").ifBlank { response.optString("errorCode") }
                .ifBlank { response.optString("code") }
            val message = response.optString("resultDesc").ifBlank { response.optString("errorMsg") }
                .ifBlank { response.optString("desc") }
            val failure = when {
                code == "400000040" -> TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
                response.optBoolean("retriable") || response.optBoolean("canRetry") -> TaskRpcFailureType.RETRYABLE_RPC
                response.opt("retriable") == false || response.opt("canRetry") == false -> TaskRpcFailureType.NON_RETRYABLE_INVALID
                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
            return TaskFlowActionResult.failure(
                failureType = failure,
                code = code,
                message = message,
                rpc = rpc,
                raw = response.toString(),
                continueCurrentRoundOnFailure = true,
            ).copy(refreshAfterAction = true, progressChanged = false)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String =
            "${action.logName}:${item.sceneCode}:${item.id}:${item.status}:${item.progress}"
        override fun logInfo(message: String) = Log.forestPatrol(message)
        override fun logError(message: String) = Log.error(TAG, message)
    }
}
