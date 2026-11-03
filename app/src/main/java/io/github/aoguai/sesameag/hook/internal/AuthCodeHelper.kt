package io.github.aoguai.sesameag.hook.internal

import io.github.aoguai.sesameag.util.Log
import java.lang.reflect.Method
import java.util.HashMap

/**
 * OAuth2 授权码服务助手类
 * 用于调用目标应用的 OpenAuthExtension.getAuthCode 方法
 */
object AuthCodeHelper {

    private const val TAG = "Oauth2AuthCodeHelper"
    private var classLoader: ClassLoader? = null

    private fun isServiceNotReadyError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("getAuthPreDecision") && message.contains("null object reference")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * 初始化 Oauth2AuthCodeHelper
     * @param loader 应用类加载器
     */
    fun init(loader: ClassLoader) {
        classLoader = loader
        Log.record(TAG, "Oauth2AuthCodeHelper 初始化完成")
    }


    /**
     * 主动调用获取授权码
     * 通过反射调用 Oauth2AuthCodeService.getAuthSkipResult 方法获取授权码
     *
     * @param appId 应用ID
     * @return code，失败返回null
     */
    fun getAuthCode(
        appId: String
    ): String? {
        try {
            val loader = classLoader
            if (loader == null) {
                Log.error(TAG, "Oauth2AuthCodeHelper 未初始化，请先调用 init 方法")
                return null
            }
            val oauth2AuthCodeServiceImplClass = Class.forName(
                "com.alibaba.ariver.rpc.biz.proxy.Oauth2AuthCodeServiceImpl",
                false,
                loader
            )
            val oauth2AuthCodeServiceImpl = oauth2AuthCodeServiceImplClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            val authSkipRequestModelClass = Class.forName(
                "com.alibaba.ariver.permission.openauth.model.request.AuthSkipRequestModel",
                false,
                loader
            )
            val authSkipRequestModel = authSkipRequestModelClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            callMethod(authSkipRequestModel, "setAppId", appId)
            callMethod(authSkipRequestModel, "setCurrentPageUrl", "https://${appId}.hybrid.alipay-eco.com/index.html")
            callMethod(authSkipRequestModel, "setFromSystem", "mobilegw_android")
            callMethod(authSkipRequestModel, "setScopeNicks", listOf("auth_base"))
            callMethod(authSkipRequestModel, "setState", "QnJpbmcgc21hbGwgYW5kIGJlYXV0aWZ1bCBjaGFuZ2VzIHRvIHRoZSB3b3JsZA==")
            callMethod(authSkipRequestModel, "setIsvAppId", "")
            callMethod(authSkipRequestModel, "setExtInfo", HashMap<String, String>())
            val appExtInfo = HashMap<String, String>()
            appExtInfo["channel"] = "tinyapp"
            appExtInfo["clientAppId"] = appId
            callMethod(authSkipRequestModel, "setAppExtInfo", appExtInfo)
            val authSkipResult = callMethod(
                oauth2AuthCodeServiceImpl,
                "getAuthSkipResult",
                "AP",
                null,
                authSkipRequestModel
            )

            if (authSkipResult != null) {
                // 直接从返回的 AuthSkipResultModel 获取 authExecuteResult
                val authExecuteResult = callMethod(authSkipResult, "getAuthExecuteResult")
                if (authExecuteResult != null) {
                    return callMethod(authExecuteResult, "getAuthCode") as? String
                }
            }

            return null
        } catch (e: Throwable) {
            if (isServiceNotReadyError(e)) {
                Log.record(TAG, "授权码服务尚未就绪，跳过本次获取[$appId]")
                return null
            }
            Log.printStackTrace(TAG, "主动调用获取授权码失败: ${e.message}", e)
            return null
        }
    }

    private fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, name, *args)
        return method.invoke(target, *args)
    }

    private fun findCompatibleMethod(targetClass: Class<*>, name: String, vararg args: Any?): Method {
        val candidates = linkedSetOf<Method>()
        candidates.addAll(targetClass.methods)
        var current: Class<*>? = targetClass
        while (current != null) {
            candidates.addAll(current.declaredMethods)
            current = current.superclass
        }
        return candidates.firstOrNull { method ->
            method.name == name &&
                method.parameterCount == args.size &&
                method.parameterTypes.indices.all { index ->
                    isArgumentCompatible(method.parameterTypes[index], args[index])
                }
        }?.apply {
            isAccessible = true
        } ?: throw NoSuchMethodException("${targetClass.name}#$name(${args.size})")
    }

    private fun isArgumentCompatible(parameterType: Class<*>, argument: Any?): Boolean {
        if (argument == null) {
            return !parameterType.isPrimitive
        }
        return boxType(parameterType).isAssignableFrom(boxType(argument.javaClass))
    }

    private fun boxType(type: Class<*>): Class<*> {
        return when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> type
        }
    }
}

