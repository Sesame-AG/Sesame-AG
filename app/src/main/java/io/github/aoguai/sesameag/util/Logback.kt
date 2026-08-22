package io.github.aoguai.sesameag.util

import android.app.Application
import android.content.Context
import android.util.Log
import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.android.LogcatAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.model.BaseModel
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Logback {
    private const val DEFAULT_LOG_FILE_MAX_SIZE_MB = 7
    private const val DEFAULT_LOG_TOTAL_SIZE_CAP_MB = 256

    private var isFileInitialized = false
    private var appContext: Context? = null
    private var nextMidnightMillis: Long = 0
    private var isRollingOwnerProcess = false
    private var processLogSuffix: String? = null

    // 捕获 Hook 在账户配置加载前已可能写入日志，首轮文件初始化必须直接创建 capture Appender。
    private var isCaptureFileAppenderEnabled = true

    /**
     * 初始化 Logcat (保证控制台一定有日志)
     * 在 Log 类的 init 块中自动调用
     */
    fun initLogcatOnly() {
        try {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext
            lc.reset() // 清除之前的配置

            val encoder =
                PatternLayoutEncoder().apply {
                    context = lc
                    pattern = "[%thread] %logger{80} %msg%n"
                    start()
                }

            val logcatAppender =
                LogcatAppender().apply {
                    context = lc
                    this.encoder = encoder
                    name = "LOGCAT"
                    start()
                }

            lc.getLogger(Logger.ROOT_LOGGER_NAME).apply {
                level = Level.DEBUG // 确保 Logcat 能看到所有级别的日志
                addAppender(logcatAppender)
            }
        } catch (e: Exception) {
            Log.e("SesameLog", "Logback initLogcatOnly failed", e)
        }
    }

    /**
     * 初始化文件日志 (有了 Context 之后调用)
     * 常规日志由主进程滚动；抓包日志由所有已允许的进程安全追加到同一个 capture.log。
     */
    @Synchronized
    fun initFileLogging(
        context: Context,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        // 1. 如果已经初始化过，且还没到跨天刷新的时间，则直接跳过
        if (!force && isFileInitialized && now < nextMidnightMillis) return

        // 记录本次初始化是否属于“重建已有 appender”
        val isRebuildingExistingAppenders = isFileInitialized

        // 2. 保存 Context 供后续跨天自动刷新使用
        this.appContext = context.applicationContext
        val processName = Application.getProcessName()
        isRollingOwnerProcess = processName == General.PACKAGE_NAME
        processLogSuffix = if (isRollingOwnerProcess) {
            null
        } else {
            processName.substringAfter("${General.PACKAGE_NAME}:", "secondary")
        }

        // 3. 如果是触发了跨天刷新，需重置上下文以彻底清除旧的 Appender 句柄
        if (isRebuildingExistingAppenders) {
            Log.i("SesameLog", if (force) "检测到日志配置变更，正在刷新日志重定向..." else "检测到跨天，正在刷新日志重定向...")
            initLogcatOnly() // 内部执行 lc.reset()
        }

        val logDir = resolveLogDir(context)

        try {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext

            val fullTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val allLogNames = (LogCatalog.loggerNames() + listOf("other", "captcha")).distinct()

            allLogNames.forEach { logName ->
                if (logName == LogChannel.CAPTURE.loggerName && !isCaptureFileAppenderEnabled) {
                    return@forEach
                }
                val fileName = resolveLogFileName(logName)
                addFileAppender(lc, logName, logDir, fileName)

                val logFile = File(logDir, fileName)
                val logger = lc.getLogger(logName)

                if (logName != LogChannel.CAPTURE.loggerName) {
                    if (!logFile.exists() || logFile.length() == 0L) {
                        logger.info("=== $fullTimestamp ===")
                    } else if (isRebuildingExistingAppenders) {
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                        logger.info("--- 日志重定向于 $time ---")
                    }
                }
            }

            isFileInitialized = true
            nextMidnightMillis = calculateNextMidnight(now)
            Log.i("SesameLog", "文件日志初始化成功: $logDir, 下次刷新时间: ${java.util.Date(nextMidnightMillis)}")
        } catch (e: Exception) {
            Log.e("SesameLog", "Logback initFileLogging 失败", e)
        }
    }

    /**
     * 【联动刷新】供 Log.kt 每次写日志前调用，感应日期变化并自动重定向。
     * 只有滚动所有者进程跨天时，才会触发 initFileLogging 重新建立滚动 Appender。
     */
    fun refreshIfCrossDay() {
        if (!isRollingOwnerProcess) return
        val now = System.currentTimeMillis()
        if (isFileInitialized && now >= nextMidnightMillis) {
            appContext?.let { initFileLogging(it) }
        }
    }

    @Synchronized
    fun reloadFileLogging(enableCaptureAppender: Boolean = isCaptureFileAppenderEnabled) {
        isCaptureFileAppenderEnabled = enableCaptureAppender
        val context = appContext ?: return
        initFileLogging(context, force = true)
    }

    private fun calculateNextMidnight(now: Long): Long =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

    /**
     * 优先 Files.LOG_DIR -> 失败则回退到 Context.external -> Context.files
     */
    private fun resolveLogDir(context: Context): String {
        // 1. 尝试使用 Files 类中定义的路径
        var targetDir = Files.LOG_DIR

        // 尝试创建目录，确保 exists() 判断准确
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        // 2. 检查是否有权写入
        if (!targetDir.exists() || !targetDir.canWrite()) {
            // 回退逻辑
            val fallbackDir = context.getExternalFilesDir("logs")
            targetDir = fallbackDir ?: File(context.filesDir, "logs")
        }

        // 3. 确保目录结构完整 (创建 bak 子目录)
        File(targetDir, "bak").mkdirs()

        return targetDir.absolutePath + File.separator
    }

    private fun resolveLogFileName(logName: String): String {
        if (logName == LogChannel.CAPTURE.loggerName) {
            return LogChannel.CAPTURE.fileName
        }
        val suffix = processLogSuffix ?: return "$logName.log"
        return "$logName-$suffix.log"
    }

    private fun addFileAppender(
        lc: LoggerContext,
        logName: String,
        logDir: String,
        fileName: String,
    ) {
        val isCaptureAppender = logName == LogChannel.CAPTURE.loggerName
        val usesRollingFileAppender = isRollingOwnerProcess && !isCaptureAppender
        val logger = lc.getLogger(logName)
        listOf("FILE-$logName", "APPEND-$fileName", "ASYNC-$logName").forEach { appenderName ->
            logger.getAppender(appenderName)?.let { existing ->
                logger.detachAppender(existing)
                existing.stop()
            }
        }
        val fileAppender: FileAppender<ILoggingEvent> = if (usesRollingFileAppender) {
            RollingFileAppender<ILoggingEvent>()
        } else {
            FileAppender<ILoggingEvent>()
        }

        fileAppender.apply {
            context = lc
            name = if (usesRollingFileAppender) "FILE-$logName" else "APPEND-$fileName"
            file = "$logDir$fileName"
            isAppend = true
            if (!usesRollingFileAppender) {
                // FileAppender prudent mode uses an OS file lock for each write across Android processes.
                isPrudent = true
            }
        }

        if (usesRollingFileAppender) {
            val rollingAppender = fileAppender as RollingFileAppender<ILoggingEvent>
            val policy =
                SizeAndTimeBasedRollingPolicy<ILoggingEvent>().apply {
                    context = lc
                    fileNamePattern = "${logDir}bak/$logName-%d{yyyy-MM-dd}.%i.log"
                    setMaxFileSize(FileSize.valueOf("${DEFAULT_LOG_FILE_MAX_SIZE_MB}MB"))
                    setTotalSizeCap(resolveTotalSizeCap())
                    maxHistory = 3
                    isCleanHistoryOnStart = true
                    setParent(rollingAppender)
                    start()
                }
            rollingAppender.rollingPolicy = policy
        }

        fileAppender.apply {
            encoder =
                PatternLayoutEncoder().apply {
                    context = lc
                    pattern = if (isCaptureAppender) "%msg%n" else "%d{dd日 HH:mm:ss.SS} %msg%n"
                    start()
                }

            start()
        }

        logger.apply {
            level = Level.ALL
            isAdditive = true
            if (isCaptureAppender) {
                // Captured RPC traffic must remain one JSON event per physical line.
                addAppender(fileAppender)
            } else {
                val asyncAppender =
                    AsyncAppender().apply {
                        context = lc
                        name = "ASYNC-$logName"
                        queueSize = 512
                        discardingThreshold = 0
                        isNeverBlock = false
                        addAppender(fileAppender)
                        start()
                    }
                addAppender(asyncAppender)
            }
        }
    }


    private fun resolveTotalSizeCap(): FileSize {
        val sizeMb = (BaseModel.logTotalSizeCapMb.value ?: DEFAULT_LOG_TOTAL_SIZE_CAP_MB).coerceAtLeast(1)
        return FileSize.valueOf("${sizeMb}MB")
    }
}
