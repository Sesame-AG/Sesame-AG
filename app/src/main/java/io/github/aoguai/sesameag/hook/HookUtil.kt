package io.github.aoguai.sesameag.hook

import android.content.Context
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object HookUtil {
    private const val TAG = "HookUtil"

    val rpcHookMap = ConcurrentHashMap<Any, Array<Any?>>()

    private var lastToastTime = 0L

    private var microContextCache: Any? = null

    data class FriendRefreshResult(
        val success: Boolean,
        val userId: String = "",
        val message: String = "",
        val profiles: Int = 0,
        val groups: Int = 0
    )

    /**
     * Hook RpcBridgeExtension.rpc 方法，记录请求信息
     */
    fun hookRpcBridgeExtension(classLoader: ClassLoader, isdebug: Boolean, debugUrl: String) {
        try {
            val jsonClassName = General.JSON_OBJECT_NAME
            val jsonClass = Class.forName(jsonClassName, false, classLoader)
            val rpcBridgeExtensionClass = loadClass(classLoader, "com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension")
            val rpcMethod = findMethod(
                rpcBridgeExtensionClass,
                "rpc",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                jsonClass,
                String::class.java,
                jsonClass,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                loadClass(classLoader, "com.alibaba.ariver.app.api.App"),
                loadClass(classLoader, "com.alibaba.ariver.app.api.Page"),
                loadClass(classLoader, "com.alibaba.ariver.engine.api.bridge.model.ApiContext"),
                loadClass(classLoader, "com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback")
            )

            ApplicationHook.requireXposedInterface().hook(rpcMethod).intercept { chain ->
                val args = chain.args
                if (args.size > 15) {
                    val methodName = args[0] as? String
                    val rawParams = args[4]
                    if (methodName != null && rawParams != null) {
                        val jsonObject = JSONObject(rawParams.toString())
                        TokenHooker.handleRpc(methodName, jsonObject)
                    }

                    val callback = args[15]
                    if (callback != null) {
                        val recordArray = arrayOfNulls<Any>(4).apply {
                            this[0] = System.currentTimeMillis()
                            this[1] = args[0] ?: "null"
                            this[2] = args[4] ?: "null"
                        }
                        rpcHookMap[callback] = recordArray
                    }
                }

                val result = chain.proceed()

                if (args.size > 15) {
                    val callback = args[15]
                    if (callback != null) {
                        val recordArray = rpcHookMap.remove(callback)
                        recordArray?.let {
                            try {
                                val time = it[0]
                                val method = it.getOrNull(1)
                                val params = it.getOrNull(2)
                                val data = it.getOrNull(3)
                                if (data != null) {
                                    val res = JSONObject().apply {
                                        put("TimeStamp", time)
                                        put("Method", method)
                                        put("Params", params)
                                        put("Data", data)
                                    }

                                    val prettyRecord = """
{
"TimeStamp": $time,
"Method": "$method",
"Params": $params,
"Data": $data
}
""".trimIndent()

                                    if (isdebug) {
                                        HookSender.sendHookData(res, debugUrl)
                                    }
                                    Log.capture(prettyRecord)
                                }
                            } catch (e: Exception) {
                                Log.runtime(TAG, "JSON 构建失败: ${e.message}")
                            }
                        }
                    }
                }
                result
            }
            Log.runtime(TAG, "Hook RpcBridgeExtension#rpc 成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Hook RpcBridgeExtension#rpc 失败", t)
        }
    }

    fun hookOtherService(classLoader: ClassLoader) {
        try {
            val fgBgMonitorClass = loadClass(classLoader, "com.alipay.mobile.common.fgbg.FgBgMonitorImpl")
            hookReturnConstant(findMethod(fgBgMonitorClass, "isInBackground"), false)
            hookReturnConstant(findMethod(fgBgMonitorClass, "isInBackground", Boolean::class.javaPrimitiveType!!), false)
            hookReturnConstant(findMethod(fgBgMonitorClass, "isInBackgroundV2"), false)

            val miscUtilsClass = loadClass(classLoader, "com.alipay.mobile.common.transport.utils.MiscUtils")
            hookReturnConstant(findMethod(miscUtilsClass, "isAtFrontDesk", Context::class.java), true)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "hookOtherService 失败", e)
        }
    }

    /**
     * Hook DefaultBridgeCallback.sendJSONResponse 方法，记录响应内容
     */
    fun hookDefaultBridgeCallback(classLoader: ClassLoader) {
        try {
            val jsonClass = Class.forName(General.JSON_OBJECT_NAME, false, classLoader)
            val callbackClass = loadClass(classLoader, "com.alibaba.ariver.engine.common.bridge.internal.DefaultBridgeCallback")
            val sendJsonResponseMethod = findMethod(callbackClass, "sendJSONResponse", jsonClass)
            ApplicationHook.requireXposedInterface().hook(sendJsonResponseMethod).intercept { chain ->
                val callback = chain.getThisObject()
                if (callback != null) {
                    val recordArray = rpcHookMap[callback]
                    if (recordArray != null && chain.args.isNotEmpty()) {
                        recordArray[3] = chain.args[0].toString()
                    }
                }
                chain.proceed()
            }
            Log.runtime(TAG, "Hook DefaultBridgeCallback#sendJSONResponse 成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Hook DefaultBridgeCallback#sendJSONResponse 失败", t)
        }
    }

    /**
     * 突破目标应用最大可登录账号数量限制
     * @param classLoader 类加载器
     */
    fun bypassAccountLimit(classLoader: ClassLoader) {
        Log.runtime(TAG, "Hook AccountManagerListAdapter#getCount")
        try {
            val adapterClass = loadClass(classLoader, "com.alipay.mobile.security.accountmanager.data.AccountManagerListAdapter")
            val getCountMethod = findMethod(adapterClass, "getCount")
            ApplicationHook.requireXposedInterface().hook(getCountMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val list = chain.getThisObject()?.let { getFieldValue(it, "queryAccountList") as? List<*> }
                    if (list != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastToastTime > 1000 * 60) {
                            Toast.show("🎉 已尝试为你突破限制")
                            lastToastTime = now
                        }
                        list.size
                    } else {
                        result
                    }
                } catch (e: Throwable) {
                    Log.printStackTrace(TAG, e)
                    Log.error(TAG, "Hook AccountManagerListAdapter#getCount failed: ${e.message}")
                    result
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Hook AccountManagerListAdapter#getCount 失败", t)
        }
        Log.runtime(TAG, "Hook AccountManagerListAdapter#getCount END")
    }

    fun getMicroApplicationContext(classLoader: ClassLoader): Any? {
        if (microContextCache != null) return microContextCache
        return runCatching {
            val appClass = loadClass(classLoader, "com.alipay.mobile.framework.AlipayApplication")
            val appInstance = callStaticMethod(appClass, "getInstance")
                ?: error("AlipayApplication#getInstance 返回 null")
            callMethod(appInstance, "getMicroApplicationContext")
                .also { microContextCache = it }
        }.onFailure {
            Log.printStackTrace(TAG, it)
        }.getOrNull()
    }

    fun getServiceObject(classLoader: ClassLoader, serviceName: String): Any? = runCatching {
        val microContext = getMicroApplicationContext(classLoader)
            ?: error("MicroApplicationContext 不可用")
        callMethod(microContext, "findServiceByInterface", serviceName)
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun getUserObject(classLoader: ClassLoader): Any? = runCatching {
        val serviceClassName = "com.alipay.mobile.personalbase.service.SocialSdkContactService"
        val serviceClass = loadClass(classLoader, serviceClassName)
        val serviceObject = getServiceObject(classLoader, serviceClass.name)
            ?: error("SocialSdkContactService 不可用")
        callMethod(serviceObject, "getMyAccountInfoModelByLocal")
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun getUserId(classLoader: ClassLoader): String? = runCatching {
        val userObject = getUserObject(classLoader) ?: error("用户对象为空")
        getFieldValue(userObject, "userId") as? String
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun captureCurrentUserEntity(classLoader: ClassLoader?): UserEntity? {
        val activeClassLoader = classLoader ?: return null
        return runCatching {
            val userObject = getUserObject(activeClassLoader) ?: error("用户对象为空")
            val userId = getFieldValue(userObject, "userId") as? String
            if (userId.isNullOrBlank()) {
                return null
            }
            UserEntity(
                userId = userId,
                account = runCatching { getFieldValue(userObject, "account") as? String }.getOrNull(),
                friendStatus = runCatching { getFieldValue(userObject, "friendStatus") as? Int }.getOrNull(),
                realName = runCatching { getFieldValue(userObject, "name") as? String }.getOrNull(),
                nickName = runCatching {
                    (getFieldValue(userObject, "nickName") as? String)
                        ?: (getFieldValue(userObject, "displayName") as? String)
                }.getOrNull(),
                remarkName = runCatching { getFieldValue(userObject, "remarkName") as? String }.getOrNull()
            )
        }.onFailure {
            Log.printStackTrace(TAG, "captureCurrentUserEntity 失败", it)
        }.getOrNull()
    }

    fun hookUser(classLoader: ClassLoader): FriendRefreshResult {
        var targetUserId = UserMap.currentUid.orEmpty()
        return try {
            val selfId = getUserId(classLoader)
            if (selfId.isNullOrBlank()) {
                val message = "hookUser 跳过：未获取到当前账号 userId"
                Log.runtime(TAG, message)
                return FriendRefreshResult(success = false, userId = targetUserId, message = message)
            }
            targetUserId = selfId
            val sameAccount = UserMap.currentUid == selfId
            val previousUsers = if (sameAccount) {
                UserMap.getUserMap().toMap()
            } else {
                emptyMap()
            }
            UserMap.setCurrentUserId(selfId)
            UserMap.saveMinimalSelf(selfId)
            val clsUserIndependentCache = loadClass(classLoader, "com.alipay.mobile.socialcommonsdk.bizdata.UserIndependentCache")
            val clsAliAccountDaoOp = loadClass(classLoader, "com.alipay.mobile.socialcommonsdk.bizdata.contact.data.AliAccountDaoOp")
            val aliAccountDaoOp = callStaticMethod(clsUserIndependentCache, "getCacheObj", clsAliAccountDaoOp)
                ?: error("AliAccountDaoOp 缓存对象为空")
            val allFriends = callMethod(aliAccountDaoOp, "getAllFriends") as? List<*> ?: emptyList<Any>()
            if (allFriends.isEmpty()) {
                if (!sameAccount || UserMap.getUserMap().isEmpty()) {
                    UserMap.load(selfId)
                }
                val cachedCenter = FriendRepository.current(selfId)
                val center = if (cachedCenter.profiles.isEmpty() && UserMap.getUserMap().isNotEmpty()) {
                    FriendRepository.mergeFromUserMap(selfId, allowPruneMissing = false)
                } else {
                    cachedCenter
                }
                val message = "支付宝本地好友缓存为空，已保留旧好友中心"
                Log.runtime(TAG, message)
                return FriendRefreshResult(
                    success = false,
                    userId = selfId,
                    message = message,
                    profiles = center.profiles.size,
                    groups = center.groups.size
                )
            }
            UserMap.unload()
            val friendClass = allFriends.firstOrNull()?.javaClass
                ?: return FriendRefreshResult(false, selfId, "支付宝本地好友缓存为空，已保留旧好友中心")
            val userIdField = findField(friendClass, "userId")
            val accountField = findField(friendClass, "account")
            val nameField = findField(friendClass, "name")
            val nickNameField = findField(friendClass, "nickName")
            val remarkNameField = findField(friendClass, "remarkName")
            val friendStatusField = findField(friendClass, "friendStatus")
            var selfEntity: UserEntity? = null
            allFriends.forEach { userObject ->
                runCatching {
                    val userId = userIdField.get(userObject) as? String
                    val account = accountField.get(userObject) as? String
                    val name = nameField.get(userObject) as? String
                    val nickName = nickNameField.get(userObject) as? String
                    val remarkName = remarkNameField.get(userObject) as? String
                    val friendStatus = friendStatusField.get(userObject) as? Int
                    val userEntity = UserEntity(userId, account, friendStatus, name, nickName, remarkName)
                    if (userId == selfId) selfEntity = userEntity
                    UserMap.add(userEntity)
                }.onFailure {
                    Log.runtime(TAG, "addUserObject err:")
                    Log.printStackTrace(it)
                }
            }
            if (UserMap.getUserMap().isEmpty()) {
                val center = FriendRepository.current(selfId)
                val message = "支付宝本地好友缓存解析为空，已保留旧好友中心"
                Log.runtime(TAG, message)
                return FriendRefreshResult(
                    success = false,
                    userId = selfId,
                    message = message,
                    profiles = center.profiles.size,
                    groups = center.groups.size
                )
            }

            selfEntity?.let { UserMap.saveSelf(it) }
            val userMapSaved = UserMap.save(selfId)
            val center = FriendRepository.mergeFromUserMap(selfId, previousUsers, allowPruneMissing = true)
            val message = if (userMapSaved) {
                "好友刷新完成: profiles=${center.profiles.size}, groups=${center.groups.size}"
            } else {
                "好友刷新完成，但 friend.json 保存失败"
            }
            Log.runtime(TAG, "userCache load success ! $message")
            FriendRefreshResult(
                success = userMapSaved,
                userId = selfId,
                message = message,
                profiles = center.profiles.size,
                groups = center.groups.size
            )
        } catch (t: Throwable) {
            val message = "hookUser 失败: ${t.message ?: t.javaClass.simpleName}"
            Log.printStackTrace(TAG, "hookUser 失败", t)
            FriendRefreshResult(success = false, userId = targetUserId, message = message)
        }
    }

    private fun hookReturnConstant(method: Method, value: Any?) {
        ApplicationHook.requireXposedInterface().hook(method).intercept { _ -> value }
    }

    private fun loadClass(classLoader: ClassLoader, className: String): Class<*> {
        return Class.forName(className, false, classLoader)
    }

    private fun findMethod(targetClass: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        var current: Class<*>? = targetClass
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name, *parameterTypes).apply {
                    isAccessible = true
                }
            }
            current = current.superclass
        }
        return targetClass.getMethod(name, *parameterTypes).apply {
            isAccessible = true
        }
    }

    private fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        return findCompatibleMethod(target.javaClass, name, *args).invoke(target, *args)
    }

    private fun callStaticMethod(targetClass: Class<*>, name: String, vararg args: Any?): Any? {
        return findCompatibleMethod(targetClass, name, *args).invoke(null, *args)
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

    private fun findField(targetClass: Class<*>, name: String): Field {
        var current: Class<*>? = targetClass
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply {
                    isAccessible = true
                }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${targetClass.name}#$name")
    }

    private fun getFieldValue(target: Any, name: String): Any? {
        return findField(target.javaClass, name).get(target)
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

