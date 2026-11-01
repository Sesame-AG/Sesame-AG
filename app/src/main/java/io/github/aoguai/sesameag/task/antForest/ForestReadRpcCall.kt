package io.github.aoguai.sesameag.task.antForest

import android.net.Uri
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL

internal class ForestReadRpcCall(var token: String) {
    private val identity = RpcDailyCircuit.captureIdentity()
    private val appId = "2021003114652763"
    private val version = "0.2.2410251510.53"
    private val miniMark = AlipayMiniMarkHelper.getAlipayMiniMark(appId, version)

    internal class RejectedResponse(message: String) : IllegalStateException(message)

    fun login(): String {
        val code = AuthCodeHelper.getAuthCode(appId)
        require(!code.isNullOrBlank()) { "森林阅读授权码为空" }
        val data = request("/authorization", JSONObject().put("auth_code", code)
            .put("app_id", appId).put("cid", "7001")).getJSONObject("data")
        return data.getString("token").also { require(it.isNotBlank()) { "森林阅读登录缺少 token" } }
    }

    fun index(): JSONArray = request("/index/index?type=1").getJSONArray("data")

    fun chapter(bookId: String, chapterId: String): JSONObject {
        val path = "/book/chapter?book_id=${Uri.encode(bookId)}" +
            if (chapterId.isBlank()) "" else "&chapter_id=${Uri.encode(chapterId)}"
        return request(path).getJSONObject("data")
    }

    fun energy(): JSONObject = request("/user/forestPro").getJSONObject("data")

    fun report(bookId: String, chapterId: String, seconds: Long) {
        require(seconds > 0)
        request("/user/updateWel", JSONObject().put("book_id", bookId)
            .put("chapter_id", chapterId.toLongOrNull() ?: chapterId).put("time", seconds))
    }

    fun recent(bookId: String, chapterId: String, chapterName: String) {
        request("/book/set_read_recently", JSONObject().put("book_id", bookId)
            .put("chapter_id", chapterId.toLongOrNull() ?: chapterId).put("chapter_name", chapterName))
    }

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        if (!RpcDailyCircuit.isCurrent(identity) || ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) {
            throw CancellationException("森林阅读账号会话不可用")
        }
        val connection = URL("https://m.zhangwenwh.com/api$path").openConnection() as HttpURLConnection
        var captureId: String? = null
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            // 十秒连接、二十秒读取均为网络等待上限，防止阅读请求无限挂起；响应及时则不会等满。
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            // 使用运行设备的平台 UA。
            System.getProperty("http.agent")?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("User-Agent", it)
            }
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            connection.setRequestProperty("alipayminimark", miniMark)
            connection.setRequestProperty("Referer", "https://$appId.hybrid.alipay-eco.com/$appId/$version/index.html")
            connection.setRequestProperty("x-release-type", "ONLINE")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            val text = body?.toString()
            val bytes = text?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            captureId = RpcTrafficCapture.recordModuleHttpRequest(connection, text.orEmpty())
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            RpcTrafficCapture.recordModuleHttpResponse(captureId, connection, httpCode, raw)
            captureId = null
            if (!RpcDailyCircuit.isCurrent(identity) || ApplicationHookConstants.isOffline()) {
                throw CancellationException("森林阅读请求期间账号会话已变化")
            }
            if (httpCode == 401) throw HttpRetryException("森林阅读登录态失效", httpCode)
            check(httpCode in 200..299) { "森林阅读 HTTP=$httpCode path=$path raw=$raw" }
            val response = JSONObject(raw)
            check(response.has("code")) { "森林阅读响应缺少业务状态 path=$path raw=$raw" }
            val code = response.getInt("code")
            if (code == 401) throw HttpRetryException("森林阅读登录态失效 path=$path raw=$raw", code)
            if (code != 2000) throw RejectedResponse("森林阅读请求失败 path=$path raw=$raw")
            return response
        } catch (t: Throwable) {
            RpcTrafficCapture.recordModuleHttpError(captureId, t)
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
