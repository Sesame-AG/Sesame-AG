package io.github.aoguai.sesameag.model.modelFieldExt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeTriggerParseOptions
import io.github.aoguai.sesameag.util.TimeTriggerParser
import io.github.aoguai.sesameag.util.TimeTriggerSpec
import java.util.Calendar

data class TimeFieldMeta(
    val allowDisable: Boolean = false,
    val allowSeconds: Boolean = false,
    val allowBlockedWindows: Boolean = false,
    val allowDayEnd: Boolean = false,
    val allowCheckpoints: Boolean = false,
    val allowWindows: Boolean = false,
    val precision: String = "minute",
    val displayMode: String = "time"
)

object TimeFieldType {
    const val TIME_POINT = "TIME_POINT"
    const val TIME_POINT_LIST = "TIME_POINT_LIST"
    const val TIME_WINDOW_LIST = "TIME_WINDOW_LIST"
    const val TIME_TRIGGER = "TIME_TRIGGER"
    const val HOUR_OF_DAY = "HOUR_OF_DAY"
}

open class TimeRuleModelField(
    code: String,
    name: String,
    value: String,
    private val fieldType: String,
    private val meta: TimeFieldMeta,
    private val parseOptions: TimeTriggerParseOptions
) : StringModelField(code, name, value) {

    @Transient
    private var cachedRawValue: String? = null

    @Transient
    private var cachedSpec: TimeTriggerSpec? = null

    @Transient
    private var initialized = false

    init {
        initialized = true
        setObjectValue(value)
        defaultValue = this.value
    }

    override fun getType(): String = fieldType

    override fun getEditorMeta(): Any = meta

    protected open fun resolveFallback(): String {
        return if (meta.allowDisable) {
            "-1"
        } else {
            defaultValue ?: "-1"
        }
    }

    override fun normalizeValue(rawValue: String): String {
        if (!initialized) {
            return rawValue.trim()
        }
        return normalizeRawValue(rawValue)
    }

    protected open fun normalizeRawValue(rawValue: String): String {
        return TimeTriggerParser.normalize(rawValue, parseOptions, resolveFallback())
    }

    protected open fun parseSpec(rawValue: String): TimeTriggerSpec {
        return TimeTriggerParser.parse(rawValue, parseOptions)
    }

    override fun setObjectValue(objectValue: Any?) {
        super.setObjectValue(objectValue)
        if (!initialized) {
            cachedRawValue = null
            cachedSpec = null
            return
        }
        val rawValue = value ?: "-1"
        cachedRawValue = rawValue
        cachedSpec = parseSpec(rawValue)
    }

    @JsonIgnore
    fun getTriggerSpec(): TimeTriggerSpec {
        val rawValue = value ?: "-1"
        val cached = cachedSpec
        if (cached != null && cachedRawValue == rawValue) {
            return cached
        }
        return parseSpec(rawValue).also {
            cachedRawValue = rawValue
            cachedSpec = it
        }
    }

    @JsonIgnore
    fun isDisabled(): Boolean = getTriggerSpec().disabled
}

class TimePointModelField(
    code: String,
    name: String,
    value: String,
    allowDisable: Boolean = false,
    allowSeconds: Boolean = false
) : TimeRuleModelField(
    code = code,
    name = name,
    value = value,
    fieldType = TimeFieldType.TIME_POINT,
    meta = TimeFieldMeta(
        allowDisable = allowDisable,
        allowSeconds = allowSeconds,
        allowBlockedWindows = false,
        allowCheckpoints = true,
        allowWindows = false,
        precision = if (allowSeconds) "second" else "minute",
        displayMode = "single"
    ),
    parseOptions = TimeTriggerParseOptions(
        allowCheckpoints = true,
        allowWindows = false,
        allowBlockedWindows = false,
        tag = code
    )
) {
    override fun parseSpec(rawValue: String): TimeTriggerSpec {
        if (rawValue == "-1") {
            return TimeTriggerSpec.disabled(rawValue)
        }
        return TimeTriggerParser.parse(
            "${rawValue}-2400",
            TimeTriggerParseOptions(
                allowCheckpoints = false,
                allowWindows = true,
                allowBlockedWindows = false,
                tag = code
            )
        )
    }

    @JsonIgnore
    fun isReachedToday(now: Long = System.currentTimeMillis()): Boolean {
        return TimeTriggerEvaluator.evaluateNow(getTriggerSpec(), now).allowNow
    }

    @JsonIgnore
    fun getTodayPointAt(referenceTime: Long = System.currentTimeMillis()): Long? {
        val token = getPointToken() ?: return null
        val digits = token.filter { it.isDigit() }
        if (digits.length != 4 && digits.length != 6) {
            return null
        }

        val hour = digits.substring(0, 2).toIntOrNull() ?: return null
        val minute = digits.substring(2, 4).toIntOrNull() ?: return null
        val second = if (digits.length == 6) {
            digits.substring(4, 6).toIntOrNull() ?: return null
        } else {
            0
        }
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        return Calendar.getInstance().apply {
            timeInMillis = referenceTime
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @JsonIgnore
    fun getPointToken(): String? = value?.takeUnless { it == "-1" }
}

class TimePointListModelField(
    code: String,
    name: String,
    value: String,
    allowDisable: Boolean = true,
    allowSeconds: Boolean = false
) : TimeRuleModelField(
    code = code,
    name = name,
    value = value,
    fieldType = TimeFieldType.TIME_POINT_LIST,
    meta = TimeFieldMeta(
        allowDisable = allowDisable,
        allowSeconds = allowSeconds,
        allowBlockedWindows = false,
        allowCheckpoints = true,
        allowWindows = false,
        precision = if (allowSeconds) "second" else "minute",
        displayMode = "list"
    ),
    parseOptions = TimeTriggerParseOptions(
        allowCheckpoints = true,
        allowWindows = false,
        allowBlockedWindows = false,
        tag = code
    )
) {
    @JsonIgnore
    fun nextPointAt(now: Long = System.currentTimeMillis(), includeNow: Boolean = false): Long? {
        return TimeTriggerEvaluator.nextCheckpointAt(getTriggerSpec(), now, includeNow)
    }
}

class TimeWindowListModelField(
    code: String,
    name: String,
    value: String,
    allowDisable: Boolean = true,
    allowSeconds: Boolean = false
) : TimeRuleModelField(
    code = code,
    name = name,
    value = value,
    fieldType = TimeFieldType.TIME_WINDOW_LIST,
    meta = TimeFieldMeta(
        allowDisable = allowDisable,
        allowSeconds = allowSeconds,
        allowBlockedWindows = false,
        allowCheckpoints = false,
        allowWindows = true,
        precision = if (allowSeconds) "second" else "minute",
        displayMode = "range-list"
    ),
    parseOptions = TimeTriggerParseOptions(
        allowCheckpoints = false,
        allowWindows = true,
        allowBlockedWindows = false,
        tag = code
    )
) {
    @JsonIgnore
    fun isActive(now: Long = System.currentTimeMillis()): Boolean {
        return TimeTriggerEvaluator.evaluateNow(getTriggerSpec(), now).allowNow
    }
}

class HourOfDayModelField(
    code: String,
    name: String,
    value: String,
    allowDisable: Boolean = false,
    private val allowDayEnd: Boolean = false
) : TimeRuleModelField(
    code = code,
    name = name,
    value = value,
    fieldType = TimeFieldType.HOUR_OF_DAY,
    meta = TimeFieldMeta(
        allowDisable = allowDisable,
        allowSeconds = false,
        allowBlockedWindows = false,
        allowDayEnd = allowDayEnd,
        allowCheckpoints = true,
        allowWindows = false,
        precision = "hour",
        displayMode = "hour"
    ),
    parseOptions = TimeTriggerParseOptions(
        allowCheckpoints = true,
        allowWindows = false,
        allowBlockedWindows = false,
        tag = code
    )
) {
    override fun normalizeRawValue(rawValue: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            return resolveFallback()
        }
        if (trimmed == "-1" && getEditorMeta().let { it as TimeFieldMeta }.allowDisable) {
            return "-1"
        }

        val normalized = when {
            trimmed == "24" || trimmed == "2400" || trimmed == "24:00" -> {
                if (allowDayEnd) "2400" else resolveFallback()
            }
            else -> {
                val digits = trimmed.filter { it.isDigit() }
                when (digits.length) {
                    1, 2 -> digits.padStart(2, '0') + "00"
                    3 -> "0$digits".take(2) + "00"
                    4 -> {
                        val hour = digits.substring(0, 2).toIntOrNull()
                        val minute = digits.substring(2, 4).toIntOrNull()
                        if (hour == null || minute == null || minute != 0) {
                            resolveFallback()
                        } else {
                            digits
                        }
                    }
                    else -> resolveFallback()
                }
            }
        }

        if (normalized == "2400") {
            return if (allowDayEnd) normalized else resolveFallback()
        }

        val hour = normalized.substring(0, 2).toIntOrNull() ?: return resolveFallback()
        return if (hour in 0..23) {
            String.format("%02d00", hour)
        } else {
            resolveFallback()
        }
    }

    @JsonIgnore
    fun getHourToken(): String? = value?.takeUnless { it == "-1" }

    @JsonIgnore
    fun hasReachedToday(now: Long = System.currentTimeMillis()): Boolean {
        val token = getHourToken() ?: return false
        if (token == "2400") {
            return false
        }
        val spec = TimeTriggerParser.parse(
            "$token-2400",
            TimeTriggerParseOptions(
                allowCheckpoints = false,
                allowWindows = true,
                allowBlockedWindows = false,
                tag = code
            )
        )
        return TimeTriggerEvaluator.evaluateNow(spec, now).allowNow
    }

    @JsonIgnore
    fun isBeforeCutoff(now: Long = System.currentTimeMillis()): Boolean {
        val token = getHourToken() ?: return false
        if (token == "2400") {
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val secondOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
                calendar.get(Calendar.MINUTE) * 60 +
                calendar.get(Calendar.SECOND)
            // 24:00 是“日终截止”专用值，23:59 开始就应放行兜底处理。
            return secondOfDay < (23 * 3600 + 59 * 60)
        }
        val spec = TimeTriggerParser.parse(
            "0000-$token",
            TimeTriggerParseOptions(
                allowCheckpoints = false,
                allowWindows = true,
                allowBlockedWindows = false,
                tag = code
            )
        )
        return TimeTriggerEvaluator.evaluateNow(spec, now).allowNow
    }
}
