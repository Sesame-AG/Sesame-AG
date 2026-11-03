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
) {
    val completed: Boolean
        get() = successfulReports >= requiredSuccesses
}

private data class GameTaskSingleReportResult(
    val success: Boolean,
    val message: String = "",
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

    private fun login(logger: (String) -> Unit): String? {
        var captureRequestId: String? = null
        var connection: HttpURLConnection? = null
        return try {
            val authCode = AuthCodeHelper.getAuthCode(appId)
            if (authCode.isNullOrBlank()) {
                logger("登录失败：授权码为空")
                return null
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
            if (token == null) logger("登录失败：HTTP=$responseCode response=$responseText")
            token
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            logger("登录异常：${error.message.orEmpty()}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun report(eggCount: Int): Boolean = reportDetailed(eggCount).completed

    internal suspend fun reportDetailed(
        eggCount: Int,
        actionFinishChannel: String = channel,
        includeSafetyReport: Boolean = true,
        logger: ((String) -> Unit)? = null,
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

        val requiredSuccesses = eggCount * requestsPerEgg
        val totalReports = requiredSuccesses + if (includeSafetyReport) 1 else 0
        currentCoroutineContext().ensureActive()
        val token = login(emitLog)
        currentCoroutineContext().ensureActive()
        if (token.isNullOrBlank()) {
            emitLog("登录失败：未取得有效登录凭证，停止上报")
            return GameTaskReportResult(
                requestedRewards = eggCount,
                requiredSuccesses = requiredSuccesses,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = "登录失败：未取得有效登录凭证",
            )
        }

        var attemptedReports = 0
        var successfulReports = 0
        var failureMessage = ""
        for (index in 1..totalReports) {
            currentCoroutineContext().ensureActive()
            attemptedReports++
            val result = executeSingleReport(index, totalReports, actionFinishChannel, token)
            currentCoroutineContext().ensureActive()
            if (!result.success) {
                failureMessage = result.message
                break
            }
            successfulReports++
            if (index % requestsPerEgg == 0) {
                emitLog("进度: $index/$requiredSuccesses (已达成 ${index / requestsPerEgg} 次)")
            }
        }
        return GameTaskReportResult(
            requestedRewards = eggCount,
            requiredSuccesses = requiredSuccesses,
            attemptedReports = attemptedReports,
            successfulReports = successfulReports,
            failureMessage = failureMessage,
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
                    message = "上报失败：进度=$current/$total HTTP=$responseCode response=$responseText",
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
