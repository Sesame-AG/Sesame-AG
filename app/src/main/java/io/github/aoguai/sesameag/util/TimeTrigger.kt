package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.task.ModelTask
import java.lang.Runnable
import java.util.Calendar
import kotlin.ConsistentCopyVisibility
import kotlin.math.max

private const val TIME_TRIGGER_TAG = "TimeTrigger"
private const val SECONDS_PER_DAY = 24 * 60 * 60

enum class TimeTriggerRuleType {
    CHECKPOINT,
    WINDOW
}

data class TimeTriggerRule(
    val token: String,
    val type: TimeTriggerRuleType,
    val startSecond: Int,
    val endSecond: Int = startSecond,
    val blocked: Boolean = false
)

@ConsistentCopyVisibility
data class TimeTriggerSpec internal constructor(
    val raw: String,
    val disabled: Boolean,
    val allowRules: List<TimeTriggerRule>,
    val blockRules: List<TimeTriggerRule>,
    internal val checkpointSeconds: List<Int>,
    internal val allowWindows: List<TimeWindow>,
    internal val blockWindows: List<TimeWindow>
) {
    companion object {
        @JvmStatic
        fun disabled(raw: String = "-1"): TimeTriggerSpec {
            return TimeTriggerSpec(
                raw = raw,
                disabled = true,
                allowRules = emptyList(),
                blockRules = emptyList(),
                checkpointSeconds = emptyList(),
                allowWindows = emptyList(),
                blockWindows = emptyList()
            )
        }
    }
}

data class TimeTriggerDecision(
    val allowNow: Boolean,
    val blockedNow: Boolean,
    val matchedSlotIndex: Int,
    val nextTriggerAt: Long?
)

data class TimeTriggerParseOptions(
    val allowCheckpoints: Boolean = true,
    val allowWindows: Boolean = true,
    val allowBlockedWindows: Boolean = true,
    val tag: String = TIME_TRIGGER_TAG
)

internal data class TimeWindow(
    val startSecond: Int,
    val endSecond: Int
) {
    fun contains(secondOfDay: Int): Boolean {
        return secondOfDay in startSecond until endSecond
    }
}

private data class ParsedRule(
    val normalized: String,
    val rule: TimeTriggerRule,
    val expandedWindows: List<TimeWindow>
)

private data class AllowedSegment(
    val startSecond: Int,
    val endSecond: Int,
    val slotIndex: Int? = null
) {
    fun contains(secondOfDay: Int): Boolean {
        return secondOfDay in startSecond until endSecond
    }
}

private data class ParsedTime(
    val secondOfDay: Int,
    val useSeconds: Boolean
)

object TimeTriggerParser {

    @JvmStatic
    fun normalize(
        raw: String?,
        options: TimeTriggerParseOptions = TimeTriggerParseOptions(),
        fallback: String? = null
    ): String {
        val normalized = normalizeInternal(raw, options)
        if (normalized != null) {
            return normalized
        }
        return normalizeInternal(fallback, options) ?: "-1"
    }

    @JvmStatic
    fun parse(
        raw: String?,
        options: TimeTriggerParseOptions = TimeTriggerParseOptions()
    ): TimeTriggerSpec {
        val normalized = normalize(raw, options)
        if (normalized == "-1") {
            return TimeTriggerSpec.disabled(normalized)
        }

        val allowRules = mutableListOf<TimeTriggerRule>()
        val blockRules = mutableListOf<TimeTriggerRule>()
        val checkpointSeconds = linkedSetOf<Int>()
        val allowWindows = mutableListOf<TimeWindow>()
        val blockWindows = mutableListOf<TimeWindow>()

        for (token in normalized.split(",")) {
            val parsed = parseToken(token, options, logInvalid = false) ?: continue
            if (parsed.rule.blocked) {
                blockRules += parsed.rule
                blockWindows += parsed.expandedWindows
            } else {
                allowRules += parsed.rule
                if (parsed.rule.type == TimeTriggerRuleType.CHECKPOINT) {
                    checkpointSeconds += parsed.rule.startSecond
                } else {
                    allowWindows += parsed.expandedWindows
                }
            }
        }

        return TimeTriggerSpec(
            raw = normalized,
            disabled = false,
            allowRules = allowRules,
            blockRules = blockRules,
            checkpointSeconds = checkpointSeconds.toList().sorted(),
            allowWindows = mergeWindows(allowWindows),
            blockWindows = mergeWindows(blockWindows)
        )
    }

    private fun normalizeInternal(
        raw: String?,
        options: TimeTriggerParseOptions
    ): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        if (trimmed == "-1") {
            return "-1"
        }

        val uniqueRules = linkedMapOf<String, ParsedRule>()
        for (token in trimmed.split(",")) {
            val parsed = parseToken(token, options, logInvalid = true) ?: continue
            uniqueRules[parsed.normalized] = parsed
        }

        if (uniqueRules.isEmpty()) {
            return null
        }

        return uniqueRules.values
            .sortedWith(
                compareBy<ParsedRule>(
                    { if (it.rule.blocked) 1 else 0 },
                    { it.rule.startSecond },
                    { if (it.rule.type == TimeTriggerRuleType.CHECKPOINT) 0 else 1 },
                    { it.rule.endSecond }
                )
            )
            .joinToString(",") { it.normalized }
    }

    private fun parseToken(
        rawToken: String,
        options: TimeTriggerParseOptions,
        logInvalid: Boolean
    ): ParsedRule? {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            return null
        }

        val blocked = token.startsWith("!")
        val content = if (blocked) token.substring(1) else token
        if (content.isBlank()) {
            return invalid(options, rawToken, "空规则", logInvalid)
        }

        return if (content.contains("-")) {
            parseWindowToken(rawToken, content, blocked, options, logInvalid)
        } else {
            parseCheckpointToken(rawToken, content, blocked, options, logInvalid)
        }
    }

    private fun parseCheckpointToken(
        rawToken: String,
        content: String,
        blocked: Boolean,
        options: TimeTriggerParseOptions,
        logInvalid: Boolean
    ): ParsedRule? {
        if (blocked) {
            return invalid(options, rawToken, "禁止规则只支持时间段", logInvalid)
        }
        if (!options.allowCheckpoints) {
            return invalid(options, rawToken, "当前字段不允许时间点规则", logInvalid)
        }

        val parsed = parseTimeToken(content, allowDayEnd = false) ?: return invalid(
            options,
            rawToken,
            "无法解析时间点",
            logInvalid
        )

        val normalized = formatTimeToken(parsed.secondOfDay, parsed.useSeconds)
        return ParsedRule(
            normalized = normalized,
            rule = TimeTriggerRule(
                token = normalized,
                type = TimeTriggerRuleType.CHECKPOINT,
                startSecond = parsed.secondOfDay
            ),
            expandedWindows = emptyList()
        )
    }

    private fun parseWindowToken(
        rawToken: String,
        content: String,
        blocked: Boolean,
        options: TimeTriggerParseOptions,
        logInvalid: Boolean
    ): ParsedRule? {
        val parts = content.split("-", limit = 2)
        if (parts.size != 2) {
            return invalid(options, rawToken, "时间段格式错误", logInvalid)
        }

        if (blocked && !options.allowBlockedWindows) {
            return invalid(options, rawToken, "当前字段不允许禁止时间段", logInvalid)
        }
        if (!blocked && !options.allowWindows) {
            return invalid(options, rawToken, "当前字段不允许允许时间段", logInvalid)
        }

        val start = parseTimeToken(parts[0], allowDayEnd = false) ?: return invalid(
            options,
            rawToken,
            "时间段开始时间无效",
            logInvalid
        )
        val end = parseTimeToken(parts[1], allowDayEnd = true) ?: return invalid(
            options,
            rawToken,
            "时间段结束时间无效",
            logInvalid
        )

        if (start.secondOfDay == end.secondOfDay) {
            return invalid(options, rawToken, "时间段长度不能为 0", logInvalid)
        }

        val useSeconds = start.useSeconds || end.useSeconds
        val normalized = buildString {
            if (blocked) {
                append('!')
            }
            append(formatTimeToken(start.secondOfDay, useSeconds))
            append('-')
            append(formatTimeToken(end.secondOfDay, useSeconds))
        }

        return ParsedRule(
            normalized = normalized,
            rule = TimeTriggerRule(
                token = normalized,
                type = TimeTriggerRuleType.WINDOW,
                startSecond = start.secondOfDay,
                endSecond = end.secondOfDay,
                blocked = blocked
            ),
            expandedWindows = expandWindow(start.secondOfDay, end.secondOfDay)
        )
    }

    private fun parseTimeToken(token: String, allowDayEnd: Boolean): ParsedTime? {
        val digits = token.trim().filter { it.isDigit() }
        if (digits.isEmpty()) {
            return null
        }

        val normalized = when (digits.length) {
            2 -> digits + "00"
            3 -> "0$digits"
            4, 6 -> digits
            else -> return null
        }

        if (allowDayEnd && (normalized == "2400" || normalized == "240000")) {
            return ParsedTime(SECONDS_PER_DAY, normalized.length == 6)
        }

        val hour = normalized.substring(0, 2).toIntOrNull() ?: return null
        val minute = normalized.substring(2, 4).toIntOrNull() ?: return null
        val second = if (normalized.length == 6) {
            normalized.substring(4, 6).toIntOrNull() ?: return null
        } else {
            0
        }

        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        return ParsedTime(hour * 3600 + minute * 60 + second, normalized.length == 6)
    }

    private fun formatTimeToken(secondOfDay: Int, useSeconds: Boolean): String {
        if (secondOfDay == SECONDS_PER_DAY) {
            return "2400"
        }
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60
        return if (useSeconds || second != 0) {
            String.format("%02d%02d%02d", hour, minute, second)
        } else {
            String.format("%02d%02d", hour, minute)
        }
    }

    @JvmStatic
    fun formatSecondOfDay(secondOfDay: Int, useSeconds: Boolean = false): String {
        return formatTimeToken(secondOfDay, useSeconds)
    }

    private fun expandWindow(startSecond: Int, endSecond: Int): List<TimeWindow> {
        if (endSecond > startSecond) {
            return listOf(TimeWindow(startSecond, endSecond))
        }
        return listOf(
            TimeWindow(0, endSecond),
            TimeWindow(startSecond, SECONDS_PER_DAY)
        )
    }

    private fun mergeWindows(windows: List<TimeWindow>): List<TimeWindow> {
        if (windows.isEmpty()) {
            return emptyList()
        }
        val sorted = windows.sortedBy { it.startSecond }
        val merged = mutableListOf<TimeWindow>()
        var current = sorted.first()
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.startSecond <= current.endSecond) {
                current = TimeWindow(current.startSecond, max(current.endSecond, next.endSecond))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun invalid(
        options: TimeTriggerParseOptions,
        token: String,
        reason: String,
        logInvalid: Boolean
    ): ParsedRule? {
        if (logInvalid) {
            Log.record(options.tag, "忽略时间触发规则[$token]：$reason")
        }
        return null
    }
}

object TimeTriggerEvaluator {

    @JvmStatic
    fun evaluateNow(
        spec: TimeTriggerSpec,
        now: Long = System.currentTimeMillis(),
        consumedIndex: Int = 0
    ): TimeTriggerDecision {
        if (spec.disabled) {
            return TimeTriggerDecision(
                allowNow = false,
                blockedNow = false,
                matchedSlotIndex = -1,
                nextTriggerAt = null
            )
        }

        val secondOfDay = getSecondOfDay(now)
        val checkpointSegments = buildCheckpointSegments(spec, consumedIndex)
        val matchedCheckpoint = checkpointSegments.firstOrNull { it.contains(secondOfDay) }
        val allowWindowNow = spec.allowWindows.any { it.contains(secondOfDay) }
        val rawAllowNow = matchedCheckpoint != null || allowWindowNow
        val blockedNow = rawAllowNow && spec.blockWindows.any { it.contains(secondOfDay) }
        val allowNow = rawAllowNow && !blockedNow
        val nextTriggerAt = if (allowNow) {
            null
        } else {
            findNextTriggerAt(spec, now, secondOfDay, consumedIndex)
        }

        return TimeTriggerDecision(
            allowNow = allowNow,
            blockedNow = blockedNow,
            matchedSlotIndex = matchedCheckpoint?.slotIndex ?: -1,
            nextTriggerAt = nextTriggerAt
        )
    }

    @JvmStatic
    fun nextTriggerAt(
        spec: TimeTriggerSpec,
        now: Long = System.currentTimeMillis(),
        consumedIndex: Int = 0
    ): Long? {
        return evaluateNow(spec, now, consumedIndex).nextTriggerAt
    }

    @JvmStatic
    fun nextCheckpointTodayAt(
        spec: TimeTriggerSpec,
        now: Long = System.currentTimeMillis(),
        includeNow: Boolean = false
    ): Long? {
        if (spec.disabled || spec.checkpointSeconds.isEmpty()) {
            return null
        }
        val secondOfDay = getSecondOfDay(now)
        val nextSecond = spec.checkpointSeconds.firstOrNull {
            if (includeNow) {
                it >= secondOfDay
            } else {
                it > secondOfDay
            }
        } ?: return null
        return toTodayTimeMillis(now, nextSecond)
    }

    @JvmStatic
    fun nextCheckpointAt(
        spec: TimeTriggerSpec,
        now: Long = System.currentTimeMillis(),
        includeNow: Boolean = false
    ): Long? {
        if (spec.disabled || spec.checkpointSeconds.isEmpty()) {
            return null
        }
        val todayTarget = nextCheckpointTodayAt(spec, now, includeNow)
        if (todayTarget != null) {
            return todayTarget
        }
        val firstSecond = spec.checkpointSeconds.firstOrNull() ?: return null
        return toTodayTimeMillis(now, firstSecond) + SECONDS_PER_DAY * 1000L
    }

    @JvmStatic
    fun scheduleNext(
        modelTask: ModelTask,
        taskKey: String,
        spec: TimeTriggerSpec,
        consumedIndex: Int = 0,
        runnable: Runnable
    ): Boolean {
        val targetTime = nextTriggerAt(spec, System.currentTimeMillis(), consumedIndex) ?: return false
        if (targetTime <= System.currentTimeMillis()) {
            return false
        }

        val childTaskId = "TIME_TRIGGER|$taskKey|$targetTime"
        if (modelTask.hasChildTask(childTaskId)) {
            return false
        }

        modelTask.addChildTask(
            ModelTask.ChildModelTask(childTaskId, "TIME_TRIGGER", runnable, targetTime)
        )
        Log.record(
            TIME_TRIGGER_TAG,
            "已为[${modelTask.getName() ?: taskKey}]添加时间触发子任务[$taskKey]，执行时间=${TimeUtil.getCommonDate(targetTime)}"
        )
        return true
    }

    private fun findNextTriggerAt(
        spec: TimeTriggerSpec,
        now: Long,
        secondOfDay: Int,
        consumedIndex: Int
    ): Long? {
        val segments = mutableListOf<AllowedSegment>()
        segments += buildCheckpointSegments(spec, consumedIndex)
        segments += spec.allowWindows.map { AllowedSegment(it.startSecond, it.endSecond) }
        if (segments.isEmpty()) {
            return null
        }

        val sortedSegments = segments.sortedWith(compareBy({ it.startSecond }, { it.endSecond }))
        val candidateSecond = findFirstAllowedSecond(sortedSegments, spec.blockWindows, secondOfDay)
            ?: return null

        return toTodayTimeMillis(now, candidateSecond)
    }

    private fun buildCheckpointSegments(
        spec: TimeTriggerSpec,
        consumedIndex: Int
    ): List<AllowedSegment> {
        if (spec.checkpointSeconds.isEmpty()) {
            return emptyList()
        }

        val startIndex = consumedIndex.coerceAtLeast(0)
        if (startIndex >= spec.checkpointSeconds.size) {
            return emptyList()
        }

        val segments = ArrayList<AllowedSegment>(spec.checkpointSeconds.size - startIndex)
        for (index in startIndex until spec.checkpointSeconds.size) {
            val startSecond = spec.checkpointSeconds[index]
            val endSecond = if (index == spec.checkpointSeconds.lastIndex) {
                SECONDS_PER_DAY
            } else {
                spec.checkpointSeconds[index + 1]
            }
            if (startSecond < endSecond) {
                segments += AllowedSegment(startSecond, endSecond, index)
            }
        }
        return segments
    }

    private fun findFirstAllowedSecond(
        segments: List<AllowedSegment>,
        blockWindows: List<TimeWindow>,
        secondOfDay: Int
    ): Int? {
        for (segment in segments) {
            var candidate = max(secondOfDay, segment.startSecond)
            if (candidate >= segment.endSecond) {
                continue
            }

            while (candidate < segment.endSecond) {
                val blockingWindow = blockWindows.firstOrNull { it.contains(candidate) }
                if (blockingWindow == null) {
                    return candidate
                }
                candidate = blockingWindow.endSecond
            }
        }
        return null
    }

    private fun getSecondOfDay(timeMillis: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 +
            calendar.get(Calendar.SECOND)
    }

    private fun toTodayTimeMillis(now: Long, secondOfDay: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis + secondOfDay * 1000L
    }
}
