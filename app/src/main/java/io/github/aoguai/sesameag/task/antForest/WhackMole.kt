package io.github.aoguai.sesameag.task.antForest

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 6秒拼手速打地鼠
 * 整合版本：适配最新 RPC 定义
 */
object WhackMole {
    private const val TAG = "WhackMole"
    private const val SOURCE = "senlinguangchangdadishu"

    @Volatile
    private var totalGames = 5

    @Volatile
    private var moleCount = 15 // 兼容模式默认击打数

    private const val GAME_DURATION_MS = 6000L
    private const val SETTLEMENT_LEAD_MS = 500L
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startTime = AtomicLong(0)

    @Volatile
    private var isRunning = false

    enum class Mode {
        COMPATIBLE,
        AGGRESSIVE
    }

    data class GameSession(
        val token: String,
        val roundNumber: Int,
        val moleIdList: List<String>
    )

    fun setTotalGames(games: Int) {
        totalGames = games
    }

    fun setMoleCount(count: Int) {
        moleCount = count
    }

    private val intervalCalculator = GameIntervalCalculator

    suspend fun startSuspend(mode: Mode): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.forest("⏭️ 打地鼠游戏正在运行中，跳过重复启动")
            return@withContext false
        }
        isRunning = true

        try {
            val executed = when (mode) {
                Mode.COMPATIBLE -> runCompatibleMode()
                Mode.AGGRESSIVE -> runAggressiveMode()
            }
            if (executed) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
            }
            return@withContext executed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "打地鼠异常: ", e)
            return@withContext false
        } finally {
            isRunning = false
            Log.forest("🎮 打地鼠运行状态已重置")
        }
    }

    fun start(mode: Mode) {
        globalScope.launch {
            startSuspend(mode)
        }
    }

    private suspend fun runCompatibleMode(): Boolean {
        try {
            val startTs = System.currentTimeMillis()
            val response = JSONObject(AntForestRpcCall.oldstartWhackMole(SOURCE))
            if (!ResChecker.checkRes(TAG, response)) {
                Log.forest(response.optString("resultDesc", "开始失败"))
                return false
            }
            if (!response.optBoolean("canPlayToday", true)) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
                logTodaySettleInfo(response)
                return false
            }

            val moleInfoArray = response.optJSONArray("moleInfo")
            val token = response.optString("token")
            if (moleInfoArray == null || moleInfoArray.length() == 0 || token.isEmpty()) {
                Log.forest("🎮 拼手速未返回可结算地鼠信息，跳过")
                return false
            }

            val allMoleIds = mutableListOf<Long>()
            val bubbleMoleIds = mutableListOf<Long>()
            for (i in 0 until moleInfoArray.length()) {
                val mole = moleInfoArray.getJSONObject(i)
                val moleId = mole.getLong("id")
                allMoleIds.add(moleId)
                if (mole.has("bubbleId")) {
                    bubbleMoleIds.add(moleId)
                }
            }

            var hitCount = 0
            bubbleMoleIds.forEach { moleId ->
                try {
                    val whackResp = JSONObject(AntForestRpcCall.oldwhackMole(moleId, token, SOURCE))
                    if (whackResp.optBoolean("success")) {
                        val energy = whackResp.optInt("energyAmount", 0)
                        hitCount++
                        Log.forest("森林能量⚡️[兼容打地鼠:$moleId +${energy}g]")
                        if (hitCount < bubbleMoleIds.size) {
                            delay(100 + (0..200).random().toLong())
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }

            val settlementIds = allMoleIds.filter { !bubbleMoleIds.contains(it) }
                .take(moleCount)
                .map { it.toString() }
            val elapsedTime = System.currentTimeMillis() - startTs
            delay(max(0L, 6000L - elapsedTime - 200L))

            val settleResp = JSONObject(AntForestRpcCall.oldsettlementWhackMole(token, settlementIds, SOURCE))
            if (ResChecker.checkRes(TAG, settleResp)) {
                val total = settleResp.optInt("totalEnergy", 0)
                Log.forest("森林能量⚡️[兼容模式完成(打${settlementIds.size + hitCount}个) 总能量+${total}g]")
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.forest("兼容模式出错: ${t.message}")
        }
        return false
    }

    @SuppressLint("DefaultLocale")
    private suspend fun runAggressiveMode(): Boolean {
        startTime.set(System.currentTimeMillis())
        val dynamicInterval = intervalCalculator.calculateDynamicInterval(GAME_DURATION_MS, totalGames)

        val sessions = mutableListOf<GameSession>()
        var triggered = false
        try {
            for (roundNum in 1..totalGames) {
                val session = startSingleRound(roundNum)
                if (session != null) {
                    sessions.add(session)
                    triggered = true
                }

                if (roundNum < totalGames) {
                    val remaining = GAME_DURATION_MS - (System.currentTimeMillis() - startTime.get())
                    val nextDelay = intervalCalculator.calculateNextDelay(dynamicInterval, roundNum, totalGames, remaining)
                    delay(max(0L, nextDelay))
                }
            }
        } catch (e: CancellationException) {
            return triggered
        }

        val waitTime = max(0L, GAME_DURATION_MS - (System.currentTimeMillis() - startTime.get()) - SETTLEMENT_LEAD_MS)
        delay(waitTime)

        var totalEnergy = 0
        sessions.forEach { session ->
            totalEnergy += settleStandardRound(session)
        }
        Log.forest("森林能量⚡️[激进模式${sessions.size}局 总计${totalEnergy}g]")
        return triggered
    }

    private suspend fun startSingleRound(round: Int): GameSession? {
        return try {
            val startResp = JSONObject(AntForestRpcCall.startWhackMole())
            if (!ResChecker.checkRes(TAG, startResp)) {
                null
            } else if (!startResp.optBoolean("canPlayToday", true)) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
                logTodaySettleInfo(startResp)
                throw CancellationException("Today limit reached")
            } else {
                val token = startResp.optString("token")
                val moleIdList = buildList {
                    val moleInfoArray = startResp.optJSONArray("moleInfo")
                    if (moleInfoArray != null) {
                        for (i in 0 until moleInfoArray.length()) {
                            val moleId = moleInfoArray.optJSONObject(i)?.optLong("id") ?: continue
                            if (moleId > 0) {
                                add(moleId.toString())
                            }
                        }
                    }
                }.ifEmpty { (1..15).map { it.toString() } }
                if (token.isBlank()) {
                    return null
                }
                Toast.show("打地鼠 第${round}局启动\nToken: $token")
                GameSession(token, round, moleIdList)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun settleStandardRound(session: GameSession): Int {
        return try {
            val resp = JSONObject(AntForestRpcCall.settlementWhackMole(session.token, session.moleIdList, SOURCE))
            if (resp.optString("resultCode") == "WHACK_MOLE_GAME_OVER") {
                Log.forest("🎮 拼手速第${session.roundNumber}局结算已超过服务端窗口，跳过")
                return 0
            }
            if (ResChecker.checkRes(TAG, resp)) {
                resp.optInt("totalEnergy", 0)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun logTodaySettleInfo(response: JSONObject) {
        val settleInfo = response.optJSONObject("currentDaySettleInfoVO")
        val totalEnergy = settleInfo?.optInt("totalEnergy", 0) ?: 0
        val totalFriendNums = settleInfo?.optInt("totalFriendNums", 0) ?: 0
        if (totalEnergy > 0 || totalFriendNums > 0) {
            Log.forest("🎮 拼手速今日次数已用尽，今日已结算${totalFriendNums}个好友，总能量+${totalEnergy}g")
        } else {
            Log.forest("🎮 拼手速今日次数已用尽，跳过")
        }
    }
}
