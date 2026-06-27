package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.random.Random

/**
 * 能量雨功能 - Kotlin协程版本
 *
 * 这是EnergyRain.java的协程版本重构，提供更好的性能和可维护性
 */
object EnergyRainCoroutine {
    private const val TAG = "EnergyRain"
    private const val FOREST_LMCT_TASK_TYPE = "GAME_DONE_LMCT"
    private const val FOREST_SLJYD_TASK_TYPE = "GAME_DONE_SLJYD"
    private val APP_ID_QUERY_REGEX = Regex("""(?:^|[?&])appId=([0-9]+)""")
    private val ENERGY_RAIN_ACTIONABLE_STATUSES = setOf("TODO", "NOT_TRIGGER")
    private val ENERGY_RAIN_TERMINAL_STATUSES = setOf("FINISHED", "DONE", "RECEIVED")
    private val ENERGY_RAIN_DRIVE_TASK_MAPPING = mapOf(
        "GAME_DONE_LMCT" to "LMCT_TASK_QUDONG",
        "GAME_DONE_SGBHSD_new" to "SGBHSD_TASK_QUDONG",
        "GAME_DONE_QYJ" to "QYJZFM_TASK_QUDONG",
        "GAME_DONE_BWXRK" to "BWXRK_TASK_QUDONG",
        "GAME_DONE_CNXDY" to "CNXDY_TASK_QUDONG",
        "GAME_DONE_MHXCZ" to "MHXCZ_TASK_QUDONG",
        "GAME_DONE_XJSKP" to "XJSKP_TASK_QUDONG",
        "GAME_DONE_SCSST" to "SCSST_TASK_QUDONG",
        "GAME_DONE_WDHYSJ" to "WDHYSJ_TASK_QUDONG"
    )
    private val SILENT_GRANT_FAILURE_CODES = setOf(
        "FRIEND_NOT_FOREST_USER",
        "RAIN_ENERGY_GRANTED_BY_OTHER",
        "RAIN_ENERGY_GRANT_EXCEED"
    )

    fun interface EnergyRainGameDriveCloser {
        fun close(request: EnergyRainGameDriveRequest): EnergyRainGameDriveResult
    }

    data class EnergyRainGameDriveRequest(
        val gameTaskType: String,
        val gameTaskTitle: String,
        val gameTaskStatus: String,
        val appId: String?,
        val driveTaskType: String
    )

    data class EnergyRainGameDriveResult(
        val status: EnergyRainGameDriveStatus,
        val message: String = ""
    )

    enum class EnergyRainGameDriveStatus {
        CONFIRMED_DONE,
        PROGRESSED,
        NOT_FOUND,
        SKIPPED_BLACKLISTED,
        NO_PROGRESS,
        RETRYABLE_FAILED,
        NON_RETRYABLE_FAILED
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
    private suspend fun randomDelay(min: Int, max: Int) {
        val delayTime = Random.nextInt(min, max + 1).toLong()
        delay(delayTime)
    }

    /**
     * 执行能量雨功能
     * @param isManual 是否为手动触发
     */
    suspend fun execEnergyRain(
        isManual: Boolean = false,
        gameTaskCloser: EnergyRainGameDriveCloser? = null
    ): Boolean {
        try {
            // 执行频率检查：防止短时间内重复执行
            val currentTime = System.currentTimeMillis()
            val timeSinceLastExec = currentTime - lastExecuteTime
            val cooldownSeconds = 3 // 冷却时间：3秒

            if (timeSinceLastExec < cooldownSeconds * 1000) {
                // 粗放点，delay 3秒
                delay(cooldownSeconds * 1000.toLong())
            }

            val executed = energyRain(isManual, gameTaskCloser)

            // 更新最后执行时间
            lastExecuteTime = System.currentTimeMillis()
            return executed
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("execEnergyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
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
        gameTaskCloser: EnergyRainGameDriveCloser?
    ): Boolean {
        try {
            var playedCount = 0
            val maxPlayLimit = 10
            var shouldRunPostFlow = false
            val attemptedGameTaskKeys = mutableSetOf<String>()

            do {
                val joEnergyRainHome = JSONObject(AntForestRpcCall.queryEnergyRainHome())
                randomDelay(250, 400) // 随机延迟 300-400ms
                if (!ResChecker.checkRes(TAG, joEnergyRainHome)) {
                    Log.forest("查询能量雨状态失败")
                    break
                }
                val energyRainGameFlag = StatusFlags.FLAG_FOREST_RAIN_GAME_TASK
                val canPlayToday = joEnergyRainHome.optBoolean("canPlayToday", false)
                val canPlayGame = joEnergyRainHome.optBoolean("canPlayGame", false) // 始终是true
                val canGrantStatus = joEnergyRainHome.optBoolean("canGrantStatus", false)
                var grantExceedToday = Status.hasFlagToday(StatusFlags.FLAG_FOREST_RAIN_GRANT_EXCEED)
                Log.forest("能量雨状态[轮次:${playedCount + 1}][manual=$isManual][canPlayToday=$canPlayToday][canGrantStatus=$canGrantStatus][canPlayGame=$canPlayGame][grantExceedToday=$grantExceedToday][gameTaskFlag=${Status.hasFlagToday(energyRainGameFlag)}]"
                )

                var worked = false

                // 1️⃣ 检查是否可以开始能量雨
                if (canPlayToday) {
                    if (startEnergyRain()) {
                        playedCount++
                        randomDelay(3000, 5000) // 随机延迟3-5秒
                        shouldRunPostFlow = true
                        worked = true
                    }
                }

                // 2️⃣ 检查是否可以赠送能量雨
                if (canGrantStatus) {
                    Log.forest("有送能量雨的机会")
                    if (grantExceedToday) {
                        Log.forest("今日已达到赠送能量雨上限，跳过赠送环节")
                    } else {
                        val joEnergyRainCanGrantList = JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList())
                        val grantInfos = joEnergyRainCanGrantList.optJSONArray("grantInfos") ?: org.json.JSONArray()
                        val giveEnergyRainSet = AntForest.giveEnergyRainList?.resolvedIds() ?: emptySet()
                        var granted = false

                        for (j in 0 until grantInfos.length()) {
                            val grantInfo = grantInfos.getJSONObject(j)
                            if (grantInfo.optBoolean("canGrantedStatus", false)) {
                                val uid = grantInfo.getString("userId")
                                if (giveEnergyRainSet.contains(uid)) {
                                    if (FriendGuard.shouldSkipFriend(uid, TAG, "赠送能量雨")) {
                                        continue
                                    }
                                    val rainJsonObj = JSONObject(AntForestRpcCall.grantEnergyRainChance(uid))
                                    val maskedName = UserMap.getMaskName(uid)
                                    val resultCode = rainJsonObj.optString("resultCode")
                                    val resultDesc = rainJsonObj.optString("resultDesc")
                                    Log.forest("尝试送能量雨给【$maskedName】")
                                    if (resultCode in SILENT_GRANT_FAILURE_CODES) {
                                        when (resultCode) {
                                            "RAIN_ENERGY_GRANT_EXCEED" -> {
                                                Status.setFlagToday(StatusFlags.FLAG_FOREST_RAIN_GRANT_EXCEED)
                                                grantExceedToday = true
                                                Log.forest("送能量雨已达到今日上限，停止继续尝试")
                                                break
                                            }

                                            "FRIEND_NOT_FOREST_USER" -> {
                                                Log.forest("跳过赠送【$maskedName】:${resultDesc.ifEmpty { "好友未开通蚂蚁森林" }}")
                                            }

                                            "RAIN_ENERGY_GRANTED_BY_OTHER" -> {
                                                Log.forest("跳过赠送【$maskedName】:${resultDesc.ifEmpty { "该好友已被其他人赠送" }}")
                                            }
                                        }
                                        continue
                                    }
                                    if (ResChecker.checkRes(TAG, rainJsonObj)) {
                                        Log.forest(
                                            "赠送能量雨机会给🌧️[${UserMap.getMaskName(uid)}]#${
                                                UserMap.getMaskName(
                                                    UserMap.currentUid
                                                )
                                            }"
                                        )
                                        randomDelay(300, 400) // 随机延迟 300-400ms
                                        granted = true
                                        break
                                    } else {
                                        Log.error(TAG, "送能量雨失败 $rainJsonObj")
                                    }
                                }
                            }
                        }
                        if (granted) {
                            worked = true
                        } else {
                            Log.forest("今日无可送能量雨好友或已达到赠送上限")
                        }
                    }
                }

                // 3️⃣ 检查是否可以能量雨游戏
                // 只有常规机会用完 (!canPlayToday) 且赠送机会也已全部处理 (!canGrantStatus) 时，才检查收尾游戏任务。
                // 今日标记只用于服务端确认终态或无候选，不用于隐藏“入口已尝试但未完成”的任务。
                val canEnterGameCheck = isManual || (!canPlayToday && (!canGrantStatus || grantExceedToday))
                if (canEnterGameCheck) {
                    if (canPlayGame && (isManual || !Status.hasFlagToday(energyRainGameFlag))) {
                        Log.forest("检查能量雨游戏任务")
                        val taskResult = checkAndDoEndGameTask(attemptedGameTaskKeys, gameTaskCloser)//检查能量雨 游戏任务 并接取
                        if (taskResult == TaskResult.SUCCESS) {
                            if (!isManual) {
                                Status.setFlagToday(energyRainGameFlag)
                            }
                            randomDelay(3000, 5000) // 随机延迟3-5秒
                            playedCount++
                            shouldRunPostFlow = true
                            worked = true
                        } else if (taskResult == TaskResult.ALREADY_DONE && !isManual) {
                            // 确定任务已完成或今日不可用，才设置标记
                            Status.setFlagToday(energyRainGameFlag)
                        }
                    }
                } else if (!isManual && !Status.hasFlagToday(energyRainGameFlag)) {
                    if (canPlayToday) {
                        Log.forest("跳过游戏任务检查：常规能量雨机会尚未耗尽。")
                    } else if (canGrantStatus) {
                        Log.forest("跳过游戏任务检查：仍有赠送能量雨的机会。注意：游戏入口需在所有赠送机会消耗后开启，若当前无可送好友，请在[赠送能量雨配置]中勾选好友。")
                    }
                }

                if (!worked) {
                    break
                }
            } while (playedCount < maxPlayLimit)

            if (playedCount >= maxPlayLimit) {
                Log.forest("能量雨执行达到单次任务上限($maxPlayLimit)，停止执行")
            }
            return shouldRunPostFlow
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("energyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.forest("energyRain err:")
            Log.printStackTrace(TAG, th)
            return false
        }
    }

    /**
     * 开始能量雨（协程版本）
     */
    private suspend fun startEnergyRain(): Boolean {
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
                    randomDelay(300, 400) // 随机延迟 300-400ms
                    return true
                }
                Log.forest("energyRainSettlement: $resultJson")
                return false
            } else {
                Log.forest("startEnergyRain: $joStart")
                return false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("startEnergyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.forest("startEnergyRain err:")
            Log.printStackTrace(TAG, th)
            return false
        }
    }

    private enum class TaskResult {
        SUCCESS,        // 执行成功
        ALREADY_DONE,   // 任务已完成或确定不可用
        NOT_FOUND       // 未发现任务（可能是接口更新延迟）
    }

    private enum class EnergyRainGameExecutionResult {
        CONFIRMED_DONE,
        EXECUTED_NO_PROGRESS,
        ALREADY_DONE,
        RETRYABLE_FAILED,
        NON_RETRYABLE_FAILED
    }

    private data class EnergyRainGameTaskCandidate(
        val taskType: String,
        val taskStatus: String,
        val taskTitle: String,
        val appId: String?,
        val taskProgress: Int,
        val taskRequire: Int,
        val thirdLevel: String
    ) {
        val attemptKey: String
            get() = listOf(taskType, appId.orEmpty(), taskStatus).joinToString("#")
    }

    /**
     * 检查并领取能量雨后的额外游戏任务
     */
    private suspend fun checkAndDoEndGameTask(
        attemptedGameTaskKeys: MutableSet<String>,
        gameTaskCloser: EnergyRainGameDriveCloser?
    ): TaskResult {
        try {
            val response = AntForestRpcCall.queryEnergyRainEndGameList()
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                return TaskResult.NOT_FOUND
            }

            val needInitTask = jo.optBoolean("needInitTask", false)

            val groupTask = jo.optJSONObject("energyRainEndGameGroupTask")
            val taskInfoList = groupTask?.optJSONArray("taskInfoList")
            if (taskInfoList == null || taskInfoList.length() <= 0) {
                if (!needInitTask) {
                    Log.forest("能量雨机会任务当前无待处理候选，视为服务端已无待处理游戏任务")
                    return TaskResult.ALREADY_DONE
                }
                Log.forest("能量雨机会任务提示 needInitTask，但未返回可初始化候选")
                return TaskResult.NOT_FOUND
            }

            val candidates = buildEnergyRainGameTaskCandidates(taskInfoList)
            val actionableCandidates = candidates.filter { it.taskStatus in ENERGY_RAIN_ACTIONABLE_STATUSES }
            if (actionableCandidates.isNotEmpty()) {
                val candidate = selectEnergyRainGameTaskCandidate(actionableCandidates)
                if (!attemptedGameTaskKeys.add(candidate.attemptKey)) {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]本轮已尝试，跳过重复执行")
                    return TaskResult.NOT_FOUND
                }
                return when (executeEnergyRainGameTask(candidate, needInitTask, gameTaskCloser)) {
                    EnergyRainGameExecutionResult.CONFIRMED_DONE -> {
                        Log.forest("能量雨游戏任务[${candidate.taskTitle}]已确认完成")
                        TaskResult.SUCCESS
                    }

                    EnergyRainGameExecutionResult.ALREADY_DONE -> {
                        Log.forest("能量雨机会任务今日已完成[${candidate.taskTitle}]")
                        TaskResult.ALREADY_DONE
                    }

                    EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS -> {
                        Log.forest("能量雨机会任务[${candidate.taskTitle}]未确认完成，本轮止损")
                        TaskResult.NOT_FOUND
                    }

                    EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> {
                        Log.error(TAG, "能量雨机会任务[${candidate.taskTitle}]命中明确不可重试失败，本轮止损")
                        TaskResult.NOT_FOUND
                    }

                    EnergyRainGameExecutionResult.RETRYABLE_FAILED -> {
                        Log.forest("森林能量雨机会任务[${candidate.taskTitle}] 未形成有效进展")
                        TaskResult.NOT_FOUND
                    }
                }
            }

            val terminalTask = candidates.firstOrNull { it.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES }
            if (terminalTask != null) {
                Log.forest("能量雨机会任务今日已完成[${terminalTask.taskTitle}]")
                return TaskResult.ALREADY_DONE
            }

            return TaskResult.NOT_FOUND
        } catch (e: Exception) {
            Log.forest("检查能量雨任务异常: ${e.message}")
            return TaskResult.NOT_FOUND
        }
    }

    private fun buildEnergyRainGameTaskCandidates(taskInfoList: JSONArray): List<EnergyRainGameTaskCandidate> {
        return buildList {
            for (i in 0 until taskInfoList.length()) {
                val task = taskInfoList.optJSONObject(i) ?: continue
                val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
                val taskType = baseInfo.optString("taskType")
                if (taskType.isBlank()) {
                    continue
                }
                val taskStatus = baseInfo.optString("taskStatus")
                val bizInfo = parseEnergyRainTaskJson(baseInfo.opt("bizInfo"))
                val prodPlayParam = parseEnergyRainTaskJson(baseInfo.opt("prodPlayParam"))
                val taskCategorization = prodPlayParam.optJSONObject("taskCategorization")
                val appId = taskCategorization
                    ?.optJSONObject("categorizationParamModel")
                    ?.optString("game_id")
                    ?.takeIf { it.isNotBlank() }
                    ?: extractEnergyRainTaskAppId(bizInfo.optString("taskJumpUrl"))
                val taskTitle = sequenceOf(
                    bizInfo.optString("taskTitle"),
                    bizInfo.optString("title"),
                    bizInfo.optString("taskDesc"),
                    taskType
                ).firstOrNull { it.isNotBlank() } ?: taskType
                add(
                    EnergyRainGameTaskCandidate(
                        taskType = taskType,
                        taskStatus = taskStatus,
                        taskTitle = taskTitle,
                        appId = appId,
                        taskProgress = baseInfo.optInt("taskProgress", 0),
                        taskRequire = baseInfo.optInt("taskRequire", 0),
                        thirdLevel = taskCategorization?.optString("categorizationThirdLevel").orEmpty()
                    )
                )
            }
        }
    }

    private fun parseEnergyRainTaskJson(value: Any?): JSONObject {
        return when (value) {
            is JSONObject -> value
            is String -> {
                if (value.isBlank()) {
                    JSONObject()
                } else {
                    runCatching { JSONObject(value) }.getOrElse { JSONObject() }
                }
            }
            else -> JSONObject()
        }
    }

    private fun extractEnergyRainTaskAppId(url: String): String? {
        return APP_ID_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun selectEnergyRainGameTaskCandidate(
        candidates: List<EnergyRainGameTaskCandidate>
    ): EnergyRainGameTaskCandidate {
        return candidates.firstOrNull(::isForestLmctCandidate)
            ?: candidates.firstOrNull(::isForestSljydCandidate)
            ?: candidates.firstOrNull { !it.appId.isNullOrBlank() }
            ?: candidates.first()
    }

    private fun isForestLmctCandidate(candidate: EnergyRainGameTaskCandidate): Boolean {
        return candidate.taskType == FOREST_LMCT_TASK_TYPE
    }

    private fun isForestSljydCandidate(candidate: EnergyRainGameTaskCandidate): Boolean {
        return candidate.taskType == FOREST_SLJYD_TASK_TYPE ||
            candidate.appId == GameTask.Forest_sljyd.appId ||
            candidate.taskTitle.contains("森林救援队")
    }

    private suspend fun executeEnergyRainGameTask(
        candidate: EnergyRainGameTaskCandidate,
        needInitTask: Boolean,
        gameTaskCloser: EnergyRainGameDriveCloser?
    ): EnergyRainGameExecutionResult {
        return try {
            val clickAppId = candidate.appId?.takeIf { it.isNotBlank() }
                ?: GameTask.Forest_sljyd.appId.takeIf { isForestSljydCandidate(candidate) }
            val appSuffix = " appId=$clickAppId"
            val progressSuffix = " progress=${candidate.taskProgress}/${candidate.taskRequire}"
            val levelSuffix = candidate.thirdLevel.takeIf { it.isNotBlank() }?.let { " level=$it" }.orEmpty()
            Log.forest(
                "发现能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}] " +
                    "status=${candidate.taskStatus}$appSuffix$progressSuffix$levelSuffix，准备执行根闭环"
            )

            var executedClosure = false
            if (candidate.taskStatus == "NOT_TRIGGER") {
                if (needInitTask) {
                    val initResponse = JSONObject(AntForestRpcCall.initTask(candidate.taskType))
                    if (!ResChecker.checkRes(TAG, initResponse)) {
                        val failure = classifyEnergyRainRpcFailure(initResponse)
                        Log.error(TAG, "初始化能量雨机会任务失败[${candidate.taskType}]: ${extractEnergyRainFailureMessage(initResponse)}")
                        return failure
                    }
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]入口已初始化，准备点击游戏入口")
                    randomDelay(800, 1500)
                } else {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]无需初始化，准备点击游戏入口")
                }
            }
            if (candidate.taskStatus in ENERGY_RAIN_ACTIONABLE_STATUSES) {
                if (candidate.taskStatus == "TODO") {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]已处于TODO，继续点击游戏入口以生成能量雨机会")
                }
                if (clickAppId.isNullOrBlank()) {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}]缺少 appId，转入普通驱动任务补充闭环")
                } else {
                    val clickResponse = JSONObject(AntForestRpcCall.clickEnergyRainGame(clickAppId))
                    if (!ResChecker.checkRes(TAG, clickResponse)) {
                        val failure = classifyEnergyRainRpcFailure(clickResponse)
                        Log.error(TAG, "点击能量雨机会游戏失败[$clickAppId]: ${extractEnergyRainFailureMessage(clickResponse)}")
                        return failure
                    }
                    Log.forest("能量雨游戏入口点击成功，检查是否生成机会")
                    randomDelay(2000, 3000)
                    when (val playResult = playEnergyRainChanceGeneratedByGame(candidate)) {
                        EnergyRainGameExecutionResult.CONFIRMED_DONE -> executedClosure = true
                        EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS,
                        EnergyRainGameExecutionResult.ALREADY_DONE -> Unit
                        EnergyRainGameExecutionResult.RETRYABLE_FAILED,
                        EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> return playResult
                    }
                }
            }

            if (!executedClosure && isForestSljydCandidate(candidate)) {
                if (GameTask.Forest_sljyd.report(1)) {
                    executedClosure = true
                } else {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]原 reporter 未确认成功，继续尝试普通驱动任务并回查服务端状态")
                }
            }

            if (!executedClosure) {
                val driveResult = closeMappedEnergyRainDriveTask(candidate, gameTaskCloser)
                executedClosure = driveResult.status in setOf(
                    EnergyRainGameDriveStatus.CONFIRMED_DONE,
                    EnergyRainGameDriveStatus.PROGRESSED
                )
                if (driveResult.status == EnergyRainGameDriveStatus.NON_RETRYABLE_FAILED) {
                    return EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED
                }
                if (driveResult.status == EnergyRainGameDriveStatus.RETRYABLE_FAILED) {
                    return EnergyRainGameExecutionResult.RETRYABLE_FAILED
                }
            }
            randomDelay(1000, 2000)

            val verifyResponse = JSONObject(AntForestRpcCall.queryEnergyRainEndGameList())
            if (!ResChecker.checkRes(TAG, verifyResponse)) {
                return classifyEnergyRainRpcFailure(verifyResponse)
            }
            verifyEnergyRainGameTask(candidate, verifyResponse, executedClosure)
        } catch (e: Exception) {
            Log.forest("执行能量雨机会任务根闭环异常: ${e.message}")
            EnergyRainGameExecutionResult.RETRYABLE_FAILED
        }
    }

    private suspend fun playEnergyRainChanceGeneratedByGame(
        candidate: EnergyRainGameTaskCandidate
    ): EnergyRainGameExecutionResult {
        val homeResponse = JSONObject(
            AntForestRpcCall.queryEnergyRainHome(AntForestRpcCall.ENERGY_RAIN_GAME_ENTRY_SOURCE)
        )
        if (!ResChecker.checkRes(TAG, homeResponse)) {
            Log.forest("能量雨游戏入口点击后查询机会失败，等待后续重试")
            return EnergyRainGameExecutionResult.RETRYABLE_FAILED
        }
        if (!homeResponse.optBoolean("canPlayToday", false)) {
            Log.forest("能量雨游戏入口点击后暂未生成可玩机会，继续尝试补充闭环")
            return EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
        }
        Log.forest("已生成能量雨机会，开始执行能量雨")
        return if (startEnergyRain()) {
            Log.forest("能量雨游戏任务[${candidate.taskTitle}]已完成一次能量雨，准备回查任务状态")
            EnergyRainGameExecutionResult.CONFIRMED_DONE
        } else {
            EnergyRainGameExecutionResult.RETRYABLE_FAILED
        }
    }

    private fun closeMappedEnergyRainDriveTask(
        candidate: EnergyRainGameTaskCandidate,
        gameTaskCloser: EnergyRainGameDriveCloser?
    ): EnergyRainGameDriveResult {
        val driveTaskType = ENERGY_RAIN_DRIVE_TASK_MAPPING[candidate.taskType]
        if (driveTaskType.isNullOrBlank()) {
            Log.forest("能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}]未找到普通森林驱动任务映射，等待抓包或任务列表补充")
            return EnergyRainGameDriveResult(EnergyRainGameDriveStatus.NOT_FOUND)
        }
        if (gameTaskCloser == null) {
            Log.forest("能量雨机会任务[${candidate.taskTitle}]缺少普通森林驱动闭环入口[$driveTaskType]")
            return EnergyRainGameDriveResult(EnergyRainGameDriveStatus.NOT_FOUND)
        }
        val request = EnergyRainGameDriveRequest(
            gameTaskType = candidate.taskType,
            gameTaskTitle = candidate.taskTitle,
            gameTaskStatus = candidate.taskStatus,
            appId = candidate.appId,
            driveTaskType = driveTaskType
        )
        val result = gameTaskCloser.close(request)
        val suffix = result.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        when (result.status) {
            EnergyRainGameDriveStatus.CONFIRMED_DONE,
            EnergyRainGameDriveStatus.PROGRESSED -> Log.forest(
                "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]已执行，回查能量雨状态$suffix"
            )

            EnergyRainGameDriveStatus.SKIPPED_BLACKLISTED -> Log.forest(
                "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]已在黑名单，跳过驱动，不标记完成$suffix"
            )

            EnergyRainGameDriveStatus.NOT_FOUND,
            EnergyRainGameDriveStatus.NO_PROGRESS -> Log.forest(
                "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]未形成进展$suffix"
            )

            EnergyRainGameDriveStatus.RETRYABLE_FAILED,
            EnergyRainGameDriveStatus.NON_RETRYABLE_FAILED -> Log.forest(
                "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]执行失败$suffix"
            )
        }
        return result
    }

    private fun verifyEnergyRainGameTask(
        candidate: EnergyRainGameTaskCandidate,
        verifyResponse: JSONObject,
        executedClosure: Boolean
    ): EnergyRainGameExecutionResult {
        val verifyNeedInitTask = verifyResponse.optBoolean("needInitTask", false)
        val verifyList = verifyResponse
            .optJSONObject("energyRainEndGameGroupTask")
            ?.optJSONArray("taskInfoList")
        if (verifyList == null || verifyList.length() <= 0) {
            return if (!verifyNeedInitTask && executedClosure) {
                EnergyRainGameExecutionResult.CONFIRMED_DONE
            } else {
                EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
            }
        }

        val verifiedCandidates = buildEnergyRainGameTaskCandidates(verifyList)
        val sameTask = verifiedCandidates.firstOrNull { isSameEnergyRainGameTask(candidate, it) }
        if (sameTask == null && !verifyNeedInitTask && executedClosure) {
            return EnergyRainGameExecutionResult.CONFIRMED_DONE
        }
        if (sameTask != null && sameTask.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES) {
            return EnergyRainGameExecutionResult.CONFIRMED_DONE
        }
        return EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
    }

    private fun isSameEnergyRainGameTask(
        expected: EnergyRainGameTaskCandidate,
        actual: EnergyRainGameTaskCandidate
    ): Boolean {
        if (expected.taskType == actual.taskType) {
            return true
        }
        val expectedAppId = expected.appId
        val actualAppId = actual.appId
        return !expectedAppId.isNullOrBlank() &&
            expectedAppId == actualAppId &&
            expected.taskTitle == actual.taskTitle
    }

    private fun classifyEnergyRainRpcFailure(response: JSONObject): EnergyRainGameExecutionResult {
        val code = extractEnergyRainFailureCode(response)
        val message = extractEnergyRainFailureMessage(response)
        return when {
            code == "400000030" ||
                containsAnyEnergyRainFailure(message, "已领取", "已经领取", "重复领取", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                EnergyRainGameExecutionResult.ALREADY_DONE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST", "400000040") ||
                containsAnyEnergyRainFailure(message, "参数错误", "任务ID非法", "模板不存在", "不支持rpc调用") ->
                EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED

            else -> EnergyRainGameExecutionResult.RETRYABLE_FAILED
        }
    }

    private fun containsAnyEnergyRainFailure(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun extractEnergyRainFailureCode(response: JSONObject): String {
        return response.optString("code")
            .ifBlank { response.optString("resultCode") }
            .ifBlank { response.optString("errorCode") }
    }

    private fun extractEnergyRainFailureMessage(response: JSONObject): String {
        return response.optString("desc")
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.toString() }
    }
}

