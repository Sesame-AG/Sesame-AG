package io.github.aoguai.sesameag.util

import android.content.Context
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.model.BaseModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 日志工具类，负责统一日志通道与模块分层。
 */
object Log {
    private const val MAX_DUPLICATE_ERRORS = 3

    private val errorCountMap = ConcurrentHashMap<String, AtomicInteger>()
    private val loggerMap: Map<LogChannel, Logger>

    private enum class Severity {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private data class TaggedModuleMessage(
        val channel: LogChannel,
        val message: String
    )

    init {
        Logback.initLogcatOnly()
        loggerMap = LogCatalog.channels.associateWith { LoggerFactory.getLogger(it.loggerName) }
    }

    @JvmStatic
    fun init(context: Context) {
        try {
            Logback.initFileLogging(context)
        } catch (e: Exception) {
            android.util.Log.e("SesameLog", "Log init failed", e)
        }
    }

    private fun getLogger(channel: LogChannel): Logger = loggerMap.getValue(channel)

    private fun formatTaggedMessage(tag: String, msg: String): String = "[$tag]: $msg"

    private fun recordTag(channel: LogChannel): String = channel.logTag ?: channel.moduleDomain.displayName

    private fun stripTagSeparator(value: String): String {
        return when {
            value.startsWith(": ") || value.startsWith("： ") -> value.substring(2)
            value.startsWith(":") || value.startsWith("：") -> value.substring(1).trimStart()
            else -> value.trimStart()
        }
    }

    private fun extractTaggedModuleMessage(msg: String): TaggedModuleMessage? {
        val trimmed = msg.trimStart()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 1) {
                val tag = trimmed.substring(1, end).trim()
                val channel = LogCatalog.findBySourceTagAlias(tag)
                if (channel != null) {
                    val body = stripTagSeparator(trimmed.substring(end + 1))
                    return TaggedModuleMessage(channel, body)
                }
            }
        }

        for (channel in LogCatalog.channels) {
            for (alias in LogCatalog.sourceTagAliases(channel).sortedByDescending { it.length }) {
                if (trimmed.length > alias.length &&
                    trimmed.regionMatches(0, alias, 0, alias.length, ignoreCase = true)
                ) {
                    val separator = trimmed[alias.length]
                    if (separator == ':' || separator == '：') {
                        return TaggedModuleMessage(
                            channel,
                            stripTagSeparator(trimmed.substring(alias.length))
                        )
                    }
                }
            }
        }
        return null
    }

    private fun recordMessageForChannel(channel: LogChannel, msg: String): String {
        return if (!channel.logTag.isNullOrEmpty()) {
            formatTaggedMessage(recordTag(channel), msg)
        } else {
            msg
        }
    }

    private fun copyTaggedMessageToModule(msg: String, severity: Severity): String {
        val taggedMessage = extractTaggedModuleMessage(msg) ?: return msg
        logRaw(taggedMessage.channel, severity, taggedMessage.message)
        return formatTaggedMessage(recordTag(taggedMessage.channel), taggedMessage.message)
    }

    private fun writeRecordOnly(severity: Severity, msg: String) {
        logRaw(LogChannel.RECORD, severity, copyTaggedMessageToModule(msg, severity))
    }

    private fun shouldWrite(channel: LogChannel): Boolean {
        return when (channel) {
            LogChannel.RECORD -> BaseModel.recordLog.value == true
            LogChannel.RUNTIME -> BaseModel.runtimeLog.value == true || BuildConfig.DEBUG
            else -> true
        }
    }

    private fun logRaw(channel: LogChannel, severity: Severity, msg: String) {
        Logback.refreshIfCrossDay()

        if (!shouldWrite(channel)) {
            return
        }
        val logger = getLogger(channel)
        when (severity) {
            Severity.DEBUG -> logger.debug("{}", msg)
            Severity.INFO -> logger.info("{}", msg)
            Severity.WARN -> logger.warn("{}", msg)
            Severity.ERROR -> logger.error("{}", msg)
        }
    }


    private fun write(channel: LogChannel, severity: Severity, msg: String, type: Int = 1) {
        if (channel.mirrorToRecord && type == 1) {
            val recordMsg = recordMessageForChannel(channel, msg)
            logRaw(LogChannel.RECORD, Severity.INFO, recordMsg)
        }
        logRaw(channel, severity, msg)
    }

    private fun business(channel: LogChannel, msg: String, type: Int = 1) {
        write(channel, Severity.INFO, msg, type)
    }

    @JvmStatic
    fun system(msg: String) {
        write(LogChannel.SYSTEM, Severity.INFO, msg)
    }

    @JvmStatic
    fun system(tag: String, msg: String) {
        system(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun runtime(msg: String) {
        write(LogChannel.RUNTIME, Severity.INFO, msg)
    }

    @JvmStatic
    fun runtime(tag: String, msg: String) {
        runtime(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun record(msg: String, type: Int = 1) {
        val taggedMessage = extractTaggedModuleMessage(msg)
        if (taggedMessage != null) {
            write(taggedMessage.channel, Severity.INFO, taggedMessage.message, type)
            return
        }

        val shouldRecord = if (type == 1) shouldWrite(LogChannel.RECORD) else false
        if (shouldRecord) {
            logRaw(LogChannel.RECORD, Severity.INFO, msg)
        }
    }

    @JvmStatic
    fun record(tag: String, msg: String, type: Int = 1) {
        record(formatTaggedMessage(tag, msg), type)
    }

    @JvmStatic
    fun summary(msg: String) {
        write(LogChannel.SUMMARY, Severity.INFO, msg)
    }

    @JvmStatic
    fun summary(tag: String, msg: String) {
        summary(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun common(msg: String) {
        business(LogChannel.COMMON, msg)
    }

    @JvmStatic
    fun common(tag: String, msg: String) {
        val channel = LogCatalog.findBySourceTagAlias(tag)
        if (channel != null) {
            business(channel, msg)
        } else {
            common(formatTaggedMessage(tag, msg))
        }
    }

    @JvmStatic
    @JvmOverloads
    fun forest(msg: String, type: Int = 1) {
        business(LogChannel.FOREST, msg, type)
    }

    @JvmStatic
    fun orchard(msg: String) {
        business(LogChannel.ORCHARD, msg)
    }

    @JvmStatic
    @JvmOverloads
    fun farm(msg: String, type: Int = 1) {
        business(LogChannel.FARM, msg, type)
    }

    @JvmStatic
    fun stall(msg: String) {
        business(LogChannel.STALL, msg)
    }

    @JvmStatic
    fun ocean(msg: String) {
        business(LogChannel.OCEAN, msg)
    }

    @JvmStatic
    fun dodo(msg: String) {
        business(LogChannel.DODO, msg)
    }

    @JvmStatic
    fun member(msg: String) {
        business(LogChannel.MEMBER, msg)
    }

    @JvmStatic
    fun fishpond(msg: String) {
        business(LogChannel.FISHPOND, msg)
    }

    @JvmStatic
    fun sports(msg: String) {
        business(LogChannel.SPORTS, msg)
    }

    @JvmStatic
    fun greenFinance(msg: String) {
        business(LogChannel.GREEN_FINANCE, msg)
    }

    @JvmStatic
    fun sesame(msg: String) {
        business(LogChannel.SESAME_CREDIT, msg)
    }

    @JvmStatic
    fun debug(msg: String) {
        write(LogChannel.DEBUG, Severity.DEBUG, msg)
    }

    @JvmStatic
    fun debug(tag: String, msg: String) {
        debug(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun error(msg: String) {
        write(LogChannel.ERROR, Severity.ERROR, copyTaggedMessageToModule(msg, Severity.ERROR))
    }

    @JvmStatic
    fun error(tag: String, msg: String) {
        error(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun capture(msg: String) {
        write(LogChannel.CAPTURE, Severity.INFO, msg)
    }

    @JvmStatic
    fun capture(tag: String, msg: String) {
        capture(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        debug(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        record(formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        writeRecordOnly(Severity.WARN, formatTaggedMessage(tag, msg))
    }

    @JvmStatic
    fun w(tag: String, msg: String, th: Throwable?) {
        val finalMsg = if (th == null) {
            formatTaggedMessage(tag, msg)
        } else {
            "${formatTaggedMessage(tag, msg)}\n${android.util.Log.getStackTraceString(th)}"
        }
        writeRecordOnly(Severity.WARN, finalMsg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, th: Throwable? = null) {
        val finalMsg = if (th == null) {
            formatTaggedMessage(tag, msg)
        } else {
            "${formatTaggedMessage(tag, msg)}\n${android.util.Log.getStackTraceString(th)}"
        }
        error(finalMsg)
    }

    private fun shouldSkipDuplicateError(th: Throwable?): Boolean {
        if (th == null) {
            return false
        }

        var errorSignature = th.javaClass.simpleName + ":" + (th.message?.take(50) ?: "null")
        if (th.message?.contains("End of input at character 0") == true) {
            errorSignature = "JSONException:EmptyResponse"
        }

        val count = errorCountMap.computeIfAbsent(errorSignature) { AtomicInteger(0) }
        val currentCount = count.incrementAndGet()

        if (currentCount == MAX_DUPLICATE_ERRORS) {
            summary("错误去重", "错误【$errorSignature】已出现${currentCount}次，后续不再打印详细堆栈")
            return false
        }

        return currentCount > MAX_DUPLICATE_ERRORS
    }

    private fun buildStackTraceMessage(tag: String? = null, msg: String? = null, th: Throwable): String {
        val header = when {
            !tag.isNullOrBlank() && !msg.isNullOrBlank() -> "[$tag] $msg"
            !tag.isNullOrBlank() -> "[$tag] Throwable error"
            !msg.isNullOrBlank() -> msg
            else -> "Throwable error"
        }
        return "$header\n${android.util.Log.getStackTraceString(th)}"
    }

    private fun shouldTreatContextAsTag(context: String): Boolean {
        if (context.isBlank()) {
            return false
        }
        if (context.any { it.isWhitespace() || it > '\u007F' }) {
            return false
        }
        return context.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '$' }
    }

    @JvmStatic
    fun printStackTrace(th: Throwable) {
        if (shouldSkipDuplicateError(th)) {
            return
        }
        error(buildStackTraceMessage(th = th))
    }

    @JvmStatic
    fun printStackTrace(context: String, th: Throwable) {
        if (shouldSkipDuplicateError(th)) {
            return
        }
        val message = if (shouldTreatContextAsTag(context)) {
            buildStackTraceMessage(tag = context, th = th)
        } else {
            buildStackTraceMessage(msg = context, th = th)
        }
        error(message)
    }

    @JvmStatic
    fun printStackTrace(tag: String, msg: String, th: Throwable) {
        if (shouldSkipDuplicateError(th)) {
            return
        }
        error(buildStackTraceMessage(tag = tag, msg = msg, th = th))
    }

    @JvmStatic
    fun printStack(tag: String) {
        val stackTrace = "stack: " + android.util.Log.getStackTraceString(Exception("获取当前堆栈$tag:"))
        debug(stackTrace)
    }
}
