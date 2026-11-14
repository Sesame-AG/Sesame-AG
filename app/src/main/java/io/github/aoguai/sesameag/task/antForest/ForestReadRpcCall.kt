package io.github.aoguai.sesameag.task.antForest

import android.net.Uri
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import kotlinx.coroutines.CancellationException
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpRetryException
import java.util.concurrent.TimeUnit

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
            .put("chapter_id", chapterId.toLongOrNull() ?: chapterId).put("chapter_name", chapterName), shortTimeout = true)
    }

    private fun request(path: String, body: JSONObject? = null, shortTimeout: Boolean = false): JSONObject {
        if (!RpcDailyCircuit.isCurrent(identity) || ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) {
            throw CancellationException("森林阅读账号会话不可用")
        }
        val text = body?.toString()
        val builder = Request.Builder().url("https://m.zhangwenwh.com/api$path")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("alipayminimark", miniMark)
            .header("Referer", "https://$appId.hybrid.alipay-eco.com/$appId/$version/index.html")
            .header("x-release-type", "ONLINE")
        System.getProperty("http.agent")?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (text != null) builder.post(text.toRequestBody(JSON))
        val request = builder.build()
        var captureId: String? = null
        try {
            captureId = RpcTrafficCapture.recordModuleHttpRequest(
                request.url.toString(), request.method, request.headers.toMultimap(), text.orEmpty())
            val client = (if (shortTimeout) SHORT_CLIENT else CLIENT).newBuilder()
                // 只读查询可自动恢复连接失败；POST 结果未知时仍由持久状态回查。
                .retryOnConnectionFailure(request.method == "GET")
                .build()
            return client.newCall(request).execute().use { response ->
                val httpCode = response.code
                val raw = response.body.string()
                RpcTrafficCapture.recordModuleHttpResponse(captureId, request.url.toString(), request.method,
                    response.headers.toMultimap(), httpCode, raw)
                captureId = null
                if (!RpcDailyCircuit.isCurrent(identity) || ApplicationHookConstants.isOffline()) {
                    throw CancellationException("森林阅读请求期间账号会话已变化")
                }
                if (httpCode == 401) throw HttpRetryException("森林阅读登录态失效", httpCode)
                check(httpCode in 200..299) { "森林阅读 HTTP=$httpCode path=$path raw=$raw" }
                val result = JSONObject(raw)
                check(result.has("code")) { "森林阅读响应缺少业务状态 path=$path raw=$raw" }
                val code = result.getInt("code")
                if (code == 401) throw HttpRetryException("森林阅读登录态失效 path=$path raw=$raw", code)
                if (code != 2000) throw RejectedResponse("森林阅读请求失败 path=$path raw=$raw")
                result
            }
        } catch (t: Throwable) {
            RpcTrafficCapture.recordModuleHttpError(captureId, t)
            throw t
        }
    }

    private companion object {
        val JSON = "application/json;charset=UTF-8".toMediaType()
        val CLIENT = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            // 上报结果未知时由持久状态回查，传输层不能自动重放请求。
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val SHORT_CLIENT = CLIENT.newBuilder()
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
