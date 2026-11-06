package io.github.aoguai.sesameag.hook.rpc

import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 页面级验证信号嗅探。
 *
 * 滑块/验证有很大一部分是 H5 页面自己弹的：模块的 RPC 全部正常返回，只是任务状态不推进，
 * 因此 [RiskControlGuard] 拿不到任何错误码。这里常驻 hook H5 传输入口，只读取 URL 做匹配：
 *
 * - 默认只写日志（`RiskVerifyPage` 标记），用来收集真实的验证页 URL；
 * - 打开「页面验证自动暂停」后，命中即触发软暂停 + 探活，缓解"页面弹滑块但模块无感知"。
 *
 * 该 hook 不依赖抓包开关，也不做任何 payload 解析，单次开销只有一次 URL 字符串匹配。
 */
object RiskVerifyPageWatcher {
    private const val TAG = "RiskVerifyPage"
    private const val LOGCAT_TAG = "SesameLog"

    private const val H5_NETWORK_CLASS = "com.alipay.mobile.common.transport.h5.H5NetworkManager"

    /** 同一 URL 摘要的日志间隔，避免每帧刷新刷屏。 */
    private const val LOG_INTERVAL_MS = 10 * 60_000L

    /**
     * 命中规则。
     *
     * 2026-11-06 实测日志确认：滑块验证码资源走 `mdn.alipayobjects.com/netflow_captcha/...`，
     * 因此以 `captcha` 系特征为主；`verify/identity/security` 这类泛词容易误伤正常页面，已移除。
     */
    private val urlPatterns = listOf(
        "netflow_captcha",
        "captcha",
        "riskverify",
        "riskcontrol",
        "seccheck",
        "needauth",
        "sliderverify",
    )

    private val installed = AtomicBoolean(false)
    private val hookedMethods: MutableMap<Method, XposedInterface.HookHandle> = ConcurrentHashMap()

    /** 仅用于"等 H5NetworkManager 被加载"的临时监听，装好 enqueue 后立刻卸载，避免常驻在类加载热路径上。 */
    private val listenerHooks: MutableMap<Method, XposedInterface.HookHandle> = ConcurrentHashMap()
    private val lastLogAtMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastViewDumpAtMs: Long = 0L

    private val urlFieldNames = listOf("url", "mUrl", "h5Url", "requestUrl", "originUrl", "srcUrl")
    private val nestedFieldNames = listOf("h5HttpUrlRequest", "request", "urlRequest")

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        // 优先直接解析目标类：类已加载时完全不碰类加载热路径。
        val direct = runCatching { installDirect(classLoader) }
            .onFailure { Log.printStackTrace(TAG, "安装 H5 验证页嗅探失败", it) }
            .getOrDefault(false)
        if (direct) return
        // 类尚未加载：挂临时监听，装好 enqueue 后自行卸载。
        runCatching { installLoadClassListener(classLoader) }
            .onFailure { Log.printStackTrace(TAG, "安装类加载监听失败", it) }
    }

    private fun installDirect(classLoader: ClassLoader): Boolean =
        runCatching { installEnqueueHook(Class.forName(H5_NETWORK_CLASS, false, classLoader)) }
            .getOrDefault(false)

    private fun installLoadClassListener(classLoader: ClassLoader) {
        val loaderClasses = linkedSetOf<Class<*>>(ClassLoader::class.java, Class.forName("dalvik.system.BaseDexClassLoader"))
        var current: Class<*>? = classLoader.javaClass
        while (current != null && ClassLoader::class.java.isAssignableFrom(current)) {
            loaderClasses.add(current)
            current = current.superclass
        }
        loaderClasses.forEach { loaderClass ->
            runCatching {
                loaderClass.declaredMethods
                    .filter { it.name == "loadClass" && it.parameterTypes.firstOrNull() == String::class.java }
                    .forEach { method ->
                        if (listenerHooks.containsKey(method) || hookedMethods.containsKey(method)) return@forEach
                        method.isAccessible = true
                        val handle = ApplicationHook.requireXposedInterface().hook(method).intercept { chain ->
                            val result = chain.proceed()
                            if (result is Class<*> && result.name == H5_NETWORK_CLASS) {
                                runCatching { installEnqueueHook(result) }
                            }
                            result
                        }
                        listenerHooks[method] = handle
                    }
            }.onFailure {
                runCatching { Log.runtime(TAG, "读取类加载入口失败: ${loaderClass.name}: ${it.message}") }
            }
        }
    }

    private fun uninstallLoadClassListeners() {
        listenerHooks.entries.toList().forEach { (method, handle) ->
            runCatching { handle.unhook() }
            listenerHooks.remove(method, handle)
        }
    }

    private fun installEnqueueHook(targetClass: Class<*>): Boolean {
        val methods = linkedMapOf<List<Class<*>>, Method>()
        var owner: Class<*>? = targetClass
        while (owner != null && owner != Any::class.java) {
            owner.declaredMethods.filter {
                it.name == "enqueue" && it.parameterCount == 1 &&
                    Future::class.java.isAssignableFrom(it.returnType) && !Modifier.isAbstract(it.modifiers)
            }.forEach { methods.putIfAbsent(it.parameterTypes.toList(), it) }
            owner = owner.superclass
        }
        if (methods.isEmpty()) return false
        var installedAny = false
        methods.values.forEach { method ->
            if (hookedMethods.containsKey(method)) return@forEach
            runCatching {
                method.isAccessible = true
                val handle = ApplicationHook.requireXposedInterface().hook(method).intercept { chain ->
                    runCatching { inspect(chain.args.getOrNull(0)) }
                    chain.proceed()
                }
                hookedMethods[method] = handle
                installedAny = true
            }.onFailure {
                runCatching { Log.runtime(TAG, "安装 enqueue 嗅探失败: $method: ${it.message}") }
            }
        }
        if (installedAny) {
            // enqueue 已装上，类加载监听没有继续存在的必要。
            // 如果这里正跑在 loadClass hook 内部，直接 unhook 自身不安全，交给主线程下一帧处理。
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post { uninstallLoadClassListeners() }
            }.onFailure { uninstallLoadClassListeners() }
            trace("已安装 H5 验证页嗅探: ${targetClass.name}")
        }
        return installedAny
    }

    private fun inspect(requestObject: Any?) {
        if (requestObject == null) return
        val url = extractUrl(requestObject, 0) ?: return
        if (!looksLikeVerification(url)) return
        val summary = url.substringBefore('?').take(200)
        val now = System.currentTimeMillis()
        val last = lastLogAtMs[summary]
        if (last != null && now - last < LOG_INTERVAL_MS) return
        lastLogAtMs[summary] = now
        trace("疑似验证页 task=${currentTaskLabel()} url=$summary")
        // 顺手记录一次前台视图结构：滑块可能在 H5(WebView) 里，也可能是原生组件，
        // 只有拿到真实 View 类名/边界，才能把拖动注入打到正确的位置。
        dumpCaptchaViewTreeOnce()
        if (BaseModel.riskPageAutoPause.value == true) {
            runCatching {
                RiskControlGuard.onExternalVerificationSignal(TAG, "H5 页面验证: $summary")
            }.onFailure { Log.printStackTrace(TAG, "页面验证信号处理失败", it) }
        }
        if (BaseModel.captchaAutoSolve.value == true &&
            io.github.aoguai.sesameag.hook.captcha.CaptchaAutoSolver.shouldAttempt()
        ) {
            runCatching {
                io.github.aoguai.sesameag.hook.captcha.CaptchaAutoSolver.solveOnce("h5_verify_page")
            }.onFailure { Log.printStackTrace(TAG, "自动过滑块调度失败", it) }
        }
    }

    private fun looksLikeVerification(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return urlPatterns.any { lower.contains(it) }
    }

    /**
     * 验证页出现时抓一次前台视图结构（10 分钟内只抓一次）。
     * 目的是定位滑块真正的宿主 View：H5 场景是 WebView，原生场景是具体控件。
     */
    private fun dumpCaptchaViewTreeOnce() {
        val now = System.currentTimeMillis()
        val last = lastViewDumpAtMs
        if (last > 0L && now - last < LOG_INTERVAL_MS) return
        lastViewDumpAtMs = now
        runCatching {
            val activity = io.github.aoguai.sesameag.hook.PageStructureSnapshotter.resumedActivity() ?: return@runCatching
            val decor = activity.window?.decorView ?: return@runCatching
            val builder = StringBuilder()
            builder.append("验证页视图 activity=").append(activity.javaClass.name).append('\n')
            collectViews(decor, builder, 0)
            trace(builder.toString().trimEnd())
        }.onFailure { Log.printStackTrace(TAG, "抓取验证页视图失败", it) }
    }

    private fun collectViews(
        view: android.view.View,
        builder: StringBuilder,
        depth: Int,
    ) {
        if (depth > 10 || view.visibility != android.view.View.VISIBLE) return
        if (view.width <= 0 || view.height <= 0) return
        builder.append("  ".repeat(depth))
            .append(view.javaClass.name.substringAfterLast('.'))
            .append(" id=").append(runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("NO_ID"))
            .append(" [").append(view.left).append(',').append(view.top).append(']')
            .append(' ').append(view.width).append('x').append(view.height)
            .append('\n')
        if (view !is android.view.ViewGroup) return
        for (index in 0 until view.childCount) {
            collectViews(view.getChildAt(index), builder, depth + 1)
        }
    }

    private fun extractUrl(
        target: Any?,
        depth: Int,
    ): String? {
        if (target == null || depth > 2) return null
        runCatching {
            val url = target.javaClass.getMethod("getUrl").invoke(target) as? String
            if (!url.isNullOrBlank()) return url
        }
        urlFieldNames.forEach { name ->
            val field = findField(target.javaClass, name) ?: return@forEach
            runCatching {
                field.isAccessible = true
                val value = field.get(target) as? String
                if (!value.isNullOrBlank()) return value
            }
        }
        nestedFieldNames.forEach { name ->
            val field = findField(target.javaClass, name) ?: return@forEach
            val nested = runCatching {
                field.isAccessible = true
                field.get(target)
            }.getOrNull()
            extractUrl(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findField(
        type: Class<*>,
        name: String,
    ): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun trace(message: String) {
        Log.record(TAG, message)
        runCatching { android.util.Log.i(LOGCAT_TAG, "$TAG: $message") }
    }

    /** 验证页出现时正在跑哪个任务，便于定位是哪个任务反复触发验证。 */
    private fun currentTaskLabel(): String =
        runCatching {
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.taskLabel()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
}
