package io.github.aoguai.sesameag.hook.internal

import android.os.SystemClock
import android.util.Pair
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/** 使用宿主 MTOP 服务执行已有第三方完成协议；不经普通 RPC 通道，也不自动重放写请求。 */
object MtopTaskCall {
    fun call(api: String, data: JSONObject, referer: String, onInvoked: () -> Unit): String {
        val session = requireNotNull(AccountSessionCoordinator.currentSession()) { "当前没有账号会话" }
        check(!ApplicationHookConstants.isOffline()) { "当前已离线" }
        val loader = requireNotNull(ApplicationHook.classLoader) { "宿主 ClassLoader 不可用" }
        val started = SystemClock.elapsedRealtime()
        val payload = JSONObject().put("data", data).put("headers", JSONObject().put("referer", referer)).toString()
        val requestId = RpcTrafficCapture.recordModuleRequest(api, payload)
        try {
            val agentClass = Class.forName("com.alipay.mobile.framework.LauncherApplicationAgent", false, loader)
            val agent = requireNotNull(agentClass.getMethod("getInstance").invoke(null))
            val context = requireNotNull(agent.javaClass.getMethod("getMicroApplicationContext").invoke(agent))
            val service = requireNotNull(context.javaClass.getMethod("findServiceByInterface", String::class.java)
                .invoke(context, "com.alipay.android.phone.mobilesdk.mtop.MtopService")) { "宿主 MtopService 不可用" }
            val jsonClass = Class.forName("com.alibaba.fastjson.JSONObject", false, loader)
            val request = jsonClass.getDeclaredConstructor().newInstance()
            val put = jsonClass.getMethod("put", String::class.java, Any::class.java)
            val methodClass = Class.forName("mtopsdk.mtop.domain.MethodEnum", false, loader)
            val headers = jsonClass.getDeclaredConstructor().newInstance()
            put.invoke(headers, "referer", referer)
            val parameters = linkedMapOf<String, Any>(
                "apiName" to api,
                "apiVersion" to "1.0",
                "dataText" to data.toString(),
                "usePost" to requireNotNull(methodClass.getField("GET").get(null)),
                "type" to "originaljson",
                "timeout" to 15_000,
                "needHttps" to true,
                "needSpdy" to true,
                "needWua" to false,
                "needEcodeSign" to false,
                "shouldSkipAutoLogin" to false,
                "ignore_aliauth_autologin" to false,
                "isvOpenAppkey" to "DEFAULT_AUTH",
                "isvAccessToken" to "",
                "customHost" to "",
                "ttid" to "",
                "tb_eagleeyex_scm_project" to "",
                "headers" to headers,
            )
            parameters.forEach { (key, value) -> put.invoke(request, key, value) }
            val methods = service.javaClass.methods.filter {
                it.name == "syncRequestFastJson" && !it.isBridge && !it.isSynthetic
            }
            val compatible = methods.filter {
                it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(request)
            }
            val method = compatible.singleOrNull { candidate ->
                compatible.none { other ->
                    other != candidate && candidate.parameterTypes[0] != other.parameterTypes[0] &&
                        candidate.parameterTypes[0].isAssignableFrom(other.parameterTypes[0])
                }
            } ?: throw NoSuchMethodException(
                "MTOP 未调用：无法唯一绑定 syncRequestFastJson(${request.javaClass.name})，宿主签名=" +
                    methods.joinToString { it.toGenericString() }
            )
            check(AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) &&
                !ApplicationHookConstants.isOffline()) { "账号会话已变化或已离线" }
            // 只有返回结果或宿主方法内部抛错，才进入写入结果待确认的范围。
            val invokedResult = try {
                method.invoke(service, request).also { onInvoked() }
            } catch (error: InvocationTargetException) {
                onInvoked()
                throw error.targetException ?: error
            }
            val result = requireNotNull(invokedResult) { "MTOP 返回为空，写入结果未知" }
            val raw = if (result is Pair<*, *>) requireNotNull(result.second).toString() else result.toString()
            RpcTrafficCapture.recordModuleResponse(api, raw, SystemClock.elapsedRealtime() - started, requestId, null)
            check(AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) &&
                !ApplicationHookConstants.isOffline()) { "账号会话已变化或已离线，写入结果待回查" }
            return raw
        } catch (error: Throwable) {
            RpcTrafficCapture.recordModuleError(api, error.toString(), SystemClock.elapsedRealtime() - started,
                "MTOP 调用异常，未重放", requestId, null)
            throw error
        }
    }
}
