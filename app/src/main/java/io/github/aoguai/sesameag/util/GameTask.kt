package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class GameTaskReportResult(
    val requestedRewards: Int,
    val requiredSuccesses: Int,
    val attemptedReports: Int,
    val successfulReports: Int,
    val failureMessage: String = "",
    val failureCode: String = "",
    val failureRaw: String = "",
    val stopCurrentRound: Boolean = false,
    val businessConfirmed: Boolean = false,
) {
    val accepted: Boolean
        get() = failureMessage.isBlank() && (businessConfirmed || successfulReports >= requiredSuccesses)
}

private data class GameTaskSingleReportResult(
    val success: Boolean,
    val message: String = "",
    val code: String = "",
    val raw: String = "",
    val stopCurrentRound: Boolean = false,
    val token: String? = null,
)

enum class GameTask(
    val title: String,
    val appId: String,
    val gid: String,
    val action: String,
    val channel: String,
    val version: String,
    val requestsPerEgg: Int,
) {
    Orchard_ncscc(
        "农场乐园:农场上车车",
        "2060170000356601",
        "zfb_ncscc",
        "ncscc_game_kaiche_every_10",
        "nongchangleyuan",
        "1.0.2",
        2,
    ),
    Farm_ddply(
        "庄园乐园:对对碰乐园",
        "2021004149679303",
        "zfb_ddply",
        "ddply_game_xiaochu_every_5",
        "zhuangyuan",
        "1.0.14",
        4,
    ),
    Forest_slxcc(
        "森林乐园:森林小车车",
        "2060170000363691",
        "zfb_slxcc",
        "slxcc_game_kaiche_every_10",
        "lianyun_senlin_leyuan",
        "1.0.1",
        3,
    ),
    Forest_sljyd(
        "森林乐园:森林救援队(能量雨)",
        "2021005113684028",
        "zfb_sljydx",
        "sljyd_game_xiaochu_every_10",
        "lianyun_senlin_leyuan",
        "1.0.1",
        3,
    );

    companion object {
        fun fromAppId(appId: String): GameTask? =
            values().firstOrNull { gameTask -> gameTask.appId == appId }
    }

    private fun logTask(message: String) {
        when (this) {
            Orchard_ncscc -> Log.orchard("[$title]: $message")
            Farm_ddply -> Log.farm("[$title]: $message")
            Forest_slxcc, Forest_sljyd -> Log.forest("[$title]: $message")
        }
    }

    private fun login(): GameTaskSingleReportResult {
        var captureRequestId: String? = null
        var connection: HttpURLConnection? = null
        return try {
            val authCode = AuthCodeHelper.getAuthCode(appId)
            if (authCode.isNullOrBlank()) {
                return GameTaskSingleReportResult(false, "登录失败：授权码为空", stopCurrentRound = true)
            }
            val mark = AlipayMiniMarkHelper.getAlipayMiniMark(appId, version)
            val requestId = "${System.currentTimeMillis()}_${(1..350).random()}"
            val body =
                JSONObject()
                    .put("v", version)
                    .put("code", authCode)
                    .put("pf", "zfb")
                    .put("reqId", requestId)
                    .put("gid", gid)
                    .put("version", version)
                    .toString()
            connection =
                (URL("https://gamesapi2.aslk2018.com/v2/game/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("alipayMiniMark", mark)
                    getDynamicUserAgent().takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                    setRequestProperty("x-release-type", "ONLINE")
                }

            captureRequestId = RpcTrafficCapture.recordModuleHttpRequest(connection, body)
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            RpcTrafficCapture.recordModuleHttpResponse(captureRequestId, connection, responseCode, responseText)
            captureRequestId = null
            val response = JSONObject(responseText)
            val token = if (responseCode in 200..299 && response.optInt("code") == 1) {
                response.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
            } else null
            if (token == null) {
                GameTaskSingleReportResult(
                    false, "登录失败：HTTP=$responseCode", RpcOfflineRisk.extractCode(response),
                    responseText, stopCurrentRound = true,
                )
            } else GameTaskSingleReportResult(true, token = token)
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            GameTaskSingleReportResult(false, "登录异常：${error.message.orEmpty()}", stopCurrentRound = true)
        } finally {
            connection?.disconnect()
        }
    }

    internal suspend fun reportDetailed(
        eggCount: Int,
        actionFinishChannel: String = channel,
        logger: ((String) -> Unit)? = null,
        confirmProgress: (suspend () -> Boolean?)? = null,
    ): GameTaskReportResult {
        val emitLog = logger ?: ::logTask
        if (eggCount <= 0) {
            return GameTaskReportResult(eggCount, 0, 0, 0)
        }
        if (actionFinishChannel.isBlank()) {
            return GameTaskReportResult(
                requestedRewards = eggCount,
                requiredSuccesses = eggCount * requestsPerEgg,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = "action_finish_channel为空",
            )
        }

        val batchSize = when {
            this == Farm_ddply && actionFinishChannel == "zhuangyuan" -> 2
            this == Forest_slxcc && actionFinishChannel == "goldenbean" -> 2
            else -> requestsPerEgg
        }
        val requiredSuccesses = eggCount * batchSize
        currentCoroutineContext().ensureActive()
        val loginResult = login()
        currentCoroutineContext().ensureActive()
        val token = loginResult.token
        if (token.isNullOrBlank()) {
            return GameTaskReportResult(
                requestedRewards = eggCount,
                requiredSuccesses = requiredSuccesses,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = loginResult.message,
                failureCode = loginResult.code,
                failureRaw = loginResult.raw,
                stopCurrentRound = true,
            )
        }

        var attemptedReports = 0
        var successfulReports = 0
        var failure: GameTaskSingleReportResult? = null
        var businessConfirmed = false
        for (index in 1..requiredSuccesses) {
            currentCoroutineContext().ensureActive()
            attemptedReports++
            val result = executeSingleReport(index, requiredSuccesses, actionFinishChannel, token)
            currentCoroutineContext().ensureActive()
            if (!result.success) {
                failure = result
                break
            }
            successfulReports++
            emitLog("上报已接受: $index/$requiredSuccesses，业务进度待回查")
            if (confirmProgress != null) {
                val confirmed = try {
                    confirmProgress()
                } catch (error: Exception) {
                    if (error is CancellationException || error is InterruptedException) throw error
                    failure = GameTaskSingleReportResult(false, "上报后业务查询异常：${error.message.orEmpty()}")
                    null
                }
                currentCoroutineContext().ensureActive()
                if (confirmed == null) {
                    if (failure == null) failure = GameTaskSingleReportResult(false, "上报后业务状态未确认，停止本批上报")
                    break
                }
                if (confirmed) {
                    businessConfirmed = true
                    emitLog("业务状态已确认达成，结束本批上报")
                    break
                }
            }
        }
        return GameTaskReportResult(
            requestedRewards = eggCount,
            requiredSuccesses = requiredSuccesses,
            attemptedReports = attemptedReports,
            successfulReports = successfulReports,
            failureMessage = failure?.message.orEmpty(),
            failureCode = failure?.code.orEmpty(),
            failureRaw = failure?.raw.orEmpty(),
            stopCurrentRound = failure?.stopCurrentRound == true,
            businessConfirmed = businessConfirmed,
        )
    }

    private fun executeSingleReport(
        current: Int,
        total: Int,
        actionFinishChannel: String,
        token: String,
    ): GameTaskSingleReportResult {
        var captureRequestId: String? = null
        var connection: HttpURLConnection? = null
        return try {
            val mark = AlipayMiniMarkHelper.getAlipayMiniMark(appId, version)
            val body =
                JSONObject()
                    .put("v", version)
                    .put("version", version)
                    .put("reqId", "${System.currentTimeMillis()}_${(10..99).random()}")
                    .put("gid", gid)
                    .put("action_code", action)
                    .put("action_finish_channel", actionFinishChannel)
                    .toString()
            connection =
                (URL("https://gamesapi2.aslk2018.com/v2/zfb/taskReport").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("authorization", token)
                    setRequestProperty("alipayMiniMark", mark)
                    setRequestProperty("Content-Type", "application/json")
                    getDynamicUserAgent().takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                    setRequestProperty("x-release-type", "ONLINE")
                    setRequestProperty("referer", "https://$appId.hybrid.alipay-eco.com/$appId/$version/index.html")
                }

            captureRequestId = RpcTrafficCapture.recordModuleHttpRequest(connection, body)
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            RpcTrafficCapture.recordModuleHttpResponse(captureRequestId, connection, responseCode, responseText)
            captureRequestId = null
            val response = JSONObject(responseText)
            if (responseCode in 200..299 && response.optInt("code") == 1) {
                GameTaskSingleReportResult(success = true)
            } else {
                GameTaskSingleReportResult(
                    success = false,
                    message = "上报失败：进度=$current/$total HTTP=$responseCode",
                    code = RpcOfflineRisk.extractCode(response),
                    raw = responseText,
                    stopCurrentRound = responseCode in setOf(401, 403) || RpcOfflineRisk.isOfflineRisk(response),
                )
            }
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            GameTaskSingleReportResult(
                success = false,
                message = "上报异常：进度=$current/$total ${error.message.orEmpty()}",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun getDynamicUserAgent(): String {
        val systemUserAgent = System.getProperty("http.agent").orEmpty()
        val alipayVersion = ApplicationHook.alipayVersion.versionString
        return buildString {
            append(systemUserAgent)
            if (alipayVersion.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("AliApp(AP/$alipayVersion) AlipayClient/$alipayVersion")
            }
        }
    }
}
