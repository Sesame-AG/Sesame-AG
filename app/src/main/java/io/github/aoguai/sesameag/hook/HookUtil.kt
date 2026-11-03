package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import java.lang.reflect.Field
import java.lang.reflect.Method

object HookUtil {
    private const val TAG = "HookUtil"

    private var microContextCache: Any? = null

    data class FriendRefreshResult(
        val success: Boolean,
        val userId: String = "",
        val message: String = "",
        val profiles: Int = 0,
        val groups: Int = 0
    )

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

    fun getUserId(classLoader: ClassLoader): String? {
        val userObject = getUserObject(classLoader) ?: run {
            Log.runtime(TAG, "getUserId 跳过：用户对象为空")
            return null
        }
        return runCatching {
            getFieldValue(userObject, "userId") as? String
        }.onFailure {
            Log.printStackTrace(TAG, it)
        }.getOrNull()
    }

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

    fun hookUser(classLoader: ClassLoader, identity: AccountSessionIdentity): FriendRefreshResult {
        val selfId = identity.userId
        return try {
            check(AccountSessionCoordinator.isCurrentSession(selfId, identity.sessionEpoch) &&
                UserMap.currentUid == selfId && getUserId(classLoader) == selfId) { "账号会话已变化" }
            val cacheClass = loadClass(classLoader, "com.alipay.mobile.socialcommonsdk.bizdata.UserIndependentCache")
            val daoClass = loadClass(classLoader, "com.alipay.mobile.socialcommonsdk.bizdata.contact.data.AliAccountDaoOp")
            val dao = callStaticMethod(cacheClass, "getCacheObj", daoClass) ?: error("AliAccountDaoOp 缓存对象为空")
            val allFriends = callMethod(dao, "getAllFriends") as? List<*> ?: emptyList<Any>()
            check(allFriends.isNotEmpty()) { "本地好友缓存为空，已保留旧好友中心" }
            val friendClass = requireNotNull(allFriends.firstOrNull()).javaClass
            val userIdField = findField(friendClass, "userId")
            val accountField = findField(friendClass, "account")
            val nameField = findField(friendClass, "name")
            val nickNameField = findField(friendClass, "nickName")
            val remarkNameField = findField(friendClass, "remarkName")
            val friendStatusField = findField(friendClass, "friendStatus")
            val observed = linkedMapOf<String, UserEntity>()
            var parseFailures = 0
            allFriends.forEach { item ->
                try {
                    val id = userIdField.get(item) as? String
                    require(!id.isNullOrBlank()) { "好友账号为空" }
                    observed[id] = UserEntity(
                        id, accountField.get(item) as? String, friendStatusField.get(item) as? Int,
                        nameField.get(item) as? String, nickNameField.get(item) as? String,
                        remarkNameField.get(item) as? String
                    )
                } catch (e: Exception) {
                    parseFailures++
                    Log.printStackTrace(TAG, "好友解析失败", e)
                }
            }
            Log.runtime(TAG, "好友采集: user=$selfId epoch=${identity.sessionEpoch} input=${allFriends.size} parsed=${observed.size} failed=$parseFailures")
            check(parseFailures == 0) { "好友缓存解析不完整，已保留旧数据" }
            check(observed.keys.any { it != selfId }) { "好友缓存尚未就绪，已保留旧数据" }
            AccountSessionCoordinator.withCurrentSession(identity) {
                check(UserMap.currentUid == selfId && getUserId(classLoader) == selfId) { "提交前账号已变化" }
                val snapshot = UserMap.getUserMap(selfId) + observed
                val center = Files.withAccountTransaction(
                    selfId, listOf(requireNotNull(Files.getFriendIdMapFile(selfId)), Files.getFriendCenterFile(selfId))
                ) {
                    check(UserMap.save(selfId, snapshot)) { "friend.json 保存失败" }
                    val merged = FriendRepository.mergeFromUserMap(
                        selfId, currentUsers = observed, refreshRelations = true
                    )
                    check(getUserId(classLoader) == selfId) { "保存期间账号已变化" }
                    merged
                }
                UserMap.replace(selfId, snapshot)
                val message = "好友刷新完成: input=${observed.size}, profiles=${center.profiles.size}, groups=${center.groups.size}"
                Log.runtime(TAG, "$message user=$selfId epoch=${identity.sessionEpoch} saved=true")
                FriendRefreshResult(true, selfId, message, center.profiles.size, center.groups.size)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "hookUser 失败", e)
            FriendRefreshResult(false, selfId, "好友刷新未完成：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun loadClass(classLoader: ClassLoader, className: String): Class<*> {
        return Class.forName(className, false, classLoader)
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

