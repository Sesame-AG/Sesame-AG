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
import ch.qos.logback.core.OutputStreamAppender
import android.os.ParcelFileDescriptor
import io.github.aoguai.sesameag.storage.PrivateStorage
import io.github.aoguai.sesameag.storage.StoreAccess
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

    private val outputQueue = SerialTaskQueue()
    @Volatile
    private var isFileInitialized = false
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var nextMidnightMillis: Long = 0
    @Volatile
    private var isRollingOwnerProcess = false
    private var processLogSuffix: String? = null

    @Volatile
    var systemLogFile: File? = null
        private set

    // 捕获 Hook 在账户配置加载前已可能写入日志，首轮文件初始化必须直接创建 capture Appender。
    private var isCaptureFileAppenderEnabled = true

    /**
     * 初始化 Logcat (保证控制台一定有日志)
     * 在 Log 类的 init 块中自动调用
     */
    fun initLogcatOnly(): Unit = outputQueue.execute { initLogcatOnlyInternal() }

    private fun initLogcatOnlyInternal() {
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
    fun initFileLogging(context: Context, force: Boolean = false): Unit =
        outputQueue.execute { initFileLoggingInternal(context, force) }

    private fun initFileLoggingInternal(
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
            initLogcatOnlyInternal() // 内部执行 lc.reset()
        }

        try {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext
            if (processName != General.MODULE_PACKAGE_NAME) {
                (LogCatalog.loggerNames() + listOf("other", "captcha")).distinct().forEach { channel ->
                    if (channel != LogChannel.SYSTEM.loggerName &&
                        (channel != LogChannel.CAPTURE.loggerName || isCaptureFileAppenderEnabled)) {
                        addPipeAppender(lc, channel, processName)
                    }
                }
                isFileInitialized = true
                nextMidnightMillis = calculateNextMidnight(now)
                return
            }
            // Before migration only the module-private diagnostic log is available.
            if (processName == General.MODULE_PACKAGE_NAME) {
                val directory = File(context.filesDir, "logs/diagnostics")
                check(File(directory, "bak").isDirectory || File(directory, "bak").mkdirs())
                systemLogFile = File(directory, LogChannel.SYSTEM.fileName)
                addFileAppender(lc, LogChannel.SYSTEM.loggerName, directory.absolutePath + File.separator, LogChannel.SYSTEM.fileName)
            }
            if (!PrivateStorage.isReady) return
            isRollingOwnerProcess = true
            processLogSuffix = null
            val logDir = resolveLogDir(context)

            val fullTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val allLogNames = (LogCatalog.loggerNames() + listOf("other", "captcha")).distinct()

            allLogNames.forEach { logName ->
                if (logName == LogChannel.SYSTEM.loggerName) return@forEach
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
        if (!isRollingOwnerProcess && systemLogFile == null) return
        val now = System.currentTimeMillis()
        if (isFileInitialized && now >= nextMidnightMillis) {
            appContext?.let { initFileLogging(it) }
        }
    }

    fun reloadFileLogging(enableCaptureAppender: Boolean = isCaptureFileAppenderEnabled): Unit = outputQueue.execute {
        isCaptureFileAppenderEnabled = enableCaptureAppender
        val context = appContext ?: return@execute
        initFileLoggingInternal(context, force = true)
    }

    fun <T> withFileLoggingPaused(action: () -> T): T = outputQueue.execute {
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        val initialized = isFileInitialized
        lc.loggerList.filter { it.name != LogChannel.SYSTEM.loggerName && it.name != Logger.ROOT_LOGGER_NAME }
            .forEach { it.detachAndStopAllAppenders() }
        isFileInitialized = false
        try { action() } finally {
            if (initialized && PrivateStorage.isReady) appContext?.let { initFileLoggingInternal(it, force = true) }
        }
    }

    fun clearSystemLog(): Boolean = outputQueue.execute {
        val file = systemLogFile ?: return@execute false
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        lc.getLogger(LogChannel.SYSTEM.loggerName).detachAndStopAllAppenders()
        try {
            val children = file.parentFile?.listFiles() ?: return@execute false
            children.map { it.deleteRecursively() }.all { it }
        } finally {
            file.parentFile?.let { directory ->
                File(directory, "bak").mkdirs()
                addFileAppender(lc, LogChannel.SYSTEM.loggerName, directory.absolutePath + File.separator, file.name)
            }
        }
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
     * Business logs are written only to the module-owned private store.
     */
    private fun resolveLogDir(context: Context): String {
        PrivateStorage.requireReady()
        val targetDir = Files.LOG_DIR
        check(targetDir.isDirectory || targetDir.mkdirs())
        check(File(targetDir, "bak").isDirectory || File(targetDir, "bak").mkdirs())
        return targetDir.absolutePath + File.separator
    }

    fun remoteLogger(channel: String, suffix: String?): Logger = outputQueue.execute {
        PrivateStorage.requireReady()
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        val loggerName = if (suffix == null || channel == LogChannel.CAPTURE.loggerName) channel else "$channel-$suffix"
        val logger = lc.getLogger(loggerName)
        if (!logger.iteratorForAppenders().hasNext()) {
            addFileAppender(lc, loggerName, resolveLogDir(checkNotNull(appContext)), "$loggerName.log")
        }
        logger
    }

    private fun addPipeAppender(lc: LoggerContext, channel: String, processName: String) {
        val logger = lc.getLogger(channel)
        listOf("PIPE-$channel", "ASYNC-$channel").forEach { name ->
            logger.getAppender(name)?.let { logger.detachAppender(it); it.stop() }
        }
        val pipe = OutputStreamAppender<ILoggingEvent>().apply {
            context = lc
            name = "PIPE-$channel"
            encoder = PatternLayoutEncoder().apply {
                context = lc
                pattern = "%msg%n"
                start()
            }
            outputStream = ParcelFileDescriptor.AutoCloseOutputStream(CommandUtil.storageService().openLogStream(channel, processName))
            start()
        }
        val async = AsyncAppender().apply {
            context = lc
            name = "ASYNC-$channel"
            queueSize = 512
            discardingThreshold = 0
            isNeverBlock = true
            addAppender(pipe)
            start()
        }
        logger.level = Level.ALL
        logger.addAppender(async)
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
        val isSystemAppender = logName == LogChannel.SYSTEM.loggerName
        val usesRollingFileAppender = isSystemAppender || (isRollingOwnerProcess && !isCaptureAppender)
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
            if (isCaptureAppender || isSystemAppender) {
                // 诊断同步写入，保证初始化失败与用户清理操作不会丢失在异步队列中。
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
