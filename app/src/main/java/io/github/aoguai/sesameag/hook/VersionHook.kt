package io.github.aoguai.sesameag.hook

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.AlipayVersion
import io.github.aoguai.sesameag.util.Log.printStackTrace
import io.github.aoguai.sesameag.util.Log.record
import java.lang.reflect.Method
import kotlin.concurrent.Volatile

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
    @Volatile
    private var capturedVersion: AlipayVersion? = null

    @Volatile
    private var hookInstalled = false

    /**
     * 在 loadPackage 阶段尽早安装 Hook
     *
     * @param classLoader 类加载器
     */
    fun installHook(classLoader: ClassLoader?) {
        if (hookInstalled) {
            record(TAG, "⚠️ Hook 已安装,跳过")
            return
        }

        try {
            val packageManagerClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader)
            val getPackageInfoMethod = findMethod(
                packageManagerClass,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            ApplicationHook.requireXposedInterface().hook(getPackageInfoMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val packageInfo = result as? PackageInfo
                    if (packageInfo != null && General.PACKAGE_NAME == packageInfo.packageName) {
                        val versionName = packageInfo.versionName
                        val longVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                        val versionCode = longVersionCode.toInt()
                        if (capturedVersion == null && versionName != null) {
                            capturedVersion = AlipayVersion(versionName)
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

            hookInstalled = true
            record(TAG, "✅ 版本号 Hook 安装成功")
        } catch (t: Throwable) {
            record(TAG, "❌ 安装版本号 Hook 失败")
            printStackTrace(TAG, t)
        }
    }

    /**
     * 检查是否已成功捕获版本号
     *
     * @return true: 已捕获, false: 未捕获
     */
    fun hasVersion(): Boolean {
        return capturedVersion != null
    }

    /**
     * 获取已捕获的版本信息
     *
     * @return 已捕获的版本信息，如果未捕获则返回 null
     */
    fun getCapturedVersion(): AlipayVersion? {
        return capturedVersion
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

