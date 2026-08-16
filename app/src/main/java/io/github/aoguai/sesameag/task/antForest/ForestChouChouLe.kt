package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GlobalThreadPools.sleepCompat
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 森林寻宝任务处理类 (每天自动执行, 完成后标记)
 */
class ForestChouChouLe {
    companion object {
        private const val TAG = "ForestChouChouLe"
        private const val SOURCE = "task_entry"
        private const val FOREST_BLACKLIST_MODULE = "蚂蚁森林"

        // 场景代码常量
        private const val SCENE_NORMAL = "ANTFOREST_NORMAL_DRAW"
        private const val SCENE_ACTIVITY = "ANTFOREST_ACTIVITY_DRAW"
        private const val TASK_AWARD_ALREADY_FINISHED_CODE = "400000030"
        private const val TASK_ALREADY_FINISHED_CODE = "2600000016"
        private const val TASK_RIGHTS_LIMIT_CODE = "400000012"
        private const val RPC_UNSUPPORTED_CODE = "400000040"

        /**
         * 抽奖场景数据类
         */
        private data class Scene(
            val id: String,
            val code: String,
            val name: String,
            val flag: String,
        ) {
            val taskCode get() = "${code}_TASK"
        }

        private enum class CompletionFlagCheck {
            ACTIONABLE,
            NO_ACTIONABLE,
            UNKNOWN,
        }

        private data class SceneState(
            val check: CompletionFlagCheck,
            val fingerprint: String = "",
        )

        // 扩展函数：简化 JSON 解析和检查
        private fun String.toJson(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

        private fun JSONObject.check(): Boolean = ResChecker.checkRes(TAG, this)

        private fun JSONObject.taskResultCode(): String = optString("code").ifBlank { optString("resultCode") }

        private fun JSONObject.taskResultDesc(): String =
            sequenceOf(
                optString("desc"),
                optString("resultDesc"),
                optString("resultMessage"),
                optString("memo"),
            ).firstOrNull { it.isNotBlank() }.orEmpty()

        private fun JSONObject.isTaskAwardAlreadyFinished(): Boolean =
            taskResultCode() == TASK_AWARD_ALREADY_FINISHED_CODE

        private fun JSONObject.isTaskAlreadyFinished(): Boolean =
            taskResultCode() == TASK_ALREADY_FINISHED_CODE

        private fun JSONObject.isTaskRightsLimitReached(): Boolean =
            taskResultCode() == TASK_RIGHTS_LIMIT_CODE

        private fun JSONObject.isRpcUnsupported(): Boolean =
            taskResultCode() == RPC_UNSUPPORTED_CODE

        private fun extractTaskName(
            bizInfo: JSONObject,
            fallback: String,
        ): String =
            bizInfo
                .optString("title")
                .ifBlank { bizInfo.optString("taskTitle") }
                .ifBlank { bizInfo.optString("taskContent") }
                .ifBlank { fallback }

        // 动态获取抽奖场景配置
        private fun getScenes(): List<Scene> =
            runCatching {
                val scenes = mutableListOf<Scene>()
                val response =
                    AntForestRpcCall.enterDrawActivityopengreen("", SCENE_NORMAL, SOURCE).toJson()
                        ?: run {
                            Log.error(TAG, "森林抽抽乐场景发现响应为空或非法")
                            return@runCatching emptyList()
                        }

                if (!response.optBoolean("success", false)) {
                    Log.error(TAG, "森林抽抽乐场景发现失败")
                    return@runCatching emptyList()
                }
                val drawSceneGroups = response.optJSONArray("drawSceneGroups")
                    ?: run {
                        Log.error(TAG, "森林抽抽乐场景发现缺少 drawSceneGroups")
                        return@runCatching emptyList()
                    }

                for (i in 0 until drawSceneGroups.length()) {
                    val sceneGroup = drawSceneGroups.optJSONObject(i) ?: continue
                    val drawActivity = sceneGroup.optJSONObject("drawActivity") ?: continue

                    val sceneCode = drawActivity.optString("sceneCode")
                    val activityId = drawActivity.optString("activityId")
                    if (sceneCode.isBlank() || activityId.isBlank()) {
                        continue
                    }
                    val name = sceneGroup.optString("name", "未知活动")

                    val flag =
                        when (sceneCode) {
                            SCENE_NORMAL -> {
                                StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED
                            }

                            SCENE_ACTIVITY -> {
                                StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED
                            }

                            else -> {
                                StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_PREFIX +
                                    sceneCode.lowercase(Locale.ROOT) +
                                    StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_SUFFIX
                            }
                        }
                    scenes.add(Scene(activityId, sceneCode, name, flag))
                }
                if (scenes.isEmpty()) {
                    Log.error(TAG, "森林抽抽乐场景发现成功但没有有效场景")
                }
                scenes.distinctBy { scene -> "${scene.code}#${scene.id}" }
            }.getOrElse {
                Log.printStackTrace(TAG, "获取抽奖场景配置失败，本轮不处理", it)
                emptyList()
            }

        private fun normalizeTaskInfoList(response: JSONObject): JSONObject {
            if (response.optJSONArray("taskInfoList") != null) {
                return response
            }
            val taskGroupList = response.optJSONArray("taskGroupInfoList") ?: return response
            val flattened = JSONArray()
            for (i in 0 until taskGroupList.length()) {
                val group = taskGroupList.optJSONObject(i) ?: continue
                val groupTaskList = group.optJSONArray("taskInfoList") ?: continue
                for (j in 0 until groupTaskList.length()) {
                    groupTaskList.optJSONObject(j)?.let { flattened.put(it) }
                }
            }
            if (flattened.length() > 0) {
                response.put("taskInfoList", flattened)
            }
            return response
        }
    }

    fun chouChouLe() {
        runCatching {
            val scenes = getScenes()
            Log.forest("开始处理森林寻宝, 共 ${scenes.size} 个场景")
            scenes.forEach {
                processScene(it)
                sleepCompat(100L)
            }
        }.onFailure { Log.printStackTrace(TAG, "执行异常", it) }
    }

    private fun processScene(s: Scene) =
        runCatching {
            if (Status.hasFlagToday(s.flag)) {
                when (inspectSceneState(s).check) {
                    CompletionFlagCheck.NO_ACTIONABLE -> {
                        Log.forest("⏭️ ${s.name} 今天已完成, 跳过")
                        return@runCatching
                    }

                    CompletionFlagCheck.UNKNOWN -> {
                        Log.forest("⏭️ ${s.name} 完成标记复核失败，保留后续重试机会")
                        return@runCatching
                    }

                    CompletionFlagCheck.ACTIONABLE -> {
                        Status.removeFlag(s.flag)
                    }
                }
            }

            Log.forest("👉 开始处理: ${s.name}")

            // 1. 检查活动有效期
            val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
            if (enterResp == null) {
                Log.error(TAG, "${s.name} 抽抽乐入口响应为空或非法")
                return@runCatching
            }
            if (!enterResp.check()) {
                Log.error(TAG, "${s.name} 抽抽乐入口响应失败")
                return@runCatching
            }

            val drawActivity = enterResp.optJSONObject("drawActivity")
            if (drawActivity != null) {
                val now = System.currentTimeMillis()
                val startTime = drawActivity.optLong("startTime")
                val endTime = drawActivity.optLong("endTime")
                if (now !in startTime..endTime) {
                    Log.forest("⛔ ${s.name} 活动不在有效期内, 跳过")
                    return@runCatching
                }
            }

            // 2. 任务流和抽奖余额是独立闭环；仅统一离线中断可以阻止后续 RPC。
            val seenStateFingerprints = mutableSetOf<String>()
            while (true) {
                val stateBefore = inspectSceneState(s)
                if (stateBefore.check == CompletionFlagCheck.ACTIONABLE &&
                    !seenStateFingerprints.add(stateBefore.fingerprint)
                ) {
                    Status.removeFlag(s.flag)
                    Log.forest("${s.name} 抽抽乐状态未收敛，停止重复请求并保留后续重试")
                    return@runCatching
                }

                val taskResult = TaskFlowEngine(ChouChouLeTaskFlowAdapter(s), roundSleepMs = 500L).run()
                val lotteryHandled =
                    if (taskResult.interrupted) {
                        Log.forest("${s.name} 任务流因离线中断，跳过本轮抽奖")
                        false
                    } else {
                        if (taskResult.stopped) {
                            Log.forest("${s.name} 任务流停止但非离线，继续复核已确认抽奖余额")
                        }
                        processLottery(s)
                    }

                if (!lotteryHandled) {
                    Status.removeFlag(s.flag)
                    Log.forest("${s.name} 抽奖闭环未完成，保留后续重试")
                    return@runCatching
                }

                val sceneState = inspectSceneState(s)
                when (sceneState.check) {
                    CompletionFlagCheck.NO_ACTIONABLE -> {
                        Status.setFlagToday(s.flag)
                        return@runCatching
                    }

                    CompletionFlagCheck.UNKNOWN -> {
                        Status.removeFlag(s.flag)
                        Log.forest("${s.name} 抽奖后状态复核失败，保留后续重试")
                        return@runCatching
                    }

                    CompletionFlagCheck.ACTIONABLE -> {
                        if (sceneState.fingerprint in seenStateFingerprints) {
                            Status.removeFlag(s.flag)
                            Log.forest("${s.name} 抽抽乐状态未收敛，停止重复请求并保留后续重试")
                            return@runCatching
                        }
                        Log.forest("${s.name} 抽奖后仍有可执行状态，重新进入任务流")
                    }
                }
            }
        }.onFailure { Log.printStackTrace(TAG, "${s.name} 处理异常", it) }

    private fun fetchFreshTaskList(s: Scene): JSONObject? =
        AntForestRpcCall
            .listTaskopengreen(
                sceneCode = s.taskCode,
                source = SOURCE,
                extend = JSONObject().put("appMode", "normal"),
            )
            .toJson()
            ?.let { normalizeTaskInfoList(it) }

    private fun inspectSceneState(s: Scene): SceneState {
        val resp = fetchFreshTaskList(s) ?: return SceneState(CompletionFlagCheck.UNKNOWN)
        if (!resp.check()) return SceneState(CompletionFlagCheck.UNKNOWN)

        val taskList = resp.optJSONArray("taskInfoList") ?: return SceneState(CompletionFlagCheck.UNKNOWN)
        val stateParts = mutableListOf<String>()
        var actionableTask = false
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
            val taskType = baseInfo.optString("taskType")
            if (taskType.isBlank()) continue
            val taskStatus = baseInfo.optString("taskStatus").uppercase(Locale.ROOT)
            if (taskStatus !in setOf(
                    TaskStatus.TODO.name,
                    TaskStatus.FINISHED.name,
                    "COMPLETE",
                    "WAIT_COMPLETE",
                    "WAIT_RECEIVE",
                    "TO_RECEIVE",
                )
            ) {
                continue
            }

            val taskRights = task.optJSONObject("taskRights") ?: JSONObject()
            val rightsTimes = taskRights.optInt("rightsTimes", 0)
            val rightsTimesLimit = taskRights.optInt("rightsTimesLimit", 0)
            stateParts += "$taskType:$taskStatus:$rightsTimes:$rightsTimesLimit"
            if (!isBlockedTask(taskType) &&
                (taskStatus in setOf("FINISHED", "COMPLETE", "WAIT_RECEIVE", "TO_RECEIVE") || isAutomatableDrawTask(baseInfo))
            ) {
                val bizInfo = baseInfo.optString("bizInfo").toJson() ?: JSONObject()
                val taskName = extractTaskName(bizInfo, taskType)
                Log.forest("${s.name} 发现待处理任务: $taskName [$taskStatus]")
                actionableTask = true
            }
        }

        val activityResponse = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
        if (activityResponse == null) {
            Log.error(TAG, "${s.name} 状态复核入口响应为空或非法")
            return SceneState(CompletionFlagCheck.UNKNOWN)
        }
        if (!activityResponse.check()) {
            Log.error(TAG, "${s.name} 状态复核入口响应失败")
            return SceneState(CompletionFlagCheck.UNKNOWN)
        }
        val drawBalance = drawBalance(activityResponse)
        if (drawBalance == null) {
            Log.error(TAG, "${s.name} 状态复核缺少 drawAsset.blance")
            return SceneState(CompletionFlagCheck.UNKNOWN)
        }
        stateParts += "drawBalance=$drawBalance"
        val fingerprint = stateParts.sorted().joinToString("|")
        return SceneState(
            check = if (actionableTask || drawBalance > 0) CompletionFlagCheck.ACTIONABLE else CompletionFlagCheck.NO_ACTIONABLE,
            fingerprint = fingerprint,
        )
    }

    private fun isAutomatableDrawTask(taskBaseInfo: JSONObject): Boolean {
        val taskStatus = taskBaseInfo.optString("taskStatus").uppercase(Locale.ROOT)
        if (taskStatus !in setOf(TaskStatus.TODO.name, "WAIT_COMPLETE")) {
            return false
        }
        val bizInfo = taskBaseInfo.optString("bizInfo").toJson() ?: JSONObject()
        val prodPlayParam = taskBaseInfo.optString("prodPlayParam").toJson() ?: JSONObject()
        val exchangeTask = taskBaseInfo.optString("taskProdPlayType") == "EXCHANGE_ASSET" &&
            bizInfo.optJSONObject("exchangeAssetsInfo") != null &&
            prodPlayParam.optString("acwSceneCode") == "VITALITY_EXCHANGE_DRAW"
        if (exchangeTask) {
            return true
        }
        if (taskBaseInfo.optString("taskProdPlayType") in setOf("VISIT_FLOAT_BALL", "CALL_APP_OUT_TASK")) {
            return false
        }
        if (bizInfo.has("autoCompleteTask") && !bizInfo.optBoolean("autoCompleteTask")) {
            return false
        }
        if (taskBaseInfo.optString("taskMode") != "ACC_ANTIEP") {
            return true
        }
        return prodPlayParam.optJSONObject("taskCategorization")
            ?.optString("categorizationSecondLevel") != "Game"
    }

    private fun drawBalance(response: JSONObject): Int? =
        response.optJSONObject("drawAsset")
            ?.takeIf { it.has("blance") }
            ?.optInt("blance")

    /**
     * 执行抽奖逻辑
     */
    private fun processLottery(s: Scene): Boolean {
        val currentUid =
            UserMap.currentUid ?: run {
                Log.error(TAG, "${s.name} 抽奖跳过: 当前会话 UID 缺失")
                return false
            }
        val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
        if (enterResp == null) {
            Log.error(TAG, "${s.name} 抽奖入口响应为空或非法")
            return false
        }
        if (!enterResp.check()) {
            Log.error(TAG, "${s.name} 抽奖入口响应失败")
            return false
        }

        var balance = drawBalance(enterResp) ?: run {
            Log.error(TAG, "${s.name} 抽奖入口缺少 drawAsset.blance")
            return false
        }
        val total = enterResp.optJSONObject("drawAsset")?.optInt("totalTimes", 0) ?: 0
        val batchDrawEnabled =
            enterResp
                .optJSONObject("drawScene")
                ?.optJSONObject("extInfo")
                ?.optString("openBatchDraw") == "Y"

        Log.forest("${s.name} 剩余抽奖次数: $balance / $total")

        if (balance > 0 && batchDrawEnabled) {
            val batchResp = AntForestRpcCall.batchDrawopengreen(s.id, s.code, SOURCE, balance, currentUid).toJson()
            if (batchResp == null) {
                Log.error(TAG, "${s.name} 批量抽奖响应为空或非法")
                return false
            }
            if (!batchResp.check()) {
                Log.error(TAG, "${s.name} 批量抽奖响应失败")
                return false
            }
            balance = drawBalance(batchResp) ?: run {
                Log.error(TAG, "${s.name} 批量抽奖响应缺少 drawAsset.blance")
                return false
            }
            logBatchDrawResults(s, batchResp.optJSONArray("drawResultList"), balance)
        } else {
            var retry = 0
            // 未声明批量能力的场景保留既有逐次抽奖闭环。
            while (balance > 0 && retry < 50) {
                retry++
                Log.forest("${s.name} 第 $retry 次抽奖")

                val drawResp = AntForestRpcCall.drawopengreen(s.id, s.code, SOURCE, currentUid).toJson()
                if (drawResp == null) {
                    Log.error(TAG, "${s.name} 单次抽奖响应为空或非法")
                    return false
                }
                if (!drawResp.check()) {
                    Log.error(TAG, "${s.name} 单次抽奖响应失败")
                    return false
                }

                balance = drawBalance(drawResp) ?: run {
                    Log.error(TAG, "${s.name} 单次抽奖响应缺少 drawAsset.blance")
                    return false
                }
                val prize = drawResp.optJSONObject("prizeVO")
                if (prize != null) {
                    val name = prize.optString("prizeName", "未知奖品")
                    val num = prize.optInt("prizeNum", 1)
                    Log.forest("${s.name} 🎁 [获得: $name * $num] 剩余次数: $balance")
                }

                if (balance > 0) sleepCompat(500L)
            }
            if (balance > 0) {
                Log.error(TAG, "${s.name} 单次抽奖达到轮次上限，剩余次数: $balance")
                return false
            }
        }

        val refreshed = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
        if (refreshed == null) {
            Log.error(TAG, "${s.name} 抽奖后余额回查响应为空或非法")
            return false
        }
        if (!refreshed.check()) {
            Log.error(TAG, "${s.name} 抽奖后余额回查失败")
            return false
        }
        val remainingBalance = drawBalance(refreshed)
        if (remainingBalance == null) {
            Log.error(TAG, "${s.name} 抽奖后余额回查缺少 drawAsset.blance")
            return false
        }
        if (remainingBalance != 0) {
            Log.error(TAG, "${s.name} 抽奖后余额未清零，剩余次数: $remainingBalance")
            return false
        }
        return true
    }

    private fun logBatchDrawResults(
        scene: Scene,
        results: JSONArray?,
        balance: Int,
    ) {
        if (results == null) {
            Log.forest("${scene.name} 批量抽奖完成，剩余次数: $balance")
            return
        }
        for (index in 0 until results.length()) {
            val prize = results.optJSONObject(index)?.optJSONObject("prizeVO") ?: continue
            val name = prize.optString("prizeName", "未知奖品")
            val num = prize.optInt("prizeNum", 1)
            Log.forest("${scene.name} 🎁 [批量获得: $name * $num]")
        }
        Log.forest("${scene.name} 批量抽奖完成，剩余次数: $balance")
    }

    /**
     * 寻宝仍保留场景发现和抽奖，但任务推进统一复用公共闭环。
     * 只依据服务端的结构化状态、类型和 RPC 响应分类，不根据标题或跳转地址预判。
     */
    private inner class ChouChouLeTaskFlowAdapter(
        private val scene: Scene,
    ) : TaskFlowAdapter {
        override val moduleName: String = FOREST_BLACKLIST_MODULE
        override val flowName: String = scene.name

        override fun query(): JSONObject = fetchFreshTaskList(scene) ?: JSONObject().put("success", false).put("message", "任务列表返回空")

        override fun isQuerySuccess(response: JSONObject): Boolean = response.check()

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONArray("taskInfoList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (index in 0 until taskList.length()) {
                val taskInfo = taskList.optJSONObject(index) ?: continue
                val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: continue
                val taskType = taskBaseInfo.optString("taskType")
                val sceneCode = taskBaseInfo.optString("sceneCode")
                if (taskType.isBlank() || sceneCode.isBlank()) {
                    continue
                }
                val bizInfo = taskBaseInfo.optString("bizInfo").toJson() ?: JSONObject()
                val taskRights = taskInfo.optJSONObject("taskRights") ?: JSONObject()
                val rightsTimes = taskRights.optInt("rightsTimes", 0)
                val rightsTimesLimit = taskRights.optInt("rightsTimesLimit", 0)
                val title = extractTaskName(bizInfo, taskType)
                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = title,
                        status = taskBaseInfo.optString("taskStatus"),
                        type = taskType,
                        sceneCode = sceneCode,
                        blacklistKeys = listOf(taskType).filter { it.isNotBlank() },
                        progress = "$rightsTimes/$rightsTimesLimit",
                        current = rightsTimes,
                        limit = rightsTimesLimit.takeIf { it > 0 },
                        raw =
                            JSONObject()
                                .put("taskInfo", taskInfo)
                                .put("taskBaseInfo", taskBaseInfo)
                                .put("bizInfo", bizInfo),
                    ),
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when (item.status.uppercase(Locale.ROOT)) {
                TaskStatus.FINISHED.name, "COMPLETE", "WAIT_RECEIVE", "TO_RECEIVE" -> {
                    TaskFlowPhase.REWARD_READY
                }

                TaskStatus.TODO.name, "WAIT_COMPLETE" -> {
                    if (requiresExternalBusinessAction(item)) {
                        TaskFlowPhase.BUSINESS_ACTION
                    } else {
                        TaskFlowPhase.READY_TO_COMPLETE
                    }
                }

                TaskStatus.RECEIVED.name, "HAS_RECEIVED", "DONE", "COMPLETED" -> {
                    TaskFlowPhase.TERMINAL
                }

                else -> {
                    TaskFlowPhase.UNKNOWN
                }
            }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val taskBaseInfo = taskBaseInfo(item) ?: return missingTaskData(item, "complete")
            val exchangeDrawTask = isExchangeDrawTask(item)
            val response =
                if (exchangeDrawTask) {
                    val useAssetsCount = taskProdPlayParam(item).optInt("useAssetsCount", 0)
                    Log.forest("${scene.name} 准备兑换抽奖机会[活力值:$useAssetsCount][次数:${item.current ?: 0}/${item.limit ?: 0}]")
                    AntForestRpcCall
                        .exchangeTimesFromTaskopengreen(
                            scene.id,
                            scene.code,
                            SOURCE,
                            item.sceneCode,
                            item.type,
                        ).toJson()
                } else {
                    val source = taskBaseInfo.optString("source").ifBlank { SOURCE }
                    AntForestRpcCall.finishTaskopengreen(item.type, item.sceneCode, source).toJson()
                }
            return handleActionResponse(
                item = item,
                response = response,
                action = TaskFlowAction.COMPLETE,
                rpc =
                    if (exchangeDrawTask) {
                        "AntForestRpcCall.exchangeTimesFromTaskopengreen"
                    } else {
                        "AntForestRpcCall.finishTaskopengreen"
                    },
            )
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val taskBaseInfo =
                item.raw?.optJSONObject("taskBaseInfo")
                    ?: return missingTaskData(item, "receive")
            val rawTask =
                JSONObject(taskBaseInfo.toString()).apply {
                    if (optString("sceneCode").isBlank()) put("sceneCode", item.sceneCode)
                    if (optString("source").isBlank()) put("source", SOURCE)
                    if (optString("taskType").isBlank()) put("taskType", item.type)
                }
            return handleActionResponse(
                item = item,
                response = AntForestRpcCall.receiveTaskAwardopengreen(rawTask).toJson(),
                action = TaskFlowAction.RECEIVE,
                rpc = "AntForestRpcCall.receiveTaskAwardopengreen",
            )
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String = "${action.logName}:${item.sceneCode}#${item.type}:${item.current ?: 0}"

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (action == TaskFlowAction.RECEIVE ||
                (action == TaskFlowAction.COMPLETE && isExchangeDrawTask(item))
            ) {
                syncDrawAssetAfterTaskAward(scene)
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            val summary = if (snapshot.totalTasks == 0) "无有效任务" else "全部完成"
            Log.forest("✅ ${scene.name} $summary (${snapshot.completedTasks}/${snapshot.totalTasks})")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "${scene.name} 任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.forest(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun handleActionResponse(
            item: TaskFlowItem,
            response: JSONObject?,
            action: TaskFlowAction,
            rpc: String,
        ): TaskFlowActionResult {
            if (response == null) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = "EMPTY_RESPONSE",
                    message = "${action.logName}返回空",
                    rpc = rpc,
                    detail = actionDetail(item, action),
                )
            }
            when {
                response.isTaskAwardAlreadyFinished() || response.isTaskAlreadyFinished() -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.TERMINAL_DONE,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.isTaskRightsLimitReached() -> {
                    val message = response.taskResultDesc().ifBlank { "任务权益次数已达上限" }
                    Log.forest(
                        "${scene.name} ${item.title} 权益次数已达上限，延后当前任务[code=${response.taskResultCode()}]",
                    )
                    return TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.CAPACITY_LIMIT,
                        message = "$message [code=${response.taskResultCode()}]",
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.isRpcUnsupported() -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.check() -> {
                    val actionName = if (action == TaskFlowAction.RECEIVE) "奖励领取成功" else "任务已提交"
                    Log.forest("${scene.name} $actionName: ${item.title}")
                    return TaskFlowActionResult.success(refreshAfterAction = true)
                }

                response.optBoolean("retriable") || response.optBoolean("retryable") -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                else -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }
            }
        }

        private fun taskBaseInfo(item: TaskFlowItem): JSONObject? =
            item.raw?.optJSONObject("taskBaseInfo")

        private fun taskBizInfo(item: TaskFlowItem): JSONObject =
            item.raw?.optJSONObject("bizInfo") ?: JSONObject()

        private fun taskProdPlayParam(item: TaskFlowItem): JSONObject =
            taskBaseInfo(item)?.optString("prodPlayParam")?.toJson() ?: JSONObject()

        private fun isExchangeDrawTask(item: TaskFlowItem): Boolean {
            val taskBaseInfo = taskBaseInfo(item) ?: return false
            return taskBaseInfo.optString("taskProdPlayType") == "EXCHANGE_ASSET" &&
                taskBizInfo(item).optJSONObject("exchangeAssetsInfo") != null &&
                taskProdPlayParam(item).optString("acwSceneCode") == "VITALITY_EXCHANGE_DRAW"
        }

        /**
         * 服务端明确标记为外部业务的任务不伪造完成；保留任务状态以等待服务端自行推进。
         */
        private fun requiresExternalBusinessAction(item: TaskFlowItem): Boolean {
            if (isExchangeDrawTask(item)) {
                return false
            }
            val taskBaseInfo = taskBaseInfo(item) ?: return false
            when (taskBaseInfo.optString("taskProdPlayType")) {
                "VISIT_FLOAT_BALL", "CALL_APP_OUT_TASK" -> return true
            }
            val bizInfo = taskBizInfo(item)
            if (bizInfo.has("autoCompleteTask") && !bizInfo.optBoolean("autoCompleteTask")) {
                return true
            }
            if (taskBaseInfo.optString("taskMode") != "ACC_ANTIEP") {
                return false
            }
            return taskProdPlayParam(item)
                .optJSONObject("taskCategorization")
                ?.optString("categorizationSecondLevel") == "Game"
        }

        private fun missingTaskData(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少寻宝任务原始数据",
                rpc = "ChouChouLeTaskFlowAdapter.$action",
                detail = actionDetail(item, null),
            )

        private fun actionDetail(
            item: TaskFlowItem,
            action: TaskFlowAction?,
        ): String = "sceneCode=${item.sceneCode} taskType=${item.type} taskName=${item.title} action=${action?.logName.orEmpty()}"
    }

    /**
     * 判断任务是否在屏蔽列表中
     */
    private fun isBlockedTask(taskType: String): Boolean =
        taskType.isNotBlank() && TaskBlacklist.isTaskInBlacklist(FOREST_BLACKLIST_MODULE, taskType)

    private fun syncDrawAssetAfterTaskAward(s: Scene) {
        runCatching {
            val res = AntForestRpcCall.drawSyncopengreen(s.id, s.code, "taskaward").toJson()
            if (res != null && res.check()) {
                val balance = res.optJSONObject("drawAsset")?.optInt("blance", 0) ?: 0
                Log.forest("${s.name} 奖励后刷新抽奖次数: $balance")
            }
        }.onFailure {
            Log.printStackTrace(TAG, "${s.name} 奖励后刷新抽奖次数失败", it)
        }
    }
}
