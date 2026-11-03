package io.github.aoguai.sesameag.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import androidx.core.content.edit
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.AccountSlotSnapshot
import io.github.aoguai.sesameag.service.LsposedServiceManager
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 模块进程的排障记录；不触发授权、不查询业务接口、不上传日志。 */
object ModuleDiagnostics {
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 256 * 1024
    private const val CURSOR_KEY = "diagnostic_logcat_cursor"
    private const val HASHES_KEY = "diagnostic_logcat_hashes"
    private const val BOOT_KEY = "diagnostic_boot_count"
    private val collectionQueue = SerialTaskQueue()
    private val collecting = AtomicBoolean(false)
    private val lastState = AtomicReference<String?>(null)

    fun account(userId: String?): String =
        userId?.trim()?.takeIf { it.isNotEmpty() }?.let(AccountSlotRegistry::shortHash) ?: "unknown"

    fun event(action: String, result: String, detail: String = "") {
        if (Application.getProcessName() != General.MODULE_PACKAGE_NAME) return
        // result/detail 由调用方提供固定字段；账号使用 account()，不传递配置和任务参数。
        Log.system("at=${Instant.now()} event=$action result=$result process=${Application.getProcessName()} " +
            "pid=${Process.myPid()} uid=${Process.myUid()} androidUser=${Process.myUid() / 100_000} ${detail.replace('\n', ' ').replace('\r', ' ')}")
    }

    @Suppress("DEPRECATION")
    fun environment(context: Context, reason: String) {
        val target = runCatching { context.packageManager.getPackageInfo(General.PACKAGE_NAME, 0) }
        val targetInfo = target.getOrNull()
        val installSource = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull() ?: "unknown"
        val framework = LsposedServiceManager.connectedFrameworkStatus()
        val shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = if (shizukuAlive) runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrNull() else false
        val launchAvailable = runCatching {
            context.packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME) != null
        }.getOrNull()
        event("environment", reason,
            "module=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "build=${BuildConfig.BUILD_DATE}_${BuildConfig.BUILD_TIME} debug=${BuildConfig.DEBUG} " +
                "officialSignature=${OfficialBuildVerifier.isOfficiallySigned(context)} installer=$installSource " +
                "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                "sdk=${Build.VERSION.SDK_INT} rom=${Build.DISPLAY} patch=${Build.VERSION.SECURITY_PATCH} " +
                "kernel=${System.getProperty("os.version")} abi=${Build.SUPPORTED_ABIS.joinToString()} " +
                "framework=${framework?.frameworkName ?: "unknown"} api=${framework?.apiVersion ?: "unknown"} " +
                "frameworkVersion=${framework?.frameworkVersion ?: "unknown"} " +
                "targetQuery=${if (target.isSuccess) "visible" else "unknown"} " +
                "targetQueryError=${target.exceptionOrNull()?.javaClass?.simpleName ?: "none"} " +
                "targetVersion=${targetInfo?.versionName ?: "unknown"} targetCode=${targetInfo?.longVersionCode ?: "unknown"} " +
                "targetUid=${targetInfo?.applicationInfo?.uid ?: "unknown"} targetUser=${targetInfo?.applicationInfo?.uid?.div(100_000) ?: "unknown"} " +
                "targetLaunchAvailable=${launchAvailable ?: "unknown"} targetScope=${LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)} " +
                "androidMaintained=${Build.VERSION.SDK_INT >= 36} " +
                "executor=${(CommandUtil.serviceStatus.value as? CommandUtil.ServiceStatus.Active)?.type ?: "unavailable"} " +
                "shizukuAlive=$shizukuAlive shizukuGranted=${shizukuGranted ?: "unknown"} " +
                "storage=private systemFile=${Logback.systemLogFile?.absolutePath ?: "unavailable"}")
    }

    fun state(
        permissions: PermissionHealthSnapshot,
        configState: String,
        configCount: Int?,
        userId: String?,
        legalAccepted: Boolean,
        slots: AccountSlotSnapshot,
    ) {
        val framework = LsposedServiceManager.connectedFrameworkStatus()
        val value = "framework=${framework?.frameworkName ?: "unknown"} api=${framework?.apiVersion ?: "unknown"} " +
            "permissions=${permissions.items.joinToString { "${it.requirement}:${it.status}" }} " +
            "config=$configState count=${configCount ?: "unknown"} account=${account(userId)} legal=$legalAccepted " +
            "slotState=${slots.migrationState} slotError=${slots.errorCode ?: "none"} " +
            "slots=${slots.activeUserIds.joinToString { account(it) }} pending=${account(slots.pendingRuntimeUserId)}"
        if (value == lastState.getAndSet(value)) return
        event("home_state", "changed", value)
    }

    private data class LogcatLine(
        val atMs: Long,
        val uid: Int,
        val pid: Int,
        val priority: String,
        val tag: String,
        val message: String,
        val raw: String,
    )

    // epoch + uid 有固定的时间、UID、PID、TID、优先级和 tag 字段；不猜测缺失的来源。
    private fun parseLine(line: String): LogcatLine? {
        val columns = line.trimStart().split(' ', '\t').filter { it.isNotEmpty() }
        if (columns.size < 6) return null
        val seconds = columns[0].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val uid = columns[1].toIntOrNull() ?: return null
        val pid = columns[2].toIntOrNull() ?: return null
        if (columns[3].toIntOrNull() == null || columns[4] !in setOf("W", "E", "F")) return null
        val tag = columns[5].removeSuffix(":")
        val message = line.substring(line.indexOf(tag) + tag.length).trimStart().removePrefix(":").trimStart()
        return LogcatLine((seconds * 1000).toLong(), uid, pid, columns[4], tag, message, line)
    }

    suspend fun collectLogcat(context: Context, reason: String) = withContext(Dispatchers.IO) {
        if (!collecting.compareAndSet(false, true)) return@withContext
        try {
            collectionQueue.submit {
            val executor = (CommandUtil.serviceStatus.value as? CommandUtil.ServiceStatus.Active)?.type
            if (executor == null) {
                event("logcat", "executor_unavailable", "trigger=$reason")
                return@submit
            }
            // 六秒为诊断日志采集的等待预算，避免诊断长期占用采集队列；结果先到就立即继续。
            val result = withTimeoutOrNull(6_000) { CommandUtil.readDiagnosticLogcat() }
            if (result == null) {
                event("logcat", "timeout", "executor=$executor limitMs=6000")
                return@submit
            }
            val output = result.getOrElse {
                val outcome = when (it.message) {
                    "executor_unavailable", "permission_denied", "timeout" -> it.message!!
                    else -> "failed"
                }
                event("logcat", outcome, "executor=$executor error=${it.javaClass.simpleName}")
                return@submit
            }
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(SesameApplication.PREFERENCES_KEY, Context.MODE_PRIVATE)
            val storedCursor = prefs.getLong(CURSOR_KEY, 0L)
            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val rebooted = bootCount >= 0 && prefs.getInt(BOOT_KEY, -1) != bootCount
            val clockReset = storedCursor > now
            val resetCursor = rebooted || clockReset
            val cursor = if (resetCursor) now - WINDOW_MS else maxOf(storedCursor, now - WINDOW_MS)
            if (resetCursor) event("logcat", "cursor_reset", "rebooted=$rebooted clockReset=$clockReset windowMs=$WINDOW_MS")
            val bytes = output.toByteArray(Charsets.UTF_8)
            val bounded = if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES).toString(Charsets.UTF_8).substringBeforeLast('\n') else output
            val rawLines = bounded.lineSequence().filter { it.isNotBlank() && !it.startsWith("---------") }.toList()
            val parsed = rawLines.mapNotNull(::parseLine)
            if (rawLines.isNotEmpty() && parsed.isEmpty()) {
                event("logcat", "parse_failed", "trigger=$reason received=${rawLines.size}")
                return@submit
            }
            val targetUid = runCatching {
                context.packageManager.getApplicationInfo(General.PACKAGE_NAME, 0).uid
            }.getOrNull()
            val allowedUids = setOfNotNull(Process.myUid(), targetUid)
            val recent = parsed.filter { it.atMs in cursor..now }
            // 包被隐藏或位于其他 Android 用户时，仍可关联模块自己标记的早期拒绝记录。
            val moduleSources = recent.filter {
                it.tag in setOf("SesameLog", "LibXposedRuntime", "ApplicationHook") &&
                    it.message.startsWith("module=${General.MODULE_PACKAGE_NAME} ")
            }.map { it.uid to it.pid }.toSet()
            val candidates = recent.filter { it.uid in allowedUids || (it.uid to it.pid) in moduleSources }
            val crashProcesses = candidates.filter { entry ->
                if (entry.tag != "AndroidRuntime" || !entry.message.startsWith("Process: ")) false
                else {
                    val process = entry.message.removePrefix("Process: ").substringBefore(',')
                    listOf(General.MODULE_PACKAGE_NAME, General.PACKAGE_NAME).any {
                        process == it || process.startsWith("$it:")
                    }
                }
            }.map { it.uid to it.pid }.toSet()
            val relevant = candidates.filter { entry ->
                when (entry.tag) {
                    "SesameLog", "LibXposedRuntime", "ApplicationHook" -> true
                    "AndroidRuntime" -> (entry.uid to entry.pid) in crashProcesses
                    else -> false
                }
            }
            val hashes = if (resetCursor) linkedSetOf() else
                prefs.getString(HASHES_KEY, "").orEmpty().split(',').filter { it.isNotEmpty() }.toCollection(linkedSetOf())
            // 复用导出时的脱敏词表；按批读取一次，不在日志循环内访问账号文件。
            val keywords = Files.collectLogSensitiveKeywords()
            var imported = 0
            var duplicates = 0
            for (entry in relevant) {
                currentCoroutineContext().ensureActive()
                val hash = AccountSlotRegistry.shortHash(entry.raw)
                if (!hashes.add(hash)) {
                    duplicates++
                    continue
                }
                val message = Files.maskSensitiveText(entry.message, keywords)
                // 回填只写 INFO，原始级别作为数据保存，避免下一次 W/E 采集再次吸入。
                event("logcat_entry", "imported", "sourceAt=${Instant.ofEpochMilli(entry.atMs)} " +
                    "sourceUid=${entry.uid} sourcePid=${entry.pid} priority=${entry.priority} tag=${entry.tag} message=$message")
                imported++
            }
            val newest = parsed.filter { it.atMs <= now }.maxOfOrNull { it.atMs } ?: cursor
            prefs.edit {
                putLong(CURSOR_KEY, maxOf(cursor, newest - 1_000))
                putInt(BOOT_KEY, bootCount)
                putString(HASHES_KEY, hashes.toList().takeLast(256).joinToString(","))
            }
            val truncated = bytes.size > MAX_BYTES || parsed.size >= 64 || output.endsWith("[diagnostic_output_truncated]")
            event("logcat", if (relevant.isEmpty()) "no_matches" else "completed",
                "trigger=$reason executor=$executor windowMs=$WINDOW_MS recordLimit=64 byteLimit=$MAX_BYTES " +
                    "received=${parsed.size} imported=$imported duplicates=$duplicates unparsed=${rawLines.size - parsed.size} " +
                    "possiblyTruncated=$truncated targetUid=${targetUid ?: "unknown"}")
            }
        } catch (e: CancellationException) {
            event("logcat", "cancelled")
            throw e
        } catch (e: Exception) {
            event("logcat", "failed", "error=${e.javaClass.simpleName}")
        } finally {
            collecting.set(false)
        }
    }

    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        collectionQueue.submit {
            val cleared = runCatching { Logback.clearSystemLog() }.getOrElse {
                event("clear_diagnostics", "failed", "error=${it.javaClass.simpleName}")
                false
            }
            if (cleared) {
                context.getSharedPreferences(SesameApplication.PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putLong(CURSOR_KEY, System.currentTimeMillis())
                    putInt(BOOT_KEY, Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
                    remove(HASHES_KEY)
                }
            }
            lastState.set(null)
            event("clear_diagnostics", if (cleared) "completed" else "failed")
            cleared
        }
    }
}
