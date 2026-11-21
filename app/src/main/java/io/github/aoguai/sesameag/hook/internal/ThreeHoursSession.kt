package io.github.aoguai.sesameag.hook.internal

import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.HookUtil
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.UserDataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 人人3小时官方会话；任务始终由主进程调度。 */
internal object ThreeHoursSession {
    const val APP_ID = "2019052265312523"
    private const val KEY = "threeHoursSession"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val headerNames = setOf("csr-token", "csr-uuid", "csr-account-v2", "csr-front-v", "csr-scene-code",
        "csr-channel", "csr-visitor-id", "x-mass-tappid", "user-agent", "origin", "referer")
    private val cookieNames = setOf("csr-context", "_3th_cie")
    private val hosts = setOf("3hours.taobao.com", "m.3hours.taobao.com", "ef.3hours.taobao.com")

    class Credential(val identity: AccountSessionIdentity, val data: JSONObject) {
        val token: String get() = data.getJSONObject("headers").getString("csr-token")
    }

    fun observe(loader: ClassLoader, rawUrl: String, source: Any?) {
        val url = rawUrl.toHttpUrlOrNull() ?: return
        if (url.scheme != "https" || url.host !in hosts || RuntimeIdentityGuard.trustedIdentity() == null) return
        val identity = AccountSessionCoordinator.currentOrPersistedSessionIdentity() ?: return
        if (HookUtil.getUserId(loader) != identity.userId || !enabled(identity.userId)) return
        val entries = when (source) {
            is JSONObject -> listOf(source)
            is JSONArray -> (0 until source.length()).map { index ->
                source.optJSONObject(index) ?: run {
                    Log.error("ThreeHours", "公益请求头数组成员不是对象 path=${url.encodedPath}")
                    return
                }
            }
            else -> {
                Log.error("ThreeHours", "公益请求头结构不符合合同 path=${url.encodedPath}")
                return
            }
        }
        val headers = JSONObject()
        var cookieHeader = ""
        entries.forEach { entry ->
            entry.keys().forEach { key ->
                val name = key.lowercase(java.util.Locale.ROOT)
                if (name in headerNames && !entry.isNull(key)) headers.put(name, entry.get(key).toString())
                if (name == "cookie") cookieHeader = entry.optString(key)
            }
        }
        Log.runtime("ThreeHours", "已观察公益请求 path=${url.encodedPath} 凭据=${headers.optString("csr-token").isNotBlank()}")
        if (headers.optString("csr-token").isBlank()) return
        val observedAt = System.currentTimeMillis()
        scope.launch {
            runCatching {
                StoreAccess.transaction {
                    if (AccountSessionCoordinator.currentOrPersistedSessionIdentity() != identity ||
                        HookUtil.getUserId(loader) != identity.userId || !enabled(identity.userId)) return@transaction
                    val store = UserDataStoreManager.getInstance(identity.userId) ?: return@transaction
                    val old = store.get(KEY, String::class.java)?.let(::JSONObject) ?: JSONObject()
                    if (old.optLong("observedAt") > observedAt) return@transaction
                    val sameToken = old.optJSONObject("headers")?.optString("csr-token") == headers.optString("csr-token")
                    val data = if (sameToken) JSONObject(old.toString()) else JSONObject()
                    val merged = data.optJSONObject("headers") ?: JSONObject()
                    headers.keys().forEach { merged.put(it, headers.get(it)) }
                    data.put("headers", merged)
                    if ("p_csrf" in url.queryParameterNames) data.put("csrf", url.queryParameter("p_csrf") ?: JSONObject.NULL)
                    val cookies = cookies(data, url).associateBy { it.name }.toMutableMap()
                    cookieHeader.split(';').forEach { pair ->
                        val name = pair.substringBefore('=').trim()
                        if (name in cookieNames && pair.contains('=')) {
                            val previous = cookies[name]
                            if (previous == null || previous.value != pair.substringAfter('=').trim()) {
                                Cookie.parse(url, "${pair.trim()}; Domain=3hours.taobao.com; Path=/; Secure")?.let { cookies[name] = it }
                            }
                        }
                    }
                    data.put("cookies", JSONArray(cookies.values.map { it.toString() }))
                    data.put("ownerUserId", identity.userId).put("sessionEpoch", identity.sessionEpoch)
                    val invalidAt = old.optLong("invalidAt", old.optLong("observedAt"))
                    val retryValidation = old.optBoolean("invalid") && old.optLong("observedAt") <= invalidAt && observedAt > invalidAt
                    if (data.toString() == old.toString() && !retryValidation) return@transaction
                    if (sameToken && old.optBoolean("invalid")) data.put("invalidAt", invalidAt)
                    data.put("observedAt", observedAt)
                    store.put(KEY, data.toString())
                    Log.record("ThreeHours", "公益凭据已保存，等待只读验证${if (retryValidation) "（重新访问页面）" else ""}")
                }
            }.onFailure { error ->
                var detail = android.util.Log.getStackTraceString(error)
                val sensitiveValues = buildList {
                    headers.keys().forEach { add(headers.optString(it)) }
                    add(cookieHeader)
                    cookieHeader.split(';').forEach { add(it.substringAfter('=', "").trim()) }
                    url.queryParameterNames.forEach { addAll(url.queryParameterValues(it).filterNotNull()) }
                }
                sensitiveValues.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }.forEach {
                    detail = detail.replace(it, "<redacted>")
                }
                Log.error("ThreeHours", "公益凭据保存失败 path=${url.encodedPath}: $detail")
            }
        }
    }

    private fun enabled(userId: String): Boolean {
        val file = Files.getConfigV2File(userId)
        if (!file.exists()) return false
        val fields = JSONObject(Files.readText(file)).optJSONObject("modelFieldsMap")?.optJSONObject("AntSports") ?: return false
        return fields.optJSONObject("enable")?.optBoolean("value") == true &&
            listOf("threeHoursDonate", "threeHoursVote", "threeHoursWish", "threeHoursSteps").any {
                fields.optJSONObject(it)?.optBoolean("value") == true
            }
    }

    fun load(identity: AccountSessionIdentity): Credential? = AccountSessionCoordinator.withCurrentSession(identity) {
        val data = UserDataStoreManager.getInstance(identity.userId)?.get(KEY, String::class.java)?.let(::JSONObject)
            ?: return@withCurrentSession null
        if (data.optString("ownerUserId") != identity.userId ||
            data.optBoolean("invalid") && data.optLong("observedAt") <= data.optLong("invalidAt", data.optLong("observedAt")) ||
            data.optJSONObject("headers")?.optString("csr-token").isNullOrBlank()) return@withCurrentSession null
        Credential(identity, data)
    }

    fun ensureCurrent(credential: Credential) {
        TaskExecutionReport.current()?.ensureCurrent(credential.identity.userId)
        if (AccountSessionCoordinator.checkCurrentSession(credential.identity.userId, credential.identity.sessionEpoch)
            !is AccountSessionCheck.Current || Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("人人3小时账号会话已变化")
        }
    }

    fun cookies(data: JSONObject, url: HttpUrl): List<Cookie> {
        val array = data.optJSONArray("cookies") ?: return emptyList()
        return (0 until array.length()).mapNotNull { Cookie.parse(url, array.getString(it)) }
            .filter { it.name in cookieNames && it.expiresAt > System.currentTimeMillis() }
    }

    fun updateCookies(credential: Credential, url: HttpUrl, values: List<Cookie>) {
        ensureCurrent(credential)
        AccountSessionCoordinator.withCurrentSession(credential.identity) {
            val store = requireNotNull(UserDataStoreManager.getInstance(credential.identity.userId))
            val data = store.get(KEY, String::class.java)?.let(::JSONObject) ?: return@withCurrentSession
            if (data.getJSONObject("headers").optString("csr-token") != credential.token ||
                data.optLong("observedAt") != credential.data.optLong("observedAt")) return@withCurrentSession
            val saved = cookies(data, url).associateBy { it.name }.toMutableMap()
            values.filter { it.name in cookieNames && (it.domain == "3hours.taobao.com" || it.domain in hosts) }.forEach {
                if (it.expiresAt <= System.currentTimeMillis()) saved.remove(it.name) else saved[it.name] = it
            }
            val result = JSONArray(saved.values.map { it.toString() })
            credential.data.put("cookies", result)
            if (result.toString() != data.optJSONArray("cookies")?.toString()) {
                data.put("cookies", result)
                store.put(KEY, data.toString())
            }
        }
    }

    fun invalid(credential: Credential) {
        AccountSessionCoordinator.withCurrentSession(credential.identity) {
            val store = requireNotNull(UserDataStoreManager.getInstance(credential.identity.userId))
            val data = store.get(KEY, String::class.java)?.let(::JSONObject) ?: return@withCurrentSession
            if (data.getJSONObject("headers").optString("csr-token") == credential.token &&
                data.optLong("observedAt") == credential.data.optLong("observedAt")) {
                data.put("invalid", true).put("invalidAt", maxOf(System.currentTimeMillis(), data.optLong("observedAt")))
                store.put(KEY, data.toString())
            }
        }
    }

    fun prompt(identity: AccountSessionIdentity, donationParametersMissing: Boolean = false) {
        val (text, notify) = AccountSessionCoordinator.withCurrentSession(identity) {
            val store = requireNotNull(UserDataStoreManager.getInstance(identity.userId))
            val data = store.get(KEY, String::class.java)?.let(::JSONObject) ?: JSONObject()
            val reason = when {
                donationParametersMissing -> "捐赠参数尚未取得"
                data.optBoolean("invalid") -> "凭据已失效"
                else -> "凭据尚未取得"
            }
            val notify = data.optString("prompted") != reason
            if (notify) {
                data.put("prompted", reason)
                store.put(KEY, data.toString())
            }
            "人人3小时$reason，请用当前账号打开一次人人3小时或运动公益页面，待页面加载完成后返回。" to notify
        }
        Log.record("ThreeHours", text)
        if (notify) Notify.sendAlert("人人3小时等待页面访问", text)
    }

    fun verified(credential: Credential): Boolean = AccountSessionCoordinator.withCurrentSession(credential.identity) {
        val store = requireNotNull(UserDataStoreManager.getInstance(credential.identity.userId))
        val data = store.get(KEY, String::class.java)?.let(::JSONObject) ?: return@withCurrentSession false
        if (data.getJSONObject("headers").optString("csr-token") != credential.token ||
            data.optLong("observedAt") != credential.data.optLong("observedAt")) return@withCurrentSession false
        val clearPrompt = data.has("prompted") &&
            (data.optString("prompted") != "捐赠参数尚未取得" || data.has("csrf"))
        if (data.optBoolean("invalid") || clearPrompt || data.has("invalidAt")) {
            data.put("invalid", false)
            data.remove("invalidAt")
            if (clearPrompt) data.remove("prompted")
            store.put(KEY, data.toString())
        }
        true
    }
}
