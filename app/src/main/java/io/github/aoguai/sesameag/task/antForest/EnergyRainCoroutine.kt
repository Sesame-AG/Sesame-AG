package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.friend.FriendRepository
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
    private const val FOREST_SLJYD_TASK_TYPE = "GAME_DONE_SLJYD"
    private const val ENERGY_RAIN_GAME_SCENE_CODE = "ANTFOREST_ENERGY_RAIN_TASK"
    private val APP_ID_QUERY_REGEX = Regex("""(?:^|[?&])appId=([0-9]+)""")
    private val ENERGY_RAIN_ACTIONABLE_STATUSES = setOf("TODO", "NOT_TRIGGER")
    private val ENERGY_RAIN_TERMINAL_STATUSES = setOf("FINISHED", "DONE", "RECEIVED", "SUCCESS", "COMPLETED")
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
        "RAIN_ENERGY_GRANTED_USED",
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
        val driveTaskType: String,
        val sceneCode: String = ENERGY_RAIN_GAME_SCENE_CODE,
        val taskProgress: Int = 0,
        val taskRequire: Int = 0
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
            var pendingGameTaskRecheck = false
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
                var grantBlocksGameCheck = canGrantStatus && !grantExceedToday
                var grantBlockReason = "仍有待处理的赠送能量雨机会。"
                Log.forest("能量雨状态[轮次:${playedCount + 1}][manual=$isManual][canPlayToday=$canPlayToday][canGrantStatus=$canGrantStatus][canPlayGame=$canPlayGame][grantExceedToday=$grantExceedToday][gameTaskFlag=${Status.hasFlagToday(energyRainGameFlag)}]"
                )

                var worked = false

                // 1️⃣ 检查是否可以开始能量雨
                if (canPlayToday) {
                    pendingGameTaskRecheck = false
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
                        grantBlocksGameCheck = false
                        Log.forest("今日已达到赠送能量雨上限，跳过赠送环节")
                    } else {
                        val joEnergyRainCanGrantList = JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList())
                        if (!ResChecker.checkRes(TAG, joEnergyRainCanGrantList)) {
                            grantBlocksGameCheck = true
                            grantBlockReason = "可送好友查询失败，等待后续重试。"
                            Log.forest("查询可送能量雨好友失败，等待后续重试")
                        } else {
                            val grantInfos = joEnergyRainCanGrantList.optJSONArray("grantInfos") ?: org.json.JSONArray()
                            var granted = false
                            var matchedConfiguredTarget = false
                            var retryableGrantFailure = false

                            for (j in 0 until grantInfos.length()) {
                                val grantInfo = grantInfos.getJSONObject(j)
                                if (!grantInfo.optBoolean("canGrantedStatus", false)) {
                                    continue
                                }
                                val uid = grantInfo.optString("userId")
                                if (!isConfiguredEnergyRainGrantTarget(uid)) {
                                    continue
                                }
                                matchedConfiguredTarget = true
                                if (shouldSkipEnergyRainGrantTarget(uid)) {
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

                                        "RAIN_ENERGY_GRANTED_USED" -> {
                                            Log.forest("跳过赠送【$maskedName】:${resultDesc.ifEmpty { "该好友今日已被赠送或本次机会已用完" }}")
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
                                    retryableGrantFailure = true
                                    Log.error(TAG, "送能量雨失败 $rainJsonObj")
                                }
                            }

                            grantBlocksGameCheck = when {
                                grantExceedToday -> false
                                granted -> true
                                retryableGrantFailure -> true
                                else -> false
                            }
                            grantBlockReason = when {
                                grantExceedToday -> ""
                                granted -> "赠送环节已执行，等待服务端刷新。"
                                retryableGrantFailure -> "当前配置好友赠送未完成，等待后续重试。"
                                !matchedConfiguredTarget -> "当前配置未命中任何服务端可送好友。"
                                else -> "当前配置好友赠送已处理完毕。"
                            }

                            if (granted) {
                                worked = true
                            } else {
                                when {
                                    retryableGrantFailure -> Log.forest("当前配置好友赠送未完成，等待后续重试")
                                    !matchedConfiguredTarget -> Log.forest("当前配置未命中任何可送能量雨好友")
                                    else -> Log.forest("当前配置好友赠送已处理完毕")
                                }
                            }
                        }
                    }
                }

                // 3️⃣ 检查是否可以能量雨游戏
                // 能量雨赠送以服务端 grantInfos 为准；若当前配置下没有待处理目标，不应继续阻塞游戏任务闭环。
                val canEnterGameCheck = isManual || (!canPlayToday && !grantBlocksGameCheck)
                if (canEnterGameCheck) {
                    if (canPlayGame && (isManual || !Status.hasFlagToday(energyRainGameFlag))) {
                        Log.forest("检查能量雨游戏任务")
                        val taskResult = checkAndDoEndGameTask(attemptedGameTaskKeys, gameTaskCloser)//检查能量雨 游戏任务 并接取
                        if (taskResult == TaskResult.SUCCESS) {
                            pendingGameTaskRecheck = false
                            if (!isManual) {
                                Status.setFlagToday(energyRainGameFlag)
                            }
                            randomDelay(3000, 5000) // 随机延迟3-5秒
                            playedCount++
                            shouldRunPostFlow = true
                            worked = true
                        } else if (taskResult == TaskResult.PROGRESSED) {
                            pendingGameTaskRecheck = true
                            Log.forest("能量雨游戏任务已推进，当前轮次继续回查")
                            worked = true
                            randomDelay(1500, 2500)
                        } else if (taskResult == TaskResult.ALREADY_DONE) {
                            pendingGameTaskRecheck = false
                            // 确定任务已完成或今日不可用，才设置标记
                            if (!isManual) {
                                Status.setFlagToday(energyRainGameFlag)
                            }
                        }
                    }
                } else if (!isManual && !Status.hasFlagToday(energyRainGameFlag)) {
                    if (canPlayToday) {
                        Log.forest("跳过游戏任务检查：常规能量雨机会尚未耗尽。")
                    } else if (grantBlocksGameCheck) {
                        Log.forest("跳过游戏任务检查：$grantBlockReason")
                    }
                }

                if (!worked) {
                    if (pendingGameTaskRecheck) {
                        Log.forest("能量雨游戏任务已推进，但本轮未产出机会，留待后续统一调度")
                    }
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
        PROGRESSED,     // 已推进但未闭环完成
        ALREADY_DONE,   // 任务已完成或确定不可用
        NOT_FOUND       // 未发现任务（可能是接口更新延迟）
    }

    private enum class EnergyRainGameExecutionResult {
        CONFIRMED_DONE,
        PROGRESSED,
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
        val sceneCode: String,
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
                var attemptedCandidate = false
                for (candidate in actionableCandidates) {
                    if (!attemptedGameTaskKeys.add(candidate.attemptKey)) {
                        Log.forest("能量雨机会任务[${candidate.taskTitle}]本轮已尝试，跳过重复执行")
                        continue
                    }
                    attemptedCandidate = true
                    when (executeEnergyRainGameTask(candidate, needInitTask, gameTaskCloser)) {
                        EnergyRainGameExecutionResult.CONFIRMED_DONE -> {
                            Log.forest("能量雨游戏任务[${candidate.taskTitle}]已确认完成")
                            return TaskResult.SUCCESS
                        }

                        EnergyRainGameExecutionResult.ALREADY_DONE -> {
                            Log.forest("能量雨机会任务今日已完成[${candidate.taskTitle}]")
                            return TaskResult.ALREADY_DONE
                        }

                        EnergyRainGameExecutionResult.PROGRESSED -> {
                            Log.forest("能量雨机会任务[${candidate.taskTitle}]已推进，当前轮次继续回查")
                            return TaskResult.PROGRESSED
                        }

                        EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS -> {
                            Log.forest("能量雨机会任务[${candidate.taskTitle}]本轮未形成确认进展，保留后续统一调度")
                        }

                        EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> {
                            Log.error(TAG, "能量雨机会任务[${candidate.taskTitle}]命中明确不可重试失败，继续检查其他候选")
                        }

                        EnergyRainGameExecutionResult.RETRYABLE_FAILED -> {
                            Log.forest("森林能量雨机会任务[${candidate.taskTitle}]本轮未形成有效进展，继续检查其他候选")
                        }
                    }
                }
                if (!attemptedCandidate) {
                    Log.forest("能量雨机会任务候选本轮均已尝试，等待服务端状态刷新")
                }
                return TaskResult.NOT_FOUND
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
                val sceneCode = baseInfo.optString("sceneCode").ifBlank { ENERGY_RAIN_GAME_SCENE_CODE }
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
                        sceneCode = sceneCode,
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
            val appSuffix = clickAppId?.let { " appId=$it" }.orEmpty()
            val sceneSuffix = " scene=${candidate.sceneCode}"
            val progressSuffix = " progress=${candidate.taskProgress}/${candidate.taskRequire}"
            val levelSuffix = candidate.thirdLevel.takeIf { it.isNotBlank() }?.let { " level=$it" }.orEmpty()
            Log.forest(
                "发现能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}] " +
                    "status=${candidate.taskStatus}$sceneSuffix$appSuffix$progressSuffix$levelSuffix，准备执行融合闭环"
            )

            var executedClosure = false
            var closureProgressed = false
            val precheckHome = queryEnergyRainGameHome("执行前") ?: return EnergyRainGameExecutionResult.RETRYABLE_FAILED
            when (val precheckResult = playEnergyRainChanceFromHome(candidate, precheckHome, "执行前")) {
                EnergyRainGameExecutionResult.CONFIRMED_DONE -> {
                    executedClosure = true
                    closureProgressed = true
                }
                EnergyRainGameExecutionResult.RETRYABLE_FAILED -> return precheckResult
                EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS,
                EnergyRainGameExecutionResult.PROGRESSED,
                EnergyRainGameExecutionResult.ALREADY_DONE,
                EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> Unit
            }

            if (!executedClosure) {
                syncEnergyRainEndContext(precheckHome)
            }

            if (!executedClosure && candidate.taskStatus == "NOT_TRIGGER") {
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
            if (!executedClosure && candidate.taskStatus in ENERGY_RAIN_ACTIONABLE_STATUSES) {
                if (candidate.taskStatus == "TODO") {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]已处于TODO，继续按游戏中心融合链路触达入口")
                }
                if (clickAppId.isNullOrBlank()) {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}]缺少 appId，跳过入口点击并继续统一驱动回查")
                } else {
                    val clickResponse = JSONObject(AntForestRpcCall.clickEnergyRainGame(clickAppId))
                    if (!ResChecker.checkRes(TAG, clickResponse)) {
                        val failure = classifyEnergyRainRpcFailure(clickResponse)
                        Log.error(TAG, "点击能量雨机会游戏失败[$clickAppId]: ${extractEnergyRainFailureMessage(clickResponse)}")
                        return failure
                    }
                    Log.forest("能量雨游戏入口点击成功，检查是否生成机会")
                    randomDelay(2000, 3000)
                    when (val playResult = queryAndPlayEnergyRainChance(candidate, "入口点击后")) {
                        EnergyRainGameExecutionResult.CONFIRMED_DONE -> {
                            executedClosure = true
                            closureProgressed = true
                        }
                        EnergyRainGameExecutionResult.PROGRESSED -> {
                            closureProgressed = true
                        }
                        EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS,
                        EnergyRainGameExecutionResult.ALREADY_DONE -> Unit
                        EnergyRainGameExecutionResult.RETRYABLE_FAILED,
                        EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> return playResult
                    }
                }
            }

            if (!executedClosure && isForestSljydCandidate(candidate)) {
                if (GameTask.Forest_sljyd.report(1)) {
                    closureProgressed = true
                } else {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]原 reporter 未确认成功，继续尝试普通驱动任务并回查服务端状态")
                }
            }

            if (!executedClosure && !closureProgressed) {
                val driveResult = closeMappedEnergyRainDriveTask(candidate, gameTaskCloser)
                closureProgressed = closureProgressed || driveResult.status in setOf(
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

            when (val finalPlayResult = queryAndPlayEnergyRainChance(candidate, "融合链路最终回查")) {
                EnergyRainGameExecutionResult.CONFIRMED_DONE -> {
                    executedClosure = true
                    closureProgressed = true
                }

                EnergyRainGameExecutionResult.RETRYABLE_FAILED -> {
                    Log.forest("能量雨机会任务[${candidate.taskTitle}]最终回查机会失败，继续回查任务状态")
                }

                EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS,
                EnergyRainGameExecutionResult.PROGRESSED,
                EnergyRainGameExecutionResult.ALREADY_DONE,
                EnergyRainGameExecutionResult.NON_RETRYABLE_FAILED -> Unit
            }
            randomDelay(1000, 2000)

            val verifyResponse = JSONObject(AntForestRpcCall.queryEnergyRainEndGameList())
            if (!ResChecker.checkRes(TAG, verifyResponse)) {
                return classifyEnergyRainRpcFailure(verifyResponse)
            }
            val verifyResult = verifyEnergyRainGameTask(candidate, verifyResponse)
            if (verifyResult == EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS && closureProgressed) {
                EnergyRainGameExecutionResult.PROGRESSED
            } else {
                verifyResult
            }
        } catch (e: Exception) {
            Log.forest("执行能量雨机会任务根闭环异常: ${e.message}")
            EnergyRainGameExecutionResult.RETRYABLE_FAILED
        }
    }

    private fun queryEnergyRainGameHome(phase: String): JSONObject? {
        return try {
            val homeResponse = JSONObject(
                AntForestRpcCall.queryEnergyRainHome(AntForestRpcCall.ENERGY_RAIN_GAME_ENTRY_SOURCE)
            )
            if (!ResChecker.checkRes(TAG, homeResponse)) {
                Log.forest("能量雨游戏任务[$phase]查询机会失败，等待后续重试")
                null
            } else {
                homeResponse
            }
        } catch (e: Exception) {
            Log.forest("能量雨游戏任务[$phase]查询机会异常: ${e.message}")
            null
        }
    }

    private suspend fun queryAndPlayEnergyRainChance(
        candidate: EnergyRainGameTaskCandidate,
        phase: String
    ): EnergyRainGameExecutionResult {
        val homeResponse = queryEnergyRainGameHome(phase) ?: return EnergyRainGameExecutionResult.RETRYABLE_FAILED
        return playEnergyRainChanceFromHome(candidate, homeResponse, phase)
    }

    private suspend fun playEnergyRainChanceFromHome(
        candidate: EnergyRainGameTaskCandidate,
        homeResponse: JSONObject,
        phase: String
    ): EnergyRainGameExecutionResult {
        if (!homeResponse.optBoolean("canPlayToday", false)) {
            Log.forest("能量雨机会任务[${candidate.taskTitle}][$phase]暂未生成可玩机会，继续融合链路回查")
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

    private fun syncEnergyRainEndContext(homeResponse: JSONObject) {
        val currentEnergy = extractEnergyRainCurrentEnergy(homeResponse)
        if (currentEnergy == null) {
            Log.forest("能量雨结束页上下文缺少 currentEnergy，跳过 guideDecisionEntrance")
        } else {
            runCatching {
                JSONObject(AntForestRpcCall.guideEnergyRainEnd(currentEnergy))
            }.onSuccess { guideResponse ->
                if (!ResChecker.checkRes(TAG, guideResponse)) {
                    Log.forest("能量雨结束页导流上下文未确认成功: ${extractEnergyRainFailureMessage(guideResponse)}")
                }
            }.onFailure { e ->
                Log.forest("能量雨结束页导流上下文异常: ${e.message}")
            }
        }

        runCatching {
            JSONObject(AntForestRpcCall.queryEnergyRainRanking())
        }.onSuccess { rankingResponse ->
            if (!ResChecker.checkRes(TAG, rankingResponse)) {
                Log.forest("能量雨排行榜上下文未确认成功: ${extractEnergyRainFailureMessage(rankingResponse)}")
            }
        }.onFailure { e ->
            Log.forest("能量雨排行榜上下文异常: ${e.message}")
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

    private fun closeMappedEnergyRainDriveTask(
        candidate: EnergyRainGameTaskCandidate,
        gameTaskCloser: EnergyRainGameDriveCloser?
    ): EnergyRainGameDriveResult {
        val driveTaskType = ENERGY_RAIN_DRIVE_TASK_MAPPING[candidate.taskType]
        if (gameTaskCloser == null) {
            Log.forest("能量雨机会任务[${candidate.taskTitle}]缺少森林游戏中心融合闭环入口[$driveTaskType]")
            return EnergyRainGameDriveResult(EnergyRainGameDriveStatus.NOT_FOUND)
        }
        val request = EnergyRainGameDriveRequest(
            gameTaskType = candidate.taskType,
            gameTaskTitle = candidate.taskTitle,
            gameTaskStatus = candidate.taskStatus,
            appId = candidate.appId,
            driveTaskType = driveTaskType.orEmpty(),
            sceneCode = candidate.sceneCode,
            taskProgress = candidate.taskProgress,
            taskRequire = candidate.taskRequire
        )
        if (driveTaskType.isNullOrBlank()) {
            val result = gameTaskCloser.close(request)
            val suffix = result.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            Log.forest(
                "能量雨机会任务[${candidate.taskTitle}][${candidate.taskType}]未找到普通森林驱动任务映射，" +
                    "已同步游戏中心上下文并保留后续统一调度$suffix"
            )
            return result
        }
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
                "能量雨机会任务[${candidate.taskTitle}]普通驱动任务[$driveTaskType]本轮未形成确认进展，保留后续统一调度$suffix"
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
        verifyResponse: JSONObject
    ): EnergyRainGameExecutionResult {
        val verifyNeedInitTask = verifyResponse.optBoolean("needInitTask", false)
        val verifyList = verifyResponse
            .optJSONObject("energyRainEndGameGroupTask")
            ?.optJSONArray("taskInfoList")
        if (verifyList == null || verifyList.length() <= 0) {
            return if (!verifyNeedInitTask) {
                EnergyRainGameExecutionResult.CONFIRMED_DONE
            } else {
                EnergyRainGameExecutionResult.EXECUTED_NO_PROGRESS
            }
        }

        val verifiedCandidates = buildEnergyRainGameTaskCandidates(verifyList)
        val sameTask = verifiedCandidates.firstOrNull { isSameEnergyRainGameTask(candidate, it) }
        if (sameTask == null && !verifyNeedInitTask) {
            return EnergyRainGameExecutionResult.CONFIRMED_DONE
        }
        if (sameTask != null && sameTask.taskStatus in ENERGY_RAIN_TERMINAL_STATUSES) {
            return EnergyRainGameExecutionResult.CONFIRMED_DONE
        }
        if (sameTask != null && hasEnergyRainTaskProgressed(candidate, sameTask)) {
            return EnergyRainGameExecutionResult.PROGRESSED
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

    private fun hasEnergyRainTaskProgressed(
        before: EnergyRainGameTaskCandidate,
        after: EnergyRainGameTaskCandidate
    ): Boolean {
        return (before.taskStatus == "NOT_TRIGGER" && after.taskStatus == "TODO") ||
            after.taskProgress > before.taskProgress
    }

    private fun isConfiguredEnergyRainGrantTarget(userId: String?): Boolean {
        return AntForest.giveEnergyRainList?.containsConfigured(userId) == true
    }

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
