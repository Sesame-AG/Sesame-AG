package io.github.aoguai.sesameag.task.antForest

import android.net.Uri
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.NonDurationGameTask
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 能量雨功能 - Kotlin协程版本
 *
 * 这是EnergyRain.java的协程版本重构，提供更好的性能和可维护性
 */
object EnergyRainCoroutine {
    private const val TAG = "EnergyRain"
    private const val FOREST_TASK_BLACKLIST_MODULE = "蚂蚁森林"
    private const val FOREST_SLJYD_TASK_TYPE = "GAME_DONE_SLJYD"
    private const val ENERGY_RAIN_GAME_SCENE_CODE = "ANTFOREST_ENERGY_RAIN_TASK"
    private val ENERGY_RAIN_REWARD_READY_STATUSES = setOf("FINISHED", "COMPLETE", "WAIT_RECEIVE", "TO_RECEIVE")
    private val ENERGY_RAIN_ACTIONABLE_STATUSES = setOf("TODO", "NOT_TRIGGER") + ENERGY_RAIN_REWARD_READY_STATUSES
    private val ENERGY_RAIN_TERMINAL_STATUSES = setOf("DONE", "RECEIVED", "HAS_RECEIVED", "SUCCESS", "COMPLETED")
    private val ENERGY_RAIN_DRIVE_TASK_MAPPING =
        mapOf(
            "GAME_DONE_LMCT" to "LMCT_TASK_QUDONG",
            "GAME_DONE_SGBHSD_new" to "SGBHSD_TASK_QUDONG",
            "GAME_DONE_QYJ" to "QYJZFM_TASK_QUDONG",
            "GAME_DONE_BWXRK" to "BWXRK_TASK_QUDONG",
            "GAME_DONE_CNXDY" to "CNXDY_TASK_QUDONG",
            "GAME_DONE_MHXCZ" to "MHXCZ_TASK_QUDONG",
            "GAME_DONE_XJSKP" to "XJSKP_TASK_QUDONG",
            "GAME_DONE_SCSST" to "SCSST_TASK_QUDONG",
            "GAME_DONE_WDHYSJ" to "WDHYSJ_TASK_QUDONG",
        )
    private val GRANT_BUSINESS_LIMIT_CODES =
        setOf(
            "FRIEND_NOT_FOREST_USER",
            "RAIN_ENERGY_GRANTED_BY_OTHER",
            "RAIN_ENERGY_GRANTED_USED",
            "RAIN_ENERGY_GRANT_EXCEED",
        )

    fun interface EnergyRainGameDriveCloser {
        suspend fun close(request: EnergyRainGameDriveRequest): EnergyRainGameDriveResult
    }

    data class EnergyRainGameDriveRequest(
        val gameTaskType: String,
        val gameTaskTitle: String,
        val gameTaskStatus: String,
        val appId: String?,
        val driveTaskType: String,
        val sceneCode: String = ENERGY_RAIN_GAME_SCENE_CODE,
        val taskProgress: Int = 0,
        val taskRequire: Int = 0,
    )

    data class EnergyRainGameDriveResult(
        val status: EnergyRainGameDriveStatus,
        val message: String = "",
    )

    enum class EnergyRainGameDriveStatus {
        STOPPED,
        CONFIRMED_DONE,
        PROGRESSED,
        NOT_FOUND,
        SKIPPED_BLACKLISTED,
        NO_PROGRESS,
        RETRYABLE_FAILED,
        NON_RETRYABLE_FAILED,
        UNKNOWN_FAILURE,
    }

    /**
     * 上次执行能量雨的时间戳
     */
    @Volatile
    private var lastExecuteTime: Long = 0

    /**
     * 随机延迟，增加随机性避免风控检测
     * @param min 最小延迟（毫秒）
     * @param max 最大延迟（毫秒）
     */
    private suspend fun randomDelay(
        min: Int,
        max: Int,
    ) {
        val delayTime = Random.nextInt(min, max + 1).toLong()
        delay(delayTime)
    }

    /**
     * 执行能量雨功能
     * @param isManual 是否为手动触发
     */
    suspend fun execEnergyRain(
        isManual: Boolean = false,
        gameTaskCloser: EnergyRainGameDriveCloser? = null,
        chanceCardUser: (suspend (MutableSet<String>) -> Boolean)? = null,
    ): Boolean {
        try {
            // 执行频率检查：防止短时间内重复执行
            val currentTime = System.currentTimeMillis()
            val timeSinceLastExec = currentTime - lastExecuteTime
            val cooldownSeconds = 3 // 冷却时间：3秒

            if (timeSinceLastExec < cooldownSeconds * 1000L) {
                delay(cooldownSeconds * 1000L - timeSinceLastExec)
            }

            val executed = energyRain(isManual, gameTaskCloser, chanceCardUser)

            // 更新最后执行时间
            lastExecuteTime = System.currentTimeMillis()
            return executed
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("execEnergyRain 协程被取消")
            throw e // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "执行能量雨出错:", th)
            return false
        }
    }

    /**
     * 能量雨主逻辑（协程版本）
     * @param isManual 是否为手动触发
     */
    private suspend fun energyRain(
        isManual: Boolean,
        gameTaskCloser: EnergyRainGameDriveCloser?,
        chanceCardUser: (suspend (MutableSet<String>) -> Boolean)?,
    ): Boolean {
        var playedCount = 0
        try {
            val attemptedGameTaskKeys = mutableSetOf<String>()
            val attemptedPropIds = mutableSetOf<String>()
            val attemptedGrantTargets = mutableSetOf<String>()
            while (true) {
                val home = JSONObject(AntForestRpcCall.queryEnergyRainHome())
                randomDelay(250, 400)
                if (!ResChecker.checkRes(TAG, home)) {
                    Log.error(TAG, "查询能量雨状态失败 raw=$home")
                    break
                }
                val canPlayToday = home.optBoolean("canPlayToday", false)
                val canGrantStatus = home.optBoolean("canGrantStatus", false)
                val canPlayGame = home.optBoolean("canPlayGame", false)
                Log.forest("能量雨状态[已结算:$playedCount][manual=$isManual][canPlayToday=$canPlayToday][canGrantStatus=$canGrantStatus][canPlayGame=$canPlayGame]")
                if (canPlayToday) {
                    if (startEnergyRain() != EnergyRainGameExecutionResult.CONFIRMED_DONE) break
                    playedCount++
                    randomDelay(3000, 5000)
                    continue
                }

                var granted = false
                try {
                    if (canGrantStatus && !Status.hasFlagToday(StatusFlags.FLAG_FOREST_RAIN_GRANT_EXCEED)) {
                        val grantResponse = JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList())
                        if (!ResChecker.checkRes(TAG, grantResponse)) {
                            Log.error(TAG, "查询可送能量雨好友失败 raw=$grantResponse")
                        } else {
                            val grantInfos = grantResponse.optJSONArray("grantInfos") ?: JSONArray()
                            for (i in (0 until grantInfos.length()).shuffled()) {
                                val info = grantInfos.optJSONObject(i) ?: continue
                                val uid = info.optString("userId")
                                if (!info.optBoolean("canGrantedStatus", false) ||
                                    !isConfiguredEnergyRainGrantTarget(uid) || shouldSkipEnergyRainGrantTarget(uid) ||
                                    !attemptedGrantTargets.add(uid)
                                ) continue
                                val result = JSONObject(AntForestRpcCall.grantEnergyRainChance(uid))
                                val code = result.optString("resultCode")
                                if (code in GRANT_BUSINESS_LIMIT_CODES) {
                                    Log.error(TAG, "赠送能量雨受限[${UserMap.getMaskName(uid)}] raw=$result")
                                    if (code == "RAIN_ENERGY_GRANT_EXCEED") {
                                        Status.setFlagToday(StatusFlags.FLAG_FOREST_RAIN_GRANT_EXCEED)
                                        break
                                    }
                                    continue
                                }
                                if (!ResChecker.checkRes(TAG, result)) {
                                    Log.error(TAG, "赠送能量雨失败[${UserMap.getMaskName(uid)}] raw=$result")
                                    continue
                                }
                                Log.forest("赠送能量雨机会给🌧️[${UserMap.getMaskName(uid)}]")
                                granted = true
                                randomDelay(300, 400)
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                    Log.printStackTrace(TAG, "赠送能量雨异常，继续其他机会来源", e)
                }
                if (granted) continue

                if (chanceCardUser?.invoke(attemptedPropIds) == true) continue

                if (canPlayGame && (isManual || !Status.hasFlagToday(StatusFlags.FLAG_FOREST_RAIN_GAME_TASK))) {
                    when (checkAndDoEndGameTask(attemptedGameTaskKeys, gameTaskCloser)) {
                        TaskResult.SUCCESS, TaskResult.PROGRESSED -> {
                            randomDelay(1500, 2500)
                            continue
                        }
                        TaskResult.ALREADY_DONE -> {
                            if (!isManual) {
                                Status.setFlagToday(StatusFlags.FLAG_FOREST_RAIN_GAME_TASK)
                                continue
                            }
                        }
                        TaskResult.RETRY_LATER -> Log.error(TAG, "能量雨任务查询明确可重试，保留后续统一调度")
                        TaskResult.UNKNOWN_FAILURE -> Log.error(TAG, "能量雨任务执行未确认，保留后续统一调度")
                        TaskResult.NOT_FOUND -> Unit
                    }
                }
                break
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (th: Throwable) {
            if (th is InterruptedException) throw th
            Log.printStackTrace(TAG, "energyRain err", th)
        }
        return playedCount > 0
    }

    /**
     * 开始能量雨（协程版本）
     */
    private suspend fun startEnergyRain(): EnergyRainGameExecutionResult {
        try {
            Log.forest("开始执行能量雨🌧️")
            val joStart = JSONObject(AntForestRpcCall.startEnergyRain())

            if (ResChecker.checkRes(TAG, joStart)) {
                val token = joStart.getString("token")
                val bubbleEnergyList = joStart.getJSONObject("difficultyInfo").getJSONArray("bubbleEnergyList")
                var sum = 0

                for (i in 0 until bubbleEnergyList.length()) {
                    sum += bubbleEnergyList.getInt(i)
                }

                randomDelay(5000, 5200) // 随机延迟 5-5.2秒，模拟真人玩游戏
                val resultJson = JSONObject(AntForestRpcCall.energyRainSettlement(sum, token))

                if (ResChecker.checkRes(TAG, resultJson)) {
                    val s = "收获能量雨🌧️[${sum}g]"
                    Toast.show(s)
                    Log.forest(s)
                    randomDelay(300, 400) // 结算成功后保留能量雨的随机请求间隔，再交回外层流程
                    return EnergyRainGameExecutionResult.CONFIRMED_DONE
                }
                val failure = classifyEnergyRainRpcFailure(resultJson)
                Log.error(
                    TAG,
                    "energyRainSettlement失败: ${extractEnergyRainFailureMessage(resultJson)} raw=$resultJson",
                )
                return failure
            } else {
                val failure = classifyEnergyRainRpcFailure(joStart)
                Log.error(TAG, "startEnergyRain失败: ${extractEnergyRainFailureMessage(joStart)} raw=$joStart")
                return failure
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("startEnergyRain 协程被取消")
            throw e // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            if (th is InterruptedException) throw th
            Log.error(TAG, "startEnergyRain异常: ${th.message}")
            Log.printStackTrace(TAG, th)
            return EnergyRainGameExecutionResult.UNKNOWN_FAILURE
        }
    }

    private enum class TaskResult {
        SUCCESS,
        PROGRESSED,
        ALREADY_DONE,
        RETRY_LATER,
        NOT_FOUND,
        UNKNOWN_FAILURE,
    }

    private enum class EnergyRainGameExecutionResult {
        STOPPED,
        CONFIRMED_DONE,
        PROGRESSED,
        EXECUTED_NO_PROGRESS,
        ALREADY_DONE,
        RETRYABLE_FAILED,
        NON_RETRYABLE_FAILED,
        UNKNOWN_FAILURE,
    }

    private data class EnergyRainGameTaskCandidate(
        val taskType: String,
        val taskStatus: String,
        val taskTitle: String,
        val appId: String?,
        val sceneCode: String,
        val taskProgress: Int,
        val taskRequire: Int,
        val rawTask: JSONObject,
    ) {
        val attemptKey: String
            get() = listOf(sceneCode, taskType, appId.orEmpty(), taskStatus, taskProgress, taskRequire).joinToString("#")
    }

    /**
     * 检查并领取能量雨后的额外游戏任务
     */
    private suspend fun checkAndDoEndGameTask(
        attemptedGameTaskKeys: MutableSet<String>,
        gameTaskCloser: EnergyRainGameDriveCloser?,
    ): TaskResult {
        try {
            val response = JSONObject(AntForestRpcCall.queryEnergyRainEndGameList())
            if (!ResChecker.checkRes(TAG, response)) {
                Log.error(TAG, "能量雨机会任务列表查询失败: ${formatEnergyRainEndGameListFailure(response)} raw=$response")
                return if (classifyEnergyRainRpcFailure(response) == EnergyRainGameExecutionResult.RETRYABLE_FAILED) {
                    TaskResult.RETRY_LATER
                } else TaskResult.UNKNOWN_FAILURE
            }
            val list = response.optJSONObject("energyRainEndGameGroupTask")?.optJSONArray("taskInfoList")
            if (list == null || list.length() == 0) {
                Log.forest("能量雨当前未返回候选，不写入全天完成标记")
                return TaskResult.NOT_FOUND
            }
            val candidates = buildEnergyRainGameTaskCandidates(list)
            var failed = false
            for (candidate in candidates.filter { it.taskStatus in ENERGY_RAIN_ACTIONABLE_STATUSES }) {
                if (candidate.taskStatus !in ENERGY_RAIN_REWARD_READY_STATUSES &&
                    TaskBlacklist.isTaskInBlacklist(FOREST_TASK_BLACKLIST_MODULE, candidate.taskType)
                ) {
                    Log.forest("能量雨任务[${candidate.taskTitle}][${candidate.taskType}]按森林黑名单跳过，不标记完成")
                    continue
                }
                if (candidate.attemptKey in attemptedGameTaskKeys) continue
                val (home, failure) = queryEnergyRainGameHome("执行前")
                if (home == null) {
                    return if (failure == EnergyRainGameExecutionResult.RETRYABLE_FAILED) TaskResult.RETRY_LATER else TaskResult.UNKNOWN_FAILURE
                }
                if (home.optBoolean("canPlayToday", false)) return TaskResult.PROGRESSED
                attemptedGameTaskKeys.add(candidate.attemptKey)
                when (executeEnergyRainGameTask(candidate, response.optBoolean("needInitTask", false), home, gameTaskCloser)) {
                    EnergyRainGameExecutionResult.CONFIRMED_DONE,
                    EnergyRainGameExecutionResult.ALREADY_DONE -> return TaskResult.SUCCESS
                    EnergyRainGameExecutionResult.PROGRESSED -> return TaskResult.PROGRESSED
                    EnergyRainGameExecutionResult.STOPPED -> return TaskResult.UNKNOWN_FAILURE
                    EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS -> {
                        Log.forest("能量雨任务[${candidate.taskTitle}]已执行本状态动作，保留后续统一调度")
                    }
                    else -> {
                        failed = true
                        Log.error(TAG, "能量雨任务[${candidate.taskTitle}]执行未确认，继续其他独立候选")
                    }
                }
            }
            if (candidates.isNotEmpty() && candidates.all { it.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES }) {
                return TaskResult.ALREADY_DONE
            }
            return if (failed) TaskResult.UNKNOWN_FAILURE else TaskResult.NOT_FOUND
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            Log.printStackTrace(TAG, "检查能量雨任务异常", e)
            return TaskResult.UNKNOWN_FAILURE
        }
    }

    private fun formatEnergyRainEndGameListFailure(response: JSONObject): String =
        "error=${response.optString("error").ifBlank { "UNKNOWN" }} " +
            "errorNo=${response.optString("errorNo").ifBlank { "UNKNOWN" }} " +
            "message=${response.optString("errorMessage").ifBlank { response.optString("errorTip") }}"

    private fun buildEnergyRainGameTaskCandidates(taskInfoList: JSONArray): List<EnergyRainGameTaskCandidate> =
        buildList {
            for (i in 0 until taskInfoList.length()) {
                val task = taskInfoList.optJSONObject(i) ?: continue
                val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
                val taskType = baseInfo.optString("taskType")
                if (taskType.isBlank()) {
                    continue
                }
                val taskStatus = baseInfo.optString("taskStatus")
                val sceneCode = baseInfo.optString("sceneCode").ifBlank { ENERGY_RAIN_GAME_SCENE_CODE }
                val bizInfo = parseEnergyRainTaskJson(baseInfo.opt("bizInfo"))
                val prodPlayParam = parseEnergyRainTaskJson(baseInfo.opt("prodPlayParam"))
                val taskCategorization = prodPlayParam.optJSONObject("taskCategorization")
                val appId =
                    taskCategorization
                        ?.optJSONObject("categorizationParamModel")
                        ?.optString("game_id")
                        ?.takeIf { it.isNotBlank() }
                        ?: extractEnergyRainTaskAppId(bizInfo.optString("taskJumpUrl"))
                val taskTitle =
                    sequenceOf(
                        bizInfo.optString("taskTitle"),
                        bizInfo.optString("title"),
                        bizInfo.optString("taskDesc"),
                        taskType,
                    ).firstOrNull { it.isNotBlank() } ?: taskType
                add(
                    EnergyRainGameTaskCandidate(
                        taskType = taskType,
                        taskStatus = taskStatus,
                        taskTitle = taskTitle,
                        appId = appId,
                        sceneCode = sceneCode,
                        taskProgress = baseInfo.optInt("taskProgress", 0),
                        taskRequire = baseInfo.optInt("taskRequire", 0),
                        rawTask = task,
                    ),
                )
            }
        }

    private fun parseEnergyRainTaskJson(value: Any?): JSONObject =
        when (value) {
            is JSONObject -> {
                value
            }

            is String -> {
                if (value.isBlank()) {
                    JSONObject()
                } else {
                    runCatching { JSONObject(value) }.getOrElse { JSONObject() }
                }
            }

            else -> {
                JSONObject()
            }
        }

    private fun extractEnergyRainTaskAppId(url: String): String? =
        Uri.parse(url).getQueryParameter("appId")?.takeIf { it.isNotBlank() }

    private suspend fun executeEnergyRainGameTask(
        candidate: EnergyRainGameTaskCandidate,
        needInitTask: Boolean,
        home: JSONObject,
        gameTaskCloser: EnergyRainGameDriveCloser?,
    ): EnergyRainGameExecutionResult {
        return try {
            Log.forest("执行能量雨任务[${candidate.taskTitle}][${candidate.taskType}] status=${candidate.taskStatus} progress=${candidate.taskProgress}/${candidate.taskRequire}")
            var actionFailure: EnergyRainGameExecutionResult? = null
            if (candidate.taskStatus in ENERGY_RAIN_REWARD_READY_STATUSES) {
                val award = JSONObject(AntForestRpcCall.receiveTaskAward(candidate.sceneCode, candidate.taskType, AntForestRpcCall.FOREST_TASK_ACTION_SOURCE))
                if (!ResChecker.checkRes(TAG, award)) {
                    val failure = classifyEnergyRainRpcFailure(award)
                    Log.error(TAG, "能量雨任务领奖返回[${candidate.taskType}] raw=$award")
                    if (failure != EnergyRainGameExecutionResult.ALREADY_DONE) actionFailure = failure
                }
            } else {
                syncEnergyRainEndContext(home)
                if (candidate.taskStatus == "NOT_TRIGGER" && needInitTask) {
                    val init = JSONObject(AntForestRpcCall.initTask(candidate.taskType))
                    if (!ResChecker.checkRes(TAG, init)) {
                        val failure = classifyEnergyRainRpcFailure(init)
                        Log.error(TAG, "初始化能量雨任务失败[${candidate.taskType}] raw=$init")
                        if (failure != EnergyRainGameExecutionResult.ALREADY_DONE) return failure
                    }
                    randomDelay(800, 1500)
                }
                candidate.appId?.takeIf { it.isNotBlank() }?.let { appId ->
                    val click = JSONObject(AntForestRpcCall.clickEnergyRainGame(appId))
                    if (!ResChecker.checkRes(TAG, click)) {
                        val failure = classifyEnergyRainRpcFailure(click)
                        Log.error(TAG, "点击能量雨游戏失败[$appId] raw=$click")
                        if (failure != EnergyRainGameExecutionResult.ALREADY_DONE) return failure
                    }
                    randomDelay(2000, 3000)
                }
                try {
                    val base = candidate.rawTask.optJSONObject("taskBaseInfo")
                    val decision = GameCenterPlayRpcCall.resolveTaskAction(
                        candidate.rawTask, base, parseEnergyRainTaskJson(base?.opt("bizInfo")),
                        parseEnergyRainTaskJson(base?.opt("prodPlayParam")),
                        JSONObject().put("gameAppId", GameTask.Forest_sljyd.appId).takeIf {
                            candidate.taskType == FOREST_SLJYD_TASK_TYPE && candidate.appId.isNullOrBlank()
                        },
                    )
                    when (decision.action) {
                        GameCenterPlayRpcCall.TaskAction.DURATION_ONLY,
                        GameCenterPlayRpcCall.TaskAction.CLICK_THEN_DURATION -> {
                            val contract = requireNotNull(decision.contract)
                            val result = GameCenterPlayRpcCall.advanceDuration(
                                "energyRainDuration:${candidate.sceneCode}:${candidate.taskType}", contract,
                            )
                            if (!result.success) {
                                Log.error(TAG, "能量雨时长任务[${candidate.taskType}] rpc=${result.rpc} code=${result.code} msg=${result.message} raw=${result.raw}")
                                actionFailure = when (result.failureType) {
                                    TaskRpcFailureType.RETRYABLE_RPC -> EnergyRainGameExecutionResult.RETRYABLE_FAILED
                                    TaskRpcFailureType.BUSINESS_LIMIT, TaskRpcFailureType.NON_RETRYABLE_INVALID,
                                    TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
                                    else -> EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                                }
                            } else {
                                val finish = JSONObject(AntForestRpcCall.finishTask(
                                    candidate.sceneCode, candidate.taskType, AntForestRpcCall.FOREST_TASK_ACTION_SOURCE,
                                ))
                                if (!ResChecker.checkRes(TAG, finish)) {
                                    val failure = classifyEnergyRainRpcFailure(finish)
                                    Log.error(TAG, "能量雨时长完成提交返回[${candidate.taskType}] raw=$finish")
                                    if (failure != EnergyRainGameExecutionResult.ALREADY_DONE) actionFailure = failure
                                }
                            }
                        }
                        GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT -> {
                            val reporter = decision.mappedTask ?: GameTask.Forest_sljyd.takeIf { candidate.taskType == FOREST_SLJYD_TASK_TYPE }
                            if (reporter == null) {
                                Log.error(TAG, "能量雨任务[${candidate.taskType}]缺少固定动作")
                                actionFailure = EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                            } else {
                                var confirmedResult: EnergyRainGameExecutionResult? = null
                                val report = NonDurationGameTask.advance(
                                    reporter, candidate.rawTask, channel = GameTask.Forest_sljyd.channel,
                                    detail = "taskId=${candidate.taskType} sceneCode=${candidate.sceneCode}", logger = Log::forest,
                                    confirmProgress = {
                                        val tasks = JSONObject(AntForestRpcCall.queryEnergyRainEndGameList())
                                        if (!ResChecker.checkRes(TAG, "能量雨上报后任务查询失败:", tasks)) {
                                            null
                                        } else {
                                            val (latestHome, _) = queryEnergyRainGameHome("逐次上报回查")
                                            val list = tasks.optJSONObject("energyRainEndGameGroupTask")?.optJSONArray("taskInfoList")
                                            if (latestHome == null || list == null) {
                                                Log.error(TAG, "能量雨上报后任务或机会状态缺失 raw=$tasks")
                                                null
                                            } else {
                                                val same = buildEnergyRainGameTaskCandidates(list)
                                                    .firstOrNull { isSameEnergyRainGameTask(candidate, it) }
                                                when {
                                                    same?.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES -> {
                                                        confirmedResult = EnergyRainGameExecutionResult.CONFIRMED_DONE
                                                        true
                                                    }
                                                    same?.taskStatus in ENERGY_RAIN_REWARD_READY_STATUSES ||
                                                        same != null && same.taskRequire > 0 && same.taskProgress >= same.taskRequire ||
                                                        !home.optBoolean("canPlayToday") && latestHome.optBoolean("canPlayToday") -> {
                                                        confirmedResult = EnergyRainGameExecutionResult.PROGRESSED
                                                        true
                                                    }
                                                    same == null -> {
                                                        Log.error(TAG, "能量雨任务消失但机会未确认增加[${candidate.taskType}] raw=$latestHome")
                                                        null
                                                    }
                                                    same.taskStatus == "TODO" || same.taskStatus == "NOT_TRIGGER" -> false
                                                    else -> null
                                                }
                                            }
                                        }
                                    },
                                )
                                if (report.stopCurrentRound) return EnergyRainGameExecutionResult.STOPPED
                                if (report.deferredReason !in setOf(DeferredReason.STATE_CONFIRMATION, DeferredReason.NO_PROGRESS_COOLDOWN)) {
                                    Log.error(TAG, "能量雨游戏上报未完成[${candidate.taskType}] rpc=${report.rpc} code=${report.code} msg=${report.message} detail=${report.detail} raw=${report.raw}")
                                    val failure = when (report.failureType) {
                                        TaskRpcFailureType.RETRYABLE_RPC -> EnergyRainGameExecutionResult.RETRYABLE_FAILED
                                        TaskRpcFailureType.BUSINESS_LIMIT, TaskRpcFailureType.NON_RETRYABLE_INVALID,
                                        TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
                                        else -> EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                                    }
                                    if (report.code != "GAME_ACTION_CONTRACT_MISSING" &&
                                        report.deferredReason != DeferredReason.PREREQUISITE_PENDING) return failure
                                    actionFailure = failure
                                }
                                confirmedResult?.let { return it }
                            }
                        }
                        GameCenterPlayRpcCall.TaskAction.DEFERRED -> {
                            Log.forest("能量雨任务[${candidate.taskTitle}][${candidate.taskType}]等待游戏业务条件，继续独立驱动并回查 reason=${decision.reason}")
                        }
                        else -> Unit // 未提供时长合同不阻断原有普通驱动和 IEP 动作。
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                    Log.printStackTrace(TAG, "能量雨任务动作异常[${candidate.taskType}]，继续独立驱动并回查", e)
                    actionFailure = EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                }
                val drive = closeMappedEnergyRainDriveTask(candidate, gameTaskCloser)
                when (drive.status) {
                    EnergyRainGameDriveStatus.STOPPED -> return EnergyRainGameExecutionResult.STOPPED
                    EnergyRainGameDriveStatus.RETRYABLE_FAILED -> actionFailure = EnergyRainGameExecutionResult.RETRYABLE_FAILED
                    EnergyRainGameDriveStatus.NON_RETRYABLE_FAILED -> actionFailure = EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
                    EnergyRainGameDriveStatus.UNKNOWN_FAILURE -> actionFailure = EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                    else -> Unit
                }
            }
            randomDelay(1000, 2000)
            val (latestHome, homeFailure) = queryEnergyRainGameHome("融合链路最终回查")
            if (latestHome == null) return homeFailure ?: EnergyRainGameExecutionResult.UNKNOWN_FAILURE
            val verify = JSONObject(AntForestRpcCall.queryEnergyRainEndGameList())
            if (!ResChecker.checkRes(TAG, verify)) {
                Log.error(TAG, "能量雨任务最终状态查询失败[${candidate.taskType}] raw=$verify")
                return when (classifyEnergyRainRpcFailure(verify)) {
                    EnergyRainGameExecutionResult.RETRYABLE_FAILED -> EnergyRainGameExecutionResult.RETRYABLE_FAILED
                    EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
                    else -> EnergyRainGameExecutionResult.UNKNOWN_FAILURE
                }
            }
            val result = verifyEnergyRainGameTask(candidate, verify)
            when {
                result != EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS -> result
                latestHome.optBoolean("canPlayToday", false) -> EnergyRainGameExecutionResult.PROGRESSED
                else -> actionFailure ?: result
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            Log.printStackTrace(TAG, "执行能量雨任务异常[${candidate.taskType}]", e)
            EnergyRainGameExecutionResult.UNKNOWN_FAILURE
        }
    }

    private fun queryEnergyRainGameHome(phase: String): Pair<JSONObject?, EnergyRainGameExecutionResult?> =
        try {
            val homeResponse =
                JSONObject(
                    AntForestRpcCall.queryEnergyRainHome(AntForestRpcCall.ENERGY_RAIN_GAME_ENTRY_SOURCE),
                )
            if (!ResChecker.checkRes(TAG, homeResponse)) {
                val failure = classifyEnergyRainRpcFailure(homeResponse)
                Log.error(
                    TAG,
                    "能量雨游戏任务[$phase]查询机会失败: ${extractEnergyRainFailureMessage(homeResponse)} raw=$homeResponse",
                )
                null to (failure.takeUnless { it == EnergyRainGameExecutionResult.ALREADY_DONE }
                    ?: EnergyRainGameExecutionResult.UNKNOWN_FAILURE)
            } else {
                homeResponse to null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
            Log.error(TAG, "能量雨游戏任务[$phase]查询机会异常: ${e.message}")
            null to EnergyRainGameExecutionResult.UNKNOWN_FAILURE
        }

    private fun syncEnergyRainEndContext(homeResponse: JSONObject) {
        val currentEnergy = extractEnergyRainCurrentEnergy(homeResponse)
        if (currentEnergy == null) {
            Log.forest("能量雨结束页上下文缺少 currentEnergy，跳过 guideDecisionEntrance")
        } else {
            runCatching {
                JSONObject(AntForestRpcCall.guideEnergyRainEnd(currentEnergy))
            }.onSuccess { guideResponse ->
                if (!ResChecker.checkRes(TAG, guideResponse)) {
                    Log.error(TAG, "能量雨结束页导流上下文失败 raw=$guideResponse")
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                Log.printStackTrace(TAG, "能量雨结束页导流上下文异常", e)
            }
        }

        runCatching {
            JSONObject(AntForestRpcCall.queryEnergyRainRanking())
        }.onSuccess { rankingResponse ->
            if (!ResChecker.checkRes(TAG, rankingResponse)) {
                Log.error(TAG, "能量雨排行榜上下文失败 raw=$rankingResponse")
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
            Log.printStackTrace(TAG, "能量雨排行榜上下文异常", e)
        }
    }

    private fun extractEnergyRainCurrentEnergy(homeResponse: JSONObject): Int? {
        for (key in listOf("currentEnergy", "userEnergy")) {
            val rawValue = homeResponse.opt(key)
            when (rawValue) {
                is Number -> return rawValue.toInt()
                is String -> rawValue.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private suspend fun closeMappedEnergyRainDriveTask(
        candidate: EnergyRainGameTaskCandidate,
        gameTaskCloser: EnergyRainGameDriveCloser?,
    ): EnergyRainGameDriveResult {
        val driveTaskType = ENERGY_RAIN_DRIVE_TASK_MAPPING[candidate.taskType]
        if (gameTaskCloser == null) {
            Log.forest("能量雨机会任务[${candidate.taskTitle}]缺少森林游戏中心融合闭环入口[$driveTaskType]")
            return EnergyRainGameDriveResult(EnergyRainGameDriveStatus.NOT_FOUND)
        }
        val request =
            EnergyRainGameDriveRequest(
                gameTaskType = candidate.taskType,
                gameTaskTitle = candidate.taskTitle,
                gameTaskStatus = candidate.taskStatus,
                appId = candidate.appId,
                driveTaskType = driveTaskType.orEmpty(),
                sceneCode = candidate.sceneCode,
                taskProgress = candidate.taskProgress,
                taskRequire = candidate.taskRequire,
            )
        val result = gameTaskCloser.close(request)
        val suffix =
            result.message
                .takeIf { it.isNotBlank() }
                ?.let { ": $it" }
                .orEmpty()
        when (result.status) {
            EnergyRainGameDriveStatus.STOPPED -> {
                Log.error(TAG,
                    "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]已停止，终止当前链路$suffix",
                )
            }

            EnergyRainGameDriveStatus.CONFIRMED_DONE,
            EnergyRainGameDriveStatus.PROGRESSED,
            -> {
                Log.forest(
                    "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]已执行，回查能量雨状态$suffix",
                )
            }

            EnergyRainGameDriveStatus.SKIPPED_BLACKLISTED -> {
                Log.forest(
                    "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]已在黑名单，跳过驱动，不标记完成$suffix",
                )
            }

            EnergyRainGameDriveStatus.NOT_FOUND,
            EnergyRainGameDriveStatus.NO_PROGRESS,
            -> {
                Log.forest(
                    "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]本轮未形成确认进展，保留后续统一调度$suffix",
                )
            }

            EnergyRainGameDriveStatus.RETRYABLE_FAILED,
            EnergyRainGameDriveStatus.NON_RETRYABLE_FAILED,
            EnergyRainGameDriveStatus.UNKNOWN_FAILURE,
            -> {
                Log.error(TAG,
                    "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]执行失败$suffix",
                )
            }
        }
        return result
    }

    private fun verifyEnergyRainGameTask(
        candidate: EnergyRainGameTaskCandidate,
        verifyResponse: JSONObject,
    ): EnergyRainGameExecutionResult {
        val list = verifyResponse.optJSONObject("energyRainEndGameGroupTask")?.optJSONArray("taskInfoList")
            ?: return EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
        val sameTask = buildEnergyRainGameTaskCandidates(list).firstOrNull { isSameEnergyRainGameTask(candidate, it) }
            ?: return EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
        if (sameTask.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES) return EnergyRainGameExecutionResult.CONFIRMED_DONE
        return if (hasEnergyRainTaskProgressed(candidate, sameTask)) EnergyRainGameExecutionResult.PROGRESSED
        else EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
    }

    private fun isSameEnergyRainGameTask(
        expected: EnergyRainGameTaskCandidate,
        actual: EnergyRainGameTaskCandidate,
    ): Boolean = expected.sceneCode == actual.sceneCode && expected.taskType == actual.taskType

    private fun hasEnergyRainTaskProgressed(
        before: EnergyRainGameTaskCandidate,
        after: EnergyRainGameTaskCandidate,
    ): Boolean = before.taskStatus != after.taskStatus || after.taskProgress > before.taskProgress

    private fun isConfiguredEnergyRainGrantTarget(userId: String?): Boolean =
        AntForest.giveEnergyRainList?.containsConfigured(userId) == true

    private fun shouldSkipEnergyRainGrantTarget(userId: String?): Boolean {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) {
            Log.record(TAG, "赠送能量雨 跳过：userId为空")
            return true
        }
        if (normalized == UserMap.currentUid) {
            Log.record(TAG, "赠送能量雨 跳过自己账号[$normalized]")
            return true
        }
        if (FriendRepository.isGlobalBlocked(normalized)) {
            val maskedName = UserMap.getMaskName(normalized) ?: normalized
            Log.record(TAG, "赠送能量雨 跳过[$maskedName]：好友中心全局黑名单")
            return true
        }
        return false
    }

    private fun classifyEnergyRainRpcFailure(response: JSONObject): EnergyRainGameExecutionResult {
        return when (extractEnergyRainFailureCode(response)) {
            "400000030", "400000008", "B000000008" -> EnergyRainGameExecutionResult.ALREADY_DONE
            "20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST",
            "400000040", "CAMP_TRIGGER_ERROR" -> EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
            "3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK" -> EnergyRainGameExecutionResult.RETRYABLE_FAILED
            else -> if (isEnergyRainFailureMarkedRetryable(response)) EnergyRainGameExecutionResult.RETRYABLE_FAILED
                else EnergyRainGameExecutionResult.UNKNOWN_FAILURE
        }
    }

    private fun isEnergyRainFailureMarkedRetryable(response: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun extractEnergyRainFailureCode(response: JSONObject): String =
        response
            .optString("code")
            .ifBlank { response.optString("resultCode") }
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("error") }

    private fun extractEnergyRainFailureMessage(response: JSONObject): String =
        response
            .optString("desc")
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.toString() }
}
