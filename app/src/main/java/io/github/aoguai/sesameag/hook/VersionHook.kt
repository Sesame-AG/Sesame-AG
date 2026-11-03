package io.github.aoguai.sesameag.hook

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.AlipayVersion
import io.github.aoguai.sesameag.util.Log.printStackTrace
import io.github.aoguai.sesameag.util.Log.record
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 版本号 Hook 工具类
 * 用于在应用启动早期拦截并获取目标应用版本信息
 */
object VersionHook {
    private const val TAG = "VersionHook"

    /**
     * -- GETTER --
     * 获取已捕获的版本信息
     *
     */
    // 缓存捕获的版本信息
    private val version = AtomicReference<AlipayVersion?>(null)
    private val hookHandle = AtomicReference<XposedInterface.HookHandle?>(null)
    private val installing = AtomicBoolean(false)

    /**
     * 在 loadPackage 阶段尽早安装 Hook
     *
     * @param classLoader 类加载器
     */
    fun installHook(classLoader: ClassLoader?) {
        if (!installing.compareAndSet(false, true)) return
        try {
            if (version.get() != null || hookHandle.get() != null) {
                record(TAG, "⚠️ 版本已获取或 Hook 已安装,跳过")
                return
            }

            val packageManagerClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader)
            val getPackageInfoMethod = findMethod(
                packageManagerClass,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val handle = ApplicationHook.requireXposedInterface().hook(getPackageInfoMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val packageInfo = result as? PackageInfo
                    if (packageInfo != null && General.PACKAGE_NAME == packageInfo.packageName) {
                        val versionName = packageInfo.versionName
                        val longVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                        val versionCode = longVersionCode.toInt()
                        if (!versionName.isNullOrBlank() && completeCapture(AlipayVersion(versionName))) {
                            record(
                                TAG,
                                "✅ 捕获目标应用版本: $versionName (code: $versionCode, longCode: $longVersionCode)"
                            )
                        }
                    }
                } catch (t: Throwable) {
                    printStackTrace(TAG, t)
                }
                result
            }

            hookHandle.set(handle)
            record(TAG, "✅ 版本号 Hook 安装成功")
            // 安装句柄保存前，框架回调可能已完成版本采集。
            version.get()?.let { completeCapture(it) }
        } catch (t: Throwable) {
            record(TAG, "❌ 安装版本号 Hook 失败")
            printStackTrace(TAG, t)
        } finally {
            installing.set(false)
        }
    }

    /**
     * 保存有效版本并结束采集；卸载失败时保留句柄，供下一次完成通知重试。
     *
     * @return 是否首次保存版本
     */
    internal fun completeCapture(version: AlipayVersion): Boolean {
        if (version.versionString.isBlank()) return false
        val firstCapture = this.version.compareAndSet(null, version)
        val handle = hookHandle.getAndSet(null) ?: return firstCapture
        try {
            handle.unhook()
        } catch (t: Throwable) {
            hookHandle.compareAndSet(null, handle)
            record(TAG, "❌ 卸载版本号 Hook 失败")
            printStackTrace(TAG, t)
        }
        return firstCapture
    }

    /**
     * 检查是否已成功捕获版本号
     *
     * @return true: 已捕获, false: 未捕获
     */
    fun hasVersion(): Boolean {
        return version.get() != null
    }

    /**
     * 获取已捕获的版本信息
     *
     * @return 已捕获的版本信息，如果未捕获则返回 null
     */
    fun getCapturedVersion(): AlipayVersion? {
        return version.get()
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
}

