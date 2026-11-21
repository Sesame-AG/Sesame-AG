package io.github.aoguai.sesameag.task.antSports

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.internal.ThreeHoursSession
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import kotlinx.coroutines.CancellationException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class ThreeHoursRpcCall(private val credential: ThreeHoursSession.Credential) {
    open class Rejected(val endpoint: String, val code: String, val response: String) :
        IOException("$endpoint code=$code raw=$response")
    class Expired(endpoint: String, response: String) : Rejected(endpoint, "401", response)

    private val client = CLIENT.newBuilder().cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = ThreeHoursSession.cookies(credential.data, url).filter { it.matches(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = ThreeHoursSession.updateCookies(credential, url, cookies)
    }).build()

    fun account(): JSONObject = request("m", "/public_welfare_account/info").getJSONObject("data")

    fun tasks(): JSONObject = request("m", "/public_welfare_account/query_opportunity",
        query = mapOf("unSupportDonateStep" to "false")).getJSONObject("data")

    fun projects(): JSONArray = request("", "/udc/donate/labelList",
        query = mapOf("openBizCode" to "alipay_health_donate")).getJSONArray("data")

    fun voteToday(): JSONObject = request("m", "/content/vote/today").getJSONObject("data")

    fun donate(projectId: Long, copies: Long): JSONObject {
        check(credential.data.has("csrf")) { "健康能量捐赠参数尚未取得，请访问官方公益页面" }
        return request("ef", "/donate/project/donate",
            JSONObject().put("resourceCopies", copies).put("openBizCode", "alipay_health_donate")
                .put("projectId", projectId).put("features", JSONObject().put("sourceSceneCode", CHANNEL).toString()))
    }

    fun vote(voteId: Long, option: Int): JSONObject = request("m", "/content/vote/create",
        JSONObject().put("voteId", voteId).put("userOption", option))

    fun like(wishId: String): JSONObject = request("m", "/apittznl/v2/wish/like", JSONObject().put("wishId", wishId))

    fun steps(): JSONObject {
        val code = AuthCodeHelper.getAuthCode(ThreeHoursSession.APP_ID)
        check(!code.isNullOrBlank()) { "人人3小时捐步授权码未取得" }
        return request("m", "/donateStep/v2/getTodayRemainStep",
            query = mapOf("authCode" to code, "timeZone" to "Asia/Shanghai")).getJSONObject("data")
    }

    fun donateSteps(todaySteps: Long): JSONObject = request("m", "/donateStep/v2/donate",
        JSONObject().put("todayExerciseSteps", todaySteps).put("donateSource", "web"))

    private fun request(subdomain: String, path: String, body: JSONObject? = null,
        query: Map<String, String> = emptyMap()): JSONObject {
        ThreeHoursSession.ensureCurrent(credential)
        if (ApplicationHookConstants.isOffline()) throw CancellationException("支付宝已离线")
        val host = if (subdomain.isEmpty()) "3hours.taobao.com" else "$subdomain.3hours.taobao.com"
        val url = "https://$host$path".toHttpUrl().newBuilder().addQueryParameter("threehours-from-channel", CHANNEL)
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        if (body != null && credential.data.has("csrf")) {
            url.addQueryParameter("p_csrf", if (credential.data.isNull("csrf")) "null" else credential.data.getString("csrf"))
        }
        val builder = Request.Builder().url(url.build())
        val headers = credential.data.getJSONObject("headers")
        headers.keys().forEach { builder.header(it, headers.getString(it)) }
        builder.header("Content-Type", "application/json;charset=UTF-8")
        val content = body?.toString()
        if (content != null) builder.post(content.toRequestBody(JSON))
        val request = builder.build()
        var captureId: String? = null
        try {
            captureId = RpcTrafficCapture.recordModuleHttpRequest(request.url.toString(), request.method,
                request.headers.toMultimap(), content.orEmpty())
            return client.newCall(request).execute().use { response ->
                val raw = response.body.string()
                RpcTrafficCapture.recordModuleHttpResponse(captureId, request.url.toString(), request.method,
                    response.headers.toMultimap(), response.code, raw)
                captureId = null
                ThreeHoursSession.ensureCurrent(credential)
                val result = runCatching { JSONObject(raw) }.getOrNull()
                val location = response.header("Location")?.let { request.url.resolve(it) }
                    ?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString()
                val safe = safeResponse(JSONObject().put("httpStatus", response.code)
                    .put("contentType", response.header("Content-Type").orEmpty())
                    .put("location", location ?: if (response.header("Location") == null) "" else "<unparseable>")
                    .put("body", result ?: JSONObject().put("format", "non-json").put("length", raw.length)))
                if (response.code == 401 || result?.optString("code") == "401") {
                    ThreeHoursSession.invalid(credential)
                    throw Expired(path, safe)
                }
                if (response.code in 300..499 || result?.opt("success") == false) {
                    throw Rejected(path, if (response.code in 300..499) "HTTP_${response.code}" else result!!.optString("code"), safe)
                }
                check(response.code in 200..299 && result != null) {
                    "人人3小时响应异常 path=$path HTTP=${response.code} response=$safe"
                }
                check(result.optBoolean("success") && result.optInt("code") == 200) {
                    "人人3小时响应缺少成功合同 path=$path raw=$safe"
                }
                result
            }
        } catch (t: Throwable) {
            RpcTrafficCapture.recordModuleHttpError(captureId, t)
            ThreeHoursSession.ensureCurrent(credential)
            if (t is CancellationException || t is InterruptedException || t is Rejected) throw t
            var detail = t.message.orEmpty().replace(credential.token, "<redacted>")
            listOf("authCode", "p_csrf").forEach { name ->
                request.url.queryParameter(name)?.takeIf { it.isNotEmpty() }?.let { detail = detail.replace(it, "<redacted>") }
            }
            throw IOException("$path ${t.javaClass.simpleName}: $detail", t)
        }
    }

    fun safeResponse(result: JSONObject): String {
        fun clean(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { key ->
                    out.put(key, if (key in setOf("token", "csr-token", "csr-uuid", "authCode", "p_csrf", "cookie", "Cookie"))
                        "<redacted>" else clean(value.get(key)))
                }
            }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(clean(value.get(i))) }
            is String -> value.replace(credential.token, "<redacted>")
            else -> value
        }
        return clean(result).toString()
    }

    private companion object {
        const val CHANNEL = "zfb_sports_energy_donation"
        val JSON = "application/json;charset=UTF-8".toMediaType()
        val CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}
