package io.github.aoguai.sesameag.hook.internal

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.BaseDexClassLoader
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook.Companion.requireXposedInterface
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.util.MiniGameVendorReporter
import io.github.aoguai.sesameag.util.Log
import okhttp3.OkHttp
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 宿主官方 HTTP 请求观察入口；业务模块各自提取和保存会话。 */
internal object OfficialRequestObserver {
    private const val XRIVER = "com.alibaba.xriver.android.proxy.CRVRequestProxy"
    private const val H5 = "com.alipay.mobile.common.transport.h5.H5NetworkManager"
    private val installed = AtomicBoolean()
    private val hooked = ConcurrentHashMap.newKeySet<Method>()
    private val classInstallations = ConcurrentHashMap<Class<*>, Boolean>()

    fun install(loader: ClassLoader, context: Context, onChanged: (() -> Unit)? = null) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val moduleResources = context.packageManager.getResourcesForApplication(requireXposedInterface().moduleApplicationInfo)
            // OkHttp 会再次取 applicationContext；保留模块 assets，避免转回宿主资源。
            val moduleContext = object : ContextWrapper(context.applicationContext ?: context) {
                override fun getApplicationContext(): Context = this
                override fun getAssets(): AssetManager = moduleResources.assets
                override fun getResources(): Resources = moduleResources
            }
            OkHttp.initialize(moduleContext)
            Log.runtime("OfficialRequestObserver", "官方请求 HTTP 环境已初始化，使用模块资源")
        } catch (error: Exception) {
            installed.set(false)
            Log.printStackTrace("OfficialRequestObserver", "官方请求 HTTP 初始化失败，未安装会话观察入口", error)
            return
        }
        if (onChanged != null) StoreAccess.observe { change ->
            val session = AccountSessionCoordinator.currentSession() ?: return@observe
            if (change.collection == 2 && change.owner == session.userId &&
                (change.name == "UserDataStore.json" || change.name.isEmpty())) onChanged()
        }
        val loaders = linkedSetOf<Class<*>>(ClassLoader::class.java, BaseDexClassLoader::class.java)
        var type: Class<*>? = loader.javaClass
        while (type != null && ClassLoader::class.java.isAssignableFrom(type)) {
            loaders.add(type)
            type = type.superclass
        }
        loaders.flatMap { it.declaredMethods.toList() }.filter {
            it.name == "loadClass" && it.parameterTypes.firstOrNull() == String::class.java
        }.forEach { method ->
            runCatching {
                requireXposedInterface().hook(method).intercept { chain ->
                    val name = chain.args.firstOrNull()
                    if (name != XRIVER && name != H5) return@intercept chain.proceed()
                    val result = chain.proceed()
                    if (result is Class<*>) {
                        runCatching { installRequests(result, loader) }
                            .onFailure { Log.printStackTrace("OfficialRequestObserver", "官方请求入口安装失败: ${result.name}", it) }
                    }
                    result
                }
            }.onFailure { Log.printStackTrace("OfficialRequestObserver", "官方请求观察安装失败: $method", it) }
        }
        listOf(XRIVER, H5).forEach { name ->
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { installRequests(it, loader) }
        }
    }

    private fun installRequests(type: Class<*>, loader: ClassLoader) {
        // 警告：反射成员会重新进入类加载回调，必须在解析方法之前登记，不能等到 hook(method) 才去重。
        if (classInstallations.putIfAbsent(type, false) != null) return
        try {
            val methods = type.declaredMethods.filter { method ->
                (type.name == XRIVER && method.name == "handleRequest" && method.parameterTypes.size == 3 &&
                    method.parameterTypes[1].name == "com.alibaba.fastjson.JSONObject") ||
                    (type.name == H5 && method.name == "enqueue" && method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name == "com.alipay.mobile.common.transport.h5.H5HttpUrlRequest")
            }
            methods.forEach { method ->
                if (!hooked.add(method)) return@forEach
                runCatching {
                    val requestType = if (type.name == H5) Class.forName(
                        "com.alipay.mobile.common.transport.http.HttpUrlRequest", false, type.classLoader) else null
                    val urlField = requestType?.getDeclaredField("mUrl")?.apply { isAccessible = true }
                    val headersField = requestType?.getDeclaredField("mHeaders")?.apply { isAccessible = true }
                    val bodyField = requestType?.declaredFields?.firstOrNull { it.name == "mReqData" }
                        ?.apply { isAccessible = true }
                    requireXposedInterface().hook(method).intercept { chain ->
                        runCatching observation@{
                            if (type.name == XRIVER) {
                                val request = JSONObject(chain.args[1].toString())
                                observe(loader, request.optString("url"), request.opt("headers"), request.opt("data"), "XRiver")
                            } else {
                                val request = chain.args[0] ?: return@observation
                                val url = urlField?.get(request) as? String ?: return@observation
                                val headers = JSONObject()
                                (headersField?.get(request) as? Iterable<*>)?.filterNotNull()?.forEach { header ->
                                    val name = runCatching {
                                        header.javaClass.getMethod("getName").invoke(header) as? String
                                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                                    val value = runCatching {
                                        header.javaClass.getMethod("getValue").invoke(header) as? String
                                    }.getOrNull() ?: return@forEach
                                    headers.put(name, value)
                                }
                                observe(loader, url, headers, bodyField?.get(request), "H5")
                            }
                        }.onFailure { error ->
                            Log.error("OfficialRequestObserver", "官方请求解析失败: ${type.name}.${method.name}\n" +
                                generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
                                    .joinToString("\nCaused by: ") { "${it.javaClass.name}\n${it.stackTrace.joinToString("\n")}" })
                        }
                        chain.proceed()
                    }
                    Log.runtime("OfficialRequestObserver", "官方请求观察入口已安装: ${type.name}.${method.name}")
                }.onFailure {
                    hooked.remove(method)
                    Log.printStackTrace("OfficialRequestObserver", "官方请求入口安装失败: $method", it)
                }
            }
            if (methods.isNotEmpty() && methods.all { it in hooked }) classInstallations[type] = true
        } finally {
            classInstallations.remove(type, false)
        }
    }

    private fun observe(loader: ClassLoader, rawUrl: String, headers: Any?, body: Any?, entry: String) {
        val endpoint = runCatching { URL(rawUrl).let { "${it.protocol}://${it.host}${it.path}" } }.getOrDefault("")
        runCatching { ThreeHoursSession.observe(loader, rawUrl, headers) }.onFailure { error ->
            Log.error("OfficialRequestObserver", "ThreeHoursSession.observe 失败: $endpoint\n" +
                generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
                    .joinToString("\nCaused by: ") { "${it.javaClass.name}\n${it.stackTrace.joinToString("\n")}" })
        }
        runCatching { MiniGameVendorReporter.observeOfficialSession(loader, rawUrl, body, entry) }.onFailure { error ->
            Log.error("OfficialRequestObserver", "MiniGameVendorReporter.observeOfficialSession 失败: $endpoint\n" +
                generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
                    .joinToString("\nCaused by: ") { "${it.javaClass.name}\n${it.stackTrace.joinToString("\n")}" })
        }
    }
}
