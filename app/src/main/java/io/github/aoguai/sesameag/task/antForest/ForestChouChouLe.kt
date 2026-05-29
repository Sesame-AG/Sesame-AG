package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.util.GlobalThreadPools.sleepCompat
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        private const val RPC_LIST_TASK_OPEN_GREEN = "com.alipay.antieptask.listTaskopengreen"

        /**
         * 抽奖场景数据类
         */
        private data class Scene(
            val id: String,
            val code: String,
            val name: String,
            val flag: String
        ) {
            val taskCode get() = "${code}_TASK"
        }

        // 扩展函数：简化 JSON 解析和检查
        private fun String.toJson(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()
        private fun JSONObject.check(): Boolean = ResChecker.checkRes(TAG, this)
        private fun JSONObject.taskResultCode(): String = optString("code").ifBlank { optString("resultCode") }
        private fun JSONObject.taskResultDesc(): String {
            return sequenceOf(
                optString("desc"),
                optString("resultDesc"),
                optString("resultMessage"),
                optString("memo")
            ).firstOrNull { it.isNotBlank() }.orEmpty()
        }

        private fun JSONObject.isTaskAwardAlreadyFinished(): Boolean {
            val code = taskResultCode()
            val desc = taskResultDesc()
            return code == TASK_AWARD_ALREADY_FINISHED_CODE || desc.contains("任务已完结")
        }

        private fun JSONObject.isTaskAlreadyFinished(): Boolean {
            val code = taskResultCode()
            return code == TASK_ALREADY_FINISHED_CODE ||
                listOf(
                    optString("desc"),
                    optString("resultDesc"),
                    optString("resultMessage"),
                    optString("memo")
                ).any { it.contains("任务已完成") || it.contains("任务已完结") }
        }

        private fun JSONObject.isTaskRightsLimitReached(): Boolean {
            val desc = taskResultDesc()
            return taskResultCode() == TASK_RIGHTS_LIMIT_CODE || desc.contains("权益获取次数超过上限")
        }

        private fun JSONObject.isRpcUnsupported(): Boolean {
            val desc = taskResultDesc()
            return taskResultCode() == RPC_UNSUPPORTED_CODE || desc.contains("不支持rpc调用", ignoreCase = true)
        }

        private fun extractTaskName(bizInfo: JSONObject, fallback: String): String {
            return bizInfo.optString("title")
                .ifBlank { bizInfo.optString("taskTitle") }
                .ifBlank { bizInfo.optString("taskContent") }
                .ifBlank { fallback }
        }

        // 动态获取抽奖场景配置
        private fun getScenes(): List<Scene> {
            val defaultScenes = listOf(
                Scene("2026040301", SCENE_NORMAL, "森林抽抽乐普通版", StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED),
                Scene("20260423", SCENE_ACTIVITY, "森林抽抽乐活动版", StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED)
            )

            return runCatching {
                val scenes = mutableListOf<Scene>()
                // 使用普通场景查询
                val response = AntForestRpcCall.enterDrawActivityopengreen("", SCENE_NORMAL, SOURCE).toJson() ?: return@runCatching defaultScenes

                if (response.optBoolean("success", false)) {
                    val drawSceneGroups = response.optJSONArray("drawSceneGroups") ?: return@runCatching defaultScenes

                    for (i in 0 until drawSceneGroups.length()) {
                        val sceneGroup = drawSceneGroups.optJSONObject(i) ?: continue
                        val drawActivity = sceneGroup.optJSONObject("drawActivity") ?: continue

                        val activityId = drawActivity.optString("activityId")
                        val sceneCode = drawActivity.optString("sceneCode")
                        val name = sceneGroup.optString("name", "未知活动")

                        val flag = when (sceneCode) {
                            SCENE_NORMAL -> StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED
                            SCENE_ACTIVITY -> StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED
                            else -> StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_PREFIX +
                                sceneCode.lowercase(Locale.getDefault()) +
                                StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_SUFFIX
                        }
                        scenes.add(Scene(activityId, sceneCode, name, flag))
                    }
                }
                if (scenes.isEmpty()) defaultScenes else scenes
            }.getOrElse {
                Log.printStackTrace(TAG, "获取抽奖场景配置失败, 使用默认配置", it)
                defaultScenes
            }
        }
    }

    private val taskTryCount = ConcurrentHashMap<String, AtomicInteger>()
    private val rewardHandledTaskKeys = ConcurrentHashMap.newKeySet<String>()

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

    private fun processScene(s: Scene) = runCatching {
        if (Status.hasFlagToday(s.flag)) {
            if (!hasActionableTaskAfterCompletionFlag(s)) {
                Log.forest("⏭️ ${s.name} 今天已完成, 跳过")
                return@runCatching
            }
            Status.removeFlag(s.flag)
        }

        rewardHandledTaskKeys.clear()
        Log.forest("👉 开始处理: ${s.name}")

        // 1. 检查活动有效期
        val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
        if (enterResp == null || !enterResp.check()) return@runCatching

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

        // 2. 循环处理任务 (执行 -> 领取)
        processTasksLoop(s)

        // 3. 执行抽奖
        processLottery(s)

        // 4. 最终检查完成状态
        checkCompletion(s)

    }.onFailure { Log.printStackTrace(TAG, "${s.name} 处理异常", it) }

    private fun fetchFreshTaskList(s: Scene): JSONObject? {
        return AntForestRpcCall.listTaskopengreen(s.taskCode, SOURCE).toJson()
    }

    private fun hasActionableTaskAfterCompletionFlag(s: Scene): Boolean {
        val resp = fetchFreshTaskList(s) ?: return false
        if (!resp.check()) return false

        val taskList = resp.optJSONArray("taskInfoList") ?: return false
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
            val taskType = baseInfo.optString("taskType")
            val taskStatus = baseInfo.optString("taskStatus")
            if (taskStatus != TaskStatus.TODO.name &&
                taskStatus != TaskStatus.FINISHED.name &&
                taskStatus != "COMPLETE"
            ) {
                continue
            }
            if (taskStatus == TaskStatus.TODO.name && !isExecutableTodoTaskType(taskType)) {
                continue
            }

            val bizInfo = baseInfo.optString("bizInfo").toJson() ?: JSONObject()
            val taskName = extractTaskName(bizInfo, taskType.ifBlank { "未知任务" })
            if (!isBlockedTask(taskType, taskName)) {
                Log.forest("${s.name} 已有完成标记但发现待处理任务: $taskName [$taskStatus]")
                return true
            }
        }
        return false
    }

    /**
     * 循环处理任务列表
     */
    private fun processTasksLoop(s: Scene) {
        var loop = 0
        var unchangedCount = 0
        while (unchangedCount < 3) {
            loop++
            Log.forest("${s.name} 第 $loop 轮任务检查")
            val tasksResp = fetchFreshTaskList(s) ?: break
            if (!tasksResp.check()) break

            val taskList = tasksResp.optJSONArray("taskInfoList") ?: break
            var hasChange = false

            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                if (processSingleTask(s, task)) {
                    hasChange = true
                }
            }

            if (!hasChange) {
                unchangedCount++
                Log.forest("${s.name} 本轮无任务状态变更, 连续 $unchangedCount / 3")
            } else {
                unchangedCount = 0
            }
            if (unchangedCount < 3) sleepCompat(100L)
        }
        Log.forest("${s.name} 连续 3 轮无任务状态变更, 结束任务循环")
    }

    /**
     * 执行抽奖逻辑
     */
    private fun processLottery(s: Scene) {
        val currentUid = UserMap.currentUid ?: return
        val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson() ?: return
        if (!enterResp.check()) return

        val drawAsset = enterResp.optJSONObject("drawAsset") ?: return
        var balance = drawAsset.optInt("blance", 0)
        val total = drawAsset.optInt("totalTimes", 0)

        Log.forest("${s.name} 剩余抽奖次数: $balance / $total")

        var retry = 0
        // 最多抽50次，防止死循环
        while (balance > 0 && retry < 50) {
            retry++
            Log.forest("${s.name} 第 $retry 次抽奖")

            val drawResp = AntForestRpcCall.drawopengreen(s.id, s.code, SOURCE, currentUid).toJson()
            if (drawResp == null || !drawResp.check()) {
                break
            }

            balance = drawResp.optJSONObject("drawAsset")?.optInt("blance", 0) ?: 0
            val prize = drawResp.optJSONObject("prizeVO")
            if (prize != null) {
                val name = prize.optString("prizeName", "未知奖品")
                val num = prize.optInt("prizeNum", 1)
                Log.forest("${s.name} 🎁 [获得: $name * $num] 剩余次数: $balance")
            }

            if (balance > 0) sleepCompat(100L)
        }
    }

    /**
     * 检查是否所有任务都已完成，并设置 Flag
     */
    private fun checkCompletion(s: Scene) {
        val resp = fetchFreshTaskList(s) ?: return
        if (!resp.check()) return

        val taskList = resp.optJSONArray("taskInfoList") ?: return
        var total = 0
        var completed = 0
        var allDone = true

        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue

            val taskType = baseInfo.optString("taskType")
            val taskStatus = baseInfo.optString("taskStatus")
            val bizInfoStr = baseInfo.optString("bizInfo")
            val taskName = if (bizInfoStr.isNotEmpty()) {
                extractTaskName(JSONObject(bizInfoStr), taskType)
            } else taskType

            if (isBlockedTask(taskType, taskName)) continue

            total++
            if (
                taskStatus == TaskStatus.RECEIVED.name ||
                rewardHandledTaskKeys.contains(buildRewardHandledTaskKey(baseInfo.optString("sceneCode"), taskType))
            ) {
                completed++
            } else {
                allDone = false
                Log.forest("${s.name} 未完成: $taskName [$taskStatus]")
            }
        }

        Log.forest("${s.name} 进度: $completed / $total")
        if (allDone) {
            Status.setFlagToday(s.flag)
            val msg = if (total > 0) "全部完成" else "无有效任务"
            Log.forest("✅ ${s.name} $msg ($completed/$total)")
        } else {
            Log.forest("⚠️ ${s.name} 未全部完成")
        }
    }

    /**
     * 判断任务是否在屏蔽列表中
     */
    private fun isBlockedTask(taskType: String, taskName: String): Boolean {
        return TaskBlacklist.isTaskInBlacklist(FOREST_BLACKLIST_MODULE, taskType) ||
            TaskBlacklist.isTaskInBlacklist(FOREST_BLACKLIST_MODULE, taskName)
    }

    /**
     * 处理单个任务分发
     * @return 任务状态是否有变更
     */
    private fun processSingleTask(s: Scene, task: JSONObject): Boolean {
        val baseInfo = task.optJSONObject("taskBaseInfo") ?: return false
        val bizInfoStr = baseInfo.optString("bizInfo")
        val bizInfo = if (bizInfoStr.isNotEmpty()) JSONObject(bizInfoStr) else JSONObject()

        val taskName = extractTaskName(bizInfo, "未知任务")
        val taskCode = baseInfo.optString("sceneCode")
        val taskStatus = baseInfo.optString("taskStatus")
        val taskType = baseInfo.optString("taskType")

        if (isBlockedTask(taskType, taskName)) return false

        Log.forest("${s.name} 任务: $taskName [$taskStatus]")

        return when (taskStatus) {
            TaskStatus.TODO.name -> handleTodoTask(s, taskName, taskCode, taskType)
            TaskStatus.FINISHED.name -> handleFinishedTask(s, taskName, taskCode, taskType)
            "COMPLETE" -> handleFinishedTask(s, taskName, taskCode, taskType)
            else -> false
        }
    }

    private fun isExecutableTodoTaskType(type: String): Boolean {
        return type == "NORMAL_DRAW_EXCHANGE_VITALITY" ||
            type.startsWith("FOREST_NORMAL_DRAW") ||
            type.startsWith("FOREST_ACTIVITY_DRAW")
    }

    private fun handleTodoTask(s: Scene, name: String, code: String, type: String): Boolean {
        return if (type == "NORMAL_DRAW_EXCHANGE_VITALITY") {
            // 活力值兑换
            Log.forest("${s.name} 兑换活力值: $name")
            val res = AntForestRpcCall.exchangeTimesFromTaskopengreen(s.id, s.code, SOURCE, code, type).toJson()
            if (res != null && res.isTaskAlreadyFinished()) {
                Log.forest("${s.name} $name 已完成")
                true
            } else if (res != null && res.check()) {
                Log.forest("${s.name} 🧾 $name 兑换成功")
                true
            } else false
        } else if (isExecutableTodoTaskType(type)) {
            // 普通任务
            Log.forest("${s.name} 执行任务(模拟耗时): $name")
            sleepCompat(100L) //

            val result = if (type.contains("XLIGHT")) {
                AntForestRpcCall.finishTask4Chouchoule(type, code)
            } else {
                AntForestRpcCall.finishTaskopengreen(type, code)
            }

            val resJson = result.toJson()
            if (resJson != null && resJson.isTaskAlreadyFinished()) {
                taskTryCount.remove(type)
                Log.forest("${s.name} 任务已完成: $name")
                true
            } else if (resJson != null && resJson.isTaskRightsLimitReached()) {
                taskTryCount.remove(type)
                Log.forest("${s.name} 任务权益已达上限，按已处理跳过: $name")
                true
            } else if (resJson != null && resJson.isRpcUnsupported()) {
                taskTryCount.remove(type)
                TaskBlacklist.autoAddToBlacklist(FOREST_BLACKLIST_MODULE, type, name, RPC_UNSUPPORTED_CODE)
                Log.forest("${s.name} 任务RPC不支持，已加入黑名单: $name")
                false
            } else if (resJson != null && resJson.check()) {
                taskTryCount.remove(type)
                Log.forest("${s.name} 🧾 $name")
                true
            } else {
                val count = taskTryCount.computeIfAbsent(type) { AtomicInteger(0) }.incrementAndGet()
                Log.forest("${s.name} 任务待重试($count): $name")
                false
            }
        } else {
            false
        }
    }

    private fun handleFinishedTask(s: Scene, name: String, code: String, type: String): Boolean {
        Log.forest("${s.name} 领取奖励: $name")
        sleepCompat(100L)
        val res = AntForestRpcCall.receiveTaskAwardopengreen(SOURCE, code, type).toJson()
        return if (res != null && (res.isTaskAwardAlreadyFinished() || res.isTaskAlreadyFinished())) {
            rewardHandledTaskKeys.add(buildRewardHandledTaskKey(code, type))
            Log.forest("${s.name} 奖励已领取: $name")
            false
        } else if (res != null && res.check()) {
            rewardHandledTaskKeys.add(buildRewardHandledTaskKey(code, type))
            Log.forest("${s.name} 🧾 $name 奖励领取成功")
            syncDrawAssetAfterTaskAward(s)
            true
        } else {
            Log.error(TAG, "${s.name} 奖励领取失败: $name")
            false
        }
    }

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

    private fun buildRewardHandledTaskKey(sceneCode: String, taskType: String): String {
        return "$sceneCode#$taskType"
    }
}

