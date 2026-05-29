package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.UserMap
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
    private val SILENT_GRANT_FAILURE_CODES = setOf(
        "FRIEND_NOT_FOREST_USER",
        "RAIN_ENERGY_GRANTED_BY_OTHER",
        "RAIN_ENERGY_GRANT_EXCEED"
    )

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
    suspend fun execEnergyRain(isManual: Boolean = false): Boolean {
        try {
            // 执行频率检查：防止短时间内重复执行
            val currentTime = System.currentTimeMillis()
            val timeSinceLastExec = currentTime - lastExecuteTime
            val cooldownSeconds = 3 // 冷却时间：3秒

            if (timeSinceLastExec < cooldownSeconds * 1000) {
                // 粗放点，delay 3秒
                delay(cooldownSeconds * 1000.toLong())
            }

            val executed = energyRain(isManual)

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
    private suspend fun energyRain(isManual: Boolean): Boolean {
        try {
            var playedCount = 0
            val maxPlayLimit = 10
            var shouldRunPostFlow = false

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
                // canPlayGame 好像一直是true        注意：能量雨游戏只能执行一次，执行后会设置标记防止重复
                // 修复逻辑：只有常规机会用完 (!canPlayToday) 且赠送机会也已全部处理 (!canGrantStatus) 时，才检查收尾游戏任务
                val canEnterGameCheck = isManual || (!canPlayToday && (!canGrantStatus || grantExceedToday))
                if (canEnterGameCheck) {
                    if (canPlayGame && (isManual || !Status.hasFlagToday(energyRainGameFlag))) {
                        Log.forest("检查能量雨游戏任务")
                        val taskResult = checkAndDoEndGameTask()//检查能量雨 游戏任务 并接取
                        if (taskResult == TaskResult.SUCCESS) {
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

    /**
     * 检查并领取能量雨后的额外游戏任务
     */
    private suspend fun checkAndDoEndGameTask(): TaskResult {
        try {
            // 1. 查询当前是否有可接或已接的游戏任务
            var response = AntForestRpcCall.queryEnergyRainEndGameList()
            var jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                return TaskResult.NOT_FOUND
            }

            // 2. 先处理“有新任务可以接”的情况（需要初始化）
            if (jo.optBoolean("needInitTask", false)) {
                Log.forest("初始化能量雨机会任务...")
                val initRes = JSONObject(AntForestRpcCall.initTask("GAME_DONE_SLJYD"))
                if (ResChecker.checkRes(TAG, initRes)) {
                    randomDelay(1000, 2000)
                    // 初始化后必须重新查询列表，否则下面的 Step 3 使用的是旧数据
                    response = AntForestRpcCall.queryEnergyRainEndGameList()
                    jo = JSONObject(response)
                    if (!ResChecker.checkRes(TAG, jo)) return TaskResult.NOT_FOUND
                } else {
                    return TaskResult.NOT_FOUND
                }
            }

            // 3. 核心逻辑：遍历任务列表，检查是否有处于 TO DO 状态的任务
            val groupTask = jo.optJSONObject("energyRainEndGameGroupTask")
            val taskInfoList = groupTask?.optJSONArray("taskInfoList")
            if (taskInfoList != null && taskInfoList.length() > 0) {
                for (i in 0 until taskInfoList.length()) {
                    val task = taskInfoList.getJSONObject(i)
                    val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
                    val taskType = baseInfo.optString("taskType")
                    val taskStatus = baseInfo.optString("taskStatus") // 关键状态

                    if (taskType == "GAME_DONE_SLJYD") {
                        if (taskStatus == "TODO" || taskStatus == "NOT_TRIGGER") {
                            val bizInfoStr = baseInfo.optString("bizInfo")
                            val taskTitle = if (bizInfoStr.isNotEmpty()) {
                                JSONObject(bizInfoStr).optString("taskTitle", "能量雨游戏任务")
                            } else {
                                "能量雨游戏任务"
                            }

                            Log.forest("发现待完成任务[$taskTitle]，准备执行上报...")
                            AntForestRpcCall.clickGame("2021005113684028")
                            randomDelay(2000, 3000)
                            if (GameTask.Forest_sljyd.report(1)) {
                                Log.forest("森林能量雨机会任务[$taskTitle] 已完成")
                                return TaskResult.SUCCESS
                            }
                            return TaskResult.NOT_FOUND
                        } else if (taskStatus == "FINISHED" || taskStatus == "DONE") {
                            Log.forest("能量雨机会任务今日已完成")
                            return TaskResult.ALREADY_DONE
                        }
                    }
                }
            }
            // 没找到特定任务
            return TaskResult.NOT_FOUND
        } catch (e: Exception) {
            Log.forest("检查能量雨任务异常: ${e.message}")
            return TaskResult.NOT_FOUND
        }
    }
}

