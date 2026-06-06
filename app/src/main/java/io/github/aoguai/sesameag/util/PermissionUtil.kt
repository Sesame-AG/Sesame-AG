package io.github.aoguai.sesameag.util

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.hook.ApplicationHook

/**
 * 权限工具类，用于检查和请求所需权限。
 * 适配 Android 6.0 - 14.0+
 */
object PermissionUtil {
    // 修复 TAG 获取错误
    private val TAG: String = PermissionUtil::class.java.simpleName
    // 基础存储权限 (Android 10及以下)
    private val PERMISSIONS_STORAGE = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    // --- 1. 文件存储权限 ---

    /**
     * 检查文件存储权限
     * 适配 Android 11+ (MANAGE_EXTERNAL_STORAGE) 和旧版本
     */
    fun checkFilePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            PERMISSIONS_STORAGE.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * 请求文件存储权限。
     *
     * 仅允许从模块前台 Activity 发起；自动调度链路只能读取权限状态。
     */
    fun checkOrRequestFilePermissions(
        activity: Activity,
        permissionLauncher: ActivityResultLauncher<Array<String>>
    ): Boolean {
        if (checkFilePermissions(activity)) return true
        if (!ensureModulePermissionRequestHost(activity, "file")) return false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: 请求所有文件访问权限
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                return startActivitySafely(activity, intent, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            } else {
                permissionLauncher.launch(PERMISSIONS_STORAGE)
                return true
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "请求文件权限失败", e)
        }
        return false
    }

    // --- 2. 闹钟/后台运行权限 ---

    /**
     * 检查精确闹钟权限 (Android 12+)
     */
    @JvmStatic
    fun checkAlarmPermissions(context: Context? = null): Boolean {
        return checkExactAlarmPermissions(context)
    }

    @JvmStatic
    fun checkExactAlarmPermissions(
        context: Context? = null,
        packageName: String? = null
    ): Boolean {
        val ctx = context ?: contextSafely ?: return false
        val targetPackage = packageName?.takeIf { it.isNotBlank() } ?: ctx.packageName

        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                true
            } else if (targetPackage == ctx.packageName) {
                val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                // Android 公开 API 不提供可靠的跨包 exact alarm 状态读取能力。
                false
            }
        }.onFailure {
            Log.printStackTrace(TAG, "检查精确闹钟权限失败: $targetPackage", it)
        }.getOrDefault(false)
    }

    /**
     * 请求精确闹钟权限。
     *
     * 仅允许从模块前台 Activity 发起；自动调度链路只能读取权限状态。
     */
    @JvmStatic
    fun checkOrRequestAlarmPermissions(activity: Activity): Boolean {
        return checkOrRequestExactAlarmPermissions(activity)
    }

    @JvmStatic
    fun checkOrRequestExactAlarmPermissions(
        activity: Activity,
        packageName: String? = null,
        intentLauncher: ActivityResultLauncher<Intent>? = null
    ): Boolean {
        val targetPackage = packageName?.takeIf { it.isNotBlank() } ?: activity.packageName
        if (checkExactAlarmPermissions(activity, targetPackage)) return true
        if (!ensureModulePermissionRequestHost(activity, "exact_alarm")) return false
        if (!isPackageInstalled(activity, targetPackage)) return false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$targetPackage")
                }
                return startActivitySafely(
                    activity,
                    intent,
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    intentLauncher
                )
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "请求闹钟权限失败", e)
        }
        return false
    }

    // --- 3. 电池优化白名单 ---

    /**
     * 检查是否忽略电池优化
     */
    @JvmStatic
    fun checkBatteryPermissions(
        context: Context? = null,
        packageName: String? = null
    ): Boolean {
        val ctx = context ?: contextSafely ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val targetPackage = packageName?.takeIf { it.isNotBlank() } ?: ctx.packageName
        return runCatching {
            pm?.isIgnoringBatteryOptimizations(targetPackage) == true
        }.onFailure {
            Log.printStackTrace(TAG, "检查电池优化权限失败: $targetPackage", it)
        }.getOrDefault(false)
    }

    /**
     * 请求加入电池优化白名单。
     *
     * 仅允许从模块前台 Activity 发起；自动调度链路只能读取权限状态。
     */
    @JvmStatic
    fun checkOrRequestBatteryPermissions(activity: Activity): Boolean {
        return checkOrRequestBatteryPermissions(activity, activity.packageName)
    }

    @JvmStatic
    fun checkOrRequestBatteryPermissions(
        activity: Activity,
        packageName: String
    ): Boolean {
        val targetPackage = packageName.takeIf { it.isNotBlank() } ?: activity.packageName
        if (checkBatteryPermissions(activity, targetPackage)) return true
        if (!ensureModulePermissionRequestHost(activity, "battery_optimization")) return false
        if (!isPackageInstalled(activity, targetPackage)) return false

        try {
            // 尝试直接请求指定包名
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$targetPackage")
            }
            return startActivitySafely(activity, intent, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "请求电池优化权限失败", e)
        }
        return false
    }

    // --- 4. 通知权限 (Android 13+) ---

    /**
     * 检查通知权限 (Android 13+)
     */
    fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // 旧版本默认允许
        }
    }

    /**
     * 请求通知权限。
     *
     * 仅允许从模块前台 Activity 发起；自动调度链路只能读取权限状态。
     */
    fun checkOrRequestNotificationPermission(
        activity: Activity,
        permissionLauncher: ActivityResultLauncher<Array<String>>
    ): Boolean {
        if (checkNotificationPermission(activity)) return true
        if (!ensureModulePermissionRequestHost(activity, "notification")) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return true
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "请求通知权限失败", e)
            }
            return false
        }
        return true
    }

    // --- 内部辅助方法 ---

    /**
     * 安全启动 Activity，自动处理 Flag 和 异常
     */
    private fun startActivitySafely(
        context: Context,
        intent: Intent,
        fallbackAction: String? = null,
        intentLauncher: ActivityResultLauncher<Intent>? = null
    ): Boolean {
        try {
            if (intentLauncher != null && context is Activity) {
                intentLauncher.launch(intent)
                return true
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            if (!fallbackAction.isNullOrEmpty()) {
                try {
                    val fallbackIntent = Intent(fallbackAction).apply {
                        if (context !is Activity || intentLauncher == null) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    if (intentLauncher != null && context is Activity) {
                        intentLauncher.launch(fallbackIntent)
                    } else {
                        context.startActivity(fallbackIntent)
                    }
                    return true
                } catch (ex: Exception) {
                    Log.printStackTrace(TAG, "Fallback Intent 启动失败: $fallbackAction", ex)
                }
            } else {
                Log.printStackTrace(TAG, "Intent 启动失败: ${intent.action}", e)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "未知错误", e)
        }
        return false
    }

    private fun ensureModulePermissionRequestHost(activity: Activity, permissionName: String): Boolean {
        if (activity.packageName == BuildConfig.APPLICATION_ID) {
            return true
        }
        Log.record(TAG, "忽略非模块前台的权限申请请求: permission=$permissionName package=${activity.packageName}")
        return false
    }

    /**
     * 获取 Hook 环境下的 Context (仅用于被 Hook 的宿主环境中)
     */
    private val contextSafely: Context?
        get() = try {
            if (ApplicationHook.isHooked) ApplicationHook.appContext else null
        } catch (_: Exception) {
            null
        }
}
