package io.github.aoguai.sesameag.util

import android.app.Application
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.HookUtil
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.storage.StoreAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 按账号和 appId 保存游戏凭据，字段由厂商协议解释。 */
object GameSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Snapshot internal constructor(
        internal val identity: AccountSessionIdentity,
        internal val appId: String,
        internal val raw: String,
        val credentials: Map<String, String>,
    )

    @JvmStatic
    fun logHandoff(appId: String, stage: String, reason: String) {
        Log.runtime("GameSession", "游戏会话 stage=$stage process=${Application.getProcessName()} appId=$appId reason=$reason")
    }

    @JvmStatic
    fun save(loader: ClassLoader, appId: String, credentials: Map<String, String>) {
        if (appId.isBlank() || credentials.isEmpty() || credentials.any { it.key.isBlank() || it.value.isBlank() }) {
            logHandoff(appId, "保存", "会话资料无效")
            return
        }
        if (RuntimeIdentityGuard.trustedIdentity() == null) {
            logHandoff(appId, "保存", "宿主身份未验证")
            return
        }
        val identity = AccountSessionCoordinator.currentOrPersistedSessionIdentity()
        if (identity == null) {
            logHandoff(appId, "保存", "账号会话未就绪")
            return
        }
        if (HookUtil.getUserId(loader) != identity.userId) {
            logHandoff(appId, "保存", "宿主账号不匹配")
            return
        }
        save(identity, appId, credentials, loader)
    }

    @JvmStatic
    @JvmOverloads
    fun save(identity: AccountSessionIdentity, appId: String, credentials: Map<String, String>, loader: ClassLoader? = null) {
        if (appId.isBlank() || credentials.isEmpty() ||
            credentials.any { it.key.isBlank() || it.value.isBlank() } ||
            RuntimeIdentityGuard.trustedIdentity() == null) return
        val values = credentials.toMap()
        val observedAt = System.currentTimeMillis()
        scope.launch {
            runCatching {
                StoreAccess.transaction {
                    if (AccountSessionCoordinator.currentOrPersistedSessionIdentity() != identity ||
                        loader != null && HookUtil.getUserId(loader) != identity.userId) return@transaction "账号会话已变化"
                    val store = UserDataStoreManager.getInstance(identity.userId) ?: return@transaction "账号存储未就绪"
                    val key = "gameSession:$appId"
                    val old = store.get(key, String::class.java)?.let(::JSONObject)
                    val previous = old?.optJSONObject("credentials")
                    if (old != null && old.optLong("observedAt") > observedAt) return@transaction "已有更新资料"
                    if (old != null && !old.optBoolean("invalid") && previous != null && previous.length() == values.size &&
                        values.all { (name, value) -> previous.optString(name) == value }) return@transaction "资料已保存"
                    store.put(key, JSONObject().put("credentials", JSONObject(values))
                        .put("observedAt", observedAt).toString())
                    "已保存"
                }
            }.onSuccess { logHandoff(appId, "保存", it) }.onFailure {
                logHandoff(appId, "保存", "异常 ${it.javaClass.simpleName}")
            }
        }
    }

    @JvmStatic
    fun read(appId: String): Snapshot? {
        val session = AccountSessionCoordinator.currentSession()
        if (session == null) {
            logHandoff(appId, "读取", "账号会话未就绪")
            return null
        }
        val identity = AccountSessionIdentity(session.userId, session.sessionEpoch)
        val snapshot = AccountSessionCoordinator.withCurrentSession(identity) {
            val raw = UserDataStoreManager.getInstance(identity.userId)
                ?.get("gameSession:$appId", String::class.java) ?: return@withCurrentSession null
            val data = JSONObject(raw)
            if (data.optBoolean("invalid")) return@withCurrentSession null
            val credentials = data.optJSONObject("credentials") ?: return@withCurrentSession null
            Snapshot(identity, appId, raw, credentials.keys().asSequence().associateWith { credentials.optString(it) })
        }
        logHandoff(appId, "读取", if (snapshot != null) "已取得" else "当前账号无有效资料")
        return snapshot
    }

    @JvmStatic
    fun invalidate(session: Snapshot) {
        AccountSessionCoordinator.withCurrentSession(session.identity) {
            val store = UserDataStoreManager.getInstance(session.identity.userId) ?: return@withCurrentSession
            val key = "gameSession:${session.appId}"
            // 失败响应只能使本次请求使用的会话失效，不能覆盖后续保存的新凭据。
            if (store.get(key, String::class.java) == session.raw) {
                store.put(key, JSONObject(session.raw).put("invalid", true).toString())
            }
        }
    }
}
