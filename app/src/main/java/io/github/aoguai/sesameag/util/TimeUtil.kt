package io.github.aoguai.sesameag.util

import android.annotation.SuppressLint
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 时间工具类。提供了一系列方法来处理时间相关的操作，包括时间范围检查、时间比较、日期格式化等。
 *
 * **迁移说明**:
 * - 保持所有方法的Java兼容性 (@JvmStatic)
 * - 使用Kotlin的null安全特性改进异常处理
 * - 未来版本将提供更多Kotlin风格的扩展函数
 */
object TimeUtil {

    private const val NANOS_PER_MILLISECOND = 1_000_000L

    // ==================== 时间范围检查 ====================

    @JvmStatic
    fun checkNowInTimeRange(timeRange: String): Boolean {
        return checkInTimeRange(System.currentTimeMillis(), timeRange)
    }

    @JvmStatic
    fun checkInTimeRange(timeMillis: Long, timeRangeList: List<String>): Boolean {
        return timeRangeList.any { checkInTimeRange(timeMillis, it) }
    }

    @JvmStatic
    fun checkInTimeRange(timeMillis: Long, timeRange: String): Boolean {
        return try {
            val timeRangeArray = timeRange.split("-")
            if (timeRangeArray.size == 2) {
                val min = timeRangeArray[0]
                val max = timeRangeArray[1]
                // Include both start and end boundaries
                isAfterOrCompareTimeStr(timeMillis, min) && isBeforeOrCompareTimeStr(timeMillis, max)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
            false
        }
    }

    // ==================== 时间字符串比较 ====================

    @JvmStatic
    fun isNowBeforeTimeStr(beforeTimeStr: String): Boolean {
        return isBeforeTimeStr(System.currentTimeMillis(), beforeTimeStr)
    }

    @JvmStatic
    fun isNowAfterTimeStr(afterTimeStr: String): Boolean {
        return isAfterTimeStr(System.currentTimeMillis(), afterTimeStr)
    }

    @JvmStatic
    fun isNowBeforeOrCompareTimeStr(beforeTimeStr: String): Boolean {
        return isBeforeOrCompareTimeStr(System.currentTimeMillis(), beforeTimeStr)
    }

    @JvmStatic
    fun isNowAfterOrCompareTimeStr(afterTimeStr: String): Boolean {
        return isAfterOrCompareTimeStr(System.currentTimeMillis(), afterTimeStr)
    }

    @JvmStatic
    fun isBeforeTimeStr(timeMillis: Long, beforeTimeStr: String): Boolean {
        val compared = isCompareTimeStr(timeMillis, beforeTimeStr)
        return compared != null && compared < 0
    }

    @JvmStatic
    fun isAfterTimeStr(timeMillis: Long, afterTimeStr: String): Boolean {
        val compared = isCompareTimeStr(timeMillis, afterTimeStr)
        return compared != null && compared > 0
    }

    @JvmStatic
    fun isBeforeOrCompareTimeStr(timeMillis: Long, beforeTimeStr: String): Boolean {
        val compared = isCompareTimeStr(timeMillis, beforeTimeStr)
        // Handle the case when times are equal (should return true for <= comparison)
        return when (compared) {
            null -> false
            else -> compared <= 0
        }
    }

    @JvmStatic
    fun isAfterOrCompareTimeStr(timeMillis: Long, afterTimeStr: String): Boolean {
        val compared = isCompareTimeStr(timeMillis, afterTimeStr)
        // Handle the case when times are equal (should return true for >= comparison)
        return when (compared) {
            null -> false
            else -> compared >= 0
        }
    }

    @JvmStatic
    fun isCompareTimeStr(timeMillis: Long, compareTimeStr: String): Int? {
        return try {
            val timeCalendar = getCalendarByTimeMillis(timeMillis) ?: return null
            val compareCalendar = getTodayCalendarByTimeStr(compareTimeStr) ?: return null
            
            // Compare only the time part (hours, minutes, seconds)
            val timeOfDay1 = timeCalendar.get(Calendar.HOUR_OF_DAY) * 3600 + 
                           timeCalendar.get(Calendar.MINUTE) * 60 + 
                           timeCalendar.get(Calendar.SECOND)
                           
            val timeOfDay2 = compareCalendar.get(Calendar.HOUR_OF_DAY) * 3600 + 
                           compareCalendar.get(Calendar.MINUTE) * 60 + 
                           compareCalendar.get(Calendar.SECOND)
                           
            timeOfDay1.compareTo(timeOfDay2)
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    // ==================== Calendar 工具方法 ====================

    @JvmStatic
    fun getTodayCalendarByTimeStr(timeStr: String?): Calendar? {
        return timeStr?.let { getCalendarByTimeStr(null, it) }
    }

    @JvmStatic
    fun getCalendarByTimeStr(timeMillis: Long?, timeStr: String?): Calendar? {
        return try {
            timeStr?.let {
                val timeCalendar = getCalendarByTimeMillis(timeMillis)
                getCalendarByTimeStr(timeCalendar, it)
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    @JvmStatic
    fun getCalendarByTimeStr(timeCalendar: Calendar, timeStr: String?): Calendar? {
        return try {
            if (timeStr == null) return null
            
            // Create a new calendar to avoid modifying the input calendar
            val result = timeCalendar.clone() as Calendar
            
            when (timeStr.length) {
                6 -> {
                    // Format: HHmmss
                    result.set(Calendar.HOUR_OF_DAY, timeStr.substring(0, 2).toInt())
                    result.set(Calendar.MINUTE, timeStr.substring(2, 4).toInt())
                    result.set(Calendar.SECOND, timeStr.substring(4).toInt())
                }
                4 -> {
                    // Format: HHmm
                    result.set(Calendar.HOUR_OF_DAY, timeStr.substring(0, 2).toInt())
                    result.set(Calendar.MINUTE, timeStr.substring(2, 4).toInt())
                    result.set(Calendar.SECOND, 0)
                }
                2 -> {
                    // Format: HH
                    result.set(Calendar.HOUR_OF_DAY, timeStr.substring(0, 2).toInt())
                    result.set(Calendar.MINUTE, 0)
                    result.set(Calendar.SECOND, 0)
                }
                else -> return null
            }
            result.set(Calendar.MILLISECOND, 0)
            result
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    @JvmStatic
    fun getCalendarByTimeMillis(timeMillis: Long?): Calendar {
        return Calendar.getInstance().apply {
            timeMillis?.let { this.timeInMillis = it }
        }
    }

    @JvmStatic
    fun getToday(): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /**
     * 通用时间差格式化（自动区分过去/未来）
     *
     * @param diffMillis 时间差（毫秒），可为正/负
     * @return 易读字符串，如 "刚刚", "5分钟后", "3天前"
     */
    @JvmStatic
    fun formatDuration(diffMillis: Long): String {
        val absSeconds = abs(diffMillis) / 1000

        val (value, unit) = when {
            absSeconds < 60 -> absSeconds to "秒"
            absSeconds < 3600 -> (absSeconds / 60) to "分钟"
            absSeconds < 86400 -> (absSeconds / 3600) to "小时"
            absSeconds < 2592000 -> (absSeconds / 86400) to "天"
            absSeconds < 31536000 -> (absSeconds / 2592000) to "个月"
            else -> (absSeconds / 31536000) to "年"
        }

        return when {
            absSeconds < 1 -> "刚刚"
            diffMillis > 0 -> "${value}${unit}后"
            else -> "${value}${unit}前"
        }
    }

    @JvmStatic
    fun getNow(): Calendar = Calendar.getInstance()

    // ==================== 时间格式化 ====================

    /**
     * 获取当前时间的字符串表示
     * @param ts 时间戳
     * @return "下午8:00:00"（东八区）或 "8:00:00 PM"（英语环境）
     */
    @JvmStatic
    fun getTimeStr(ts: Long): String {
        return DateFormat.getTimeInstance().format(Date(ts))
    }

    /**
     * 获取当前时间的字符串表示
     * @return "下午8:00:00"（东八区）或 "8:00:00 PM"（英语环境）
     */
    @JvmStatic
    fun getTimeStr(): String = getTimeStr(System.currentTimeMillis())

    /**
     * 获取当前日期的字符串表示
     * @return 格式：yyyy年*M月*d日
     */
    @JvmStatic
    fun getDateStr(): String = getDateStr(0)

    /**
     * 获取日期的字符串表示
     * @param plusDay 日期偏移量
     * @return 格式：yyyy年*M月*d日
     */
    @JvmStatic
    fun getDateStr(plusDay: Int): String {
        val c = Calendar.getInstance()
        if (plusDay != 0) {
            c.add(Calendar.DATE, plusDay)
        }
        return DateFormat.getDateInstance().format(c.time)
    }

    /**
     * 获取今天日期
     * @return yyyy-MM-dd
     */
    @JvmStatic
    fun getDateStr2(): String = getDateStr2(0)

    /**
     * 获取日期字符串
     * @param plusDay 日期偏移量
     * @return yyyy-MM-dd
     */
    @JvmStatic
    fun getDateStr2(plusDay: Int): String {
        val c = Calendar.getInstance()
        if (plusDay != 0) {
            c.add(Calendar.DATE, plusDay)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(c.time)
    }

    @SuppressLint("SimpleDateFormat")
    @JvmStatic
    fun getCommonDateFormat(): DateFormat {
        return SimpleDateFormat("dd日HH:mm:ss")
    }

    @JvmStatic
    fun getCommonDate(timestamp: Long): String {
        return getCommonDateFormat().format(timestamp)
    }

    // ==================== 格式化相关 ====================

    @JvmField
    val DATE_TIME_FORMAT_THREAD_LOCAL: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        }
    }

    @JvmField
    val OTHER_DATE_TIME_FORMAT_THREAD_LOCAL: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())
        }
    }

    @JvmStatic
    fun timeToStamp(timers: String): Long {
        return try {
            val simpleDateFormat = OTHER_DATE_TIME_FORMAT_THREAD_LOCAL.get()
                ?: SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())
            val newD = simpleDateFormat.parse(timers)
            newD?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * 获取格式化的日期时间字符串 yyyy-MM-dd HH:mm:ss
     */
    @JvmStatic
    fun getFormatDateTime(): String {
        val simpleDateFormat = DATE_TIME_FORMAT_THREAD_LOCAL.get()
            ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return simpleDateFormat.format(Date())
    }

    /**
     * 获取格式化的日期字符串 yyyy-MM-dd
     */
    @JvmStatic
    fun getFormatDate(): String {
        return getFormatDateTime().split(" ")[0]
    }

    /**
     * 获取格式化的时间字符串 HH:mm:ss
     */
    @JvmStatic
    fun getFormatTime(): String {
        return getFormatDateTime().split(" ")[1]
    }

    /**
     * 根据传入的格式化字符串获取格式化后的时间字符串
     * @param offset 日期偏移量
     * @param format 格式化字符串
     * @return 格式化后的时间字符串
     */
    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun getFormatTime(offset: Int, format: String): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, offset)
        val sdf = SimpleDateFormat(format)
        return sdf.format(calendar.time)
    }

    @JvmStatic
    fun getFormatTime(timestamp: Long, format: String): String {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }


    // ==================== 日期比较 ====================

    /**
     * 获取指定时间的周数
     * @param dateTime 时间
     * @return 当前年的第几周
     */
    @JvmStatic
    fun getWeekNumber(dateTime: Date): Int {
        val calendar = Calendar.getInstance()
        calendar.time = dateTime
        calendar.firstDayOfWeek = Calendar.MONDAY
        return calendar.get(Calendar.WEEK_OF_YEAR)
    }

    /**
     * 比较第一个日历的天数小于第二个日历的天数
     */
    @JvmStatic
    fun isLessThanSecondOfDays(firstCalendar: Calendar, secondCalendar: Calendar): Boolean {
        return (firstCalendar.get(Calendar.YEAR) < secondCalendar.get(Calendar.YEAR)) ||
                (firstCalendar.get(Calendar.YEAR) == secondCalendar.get(Calendar.YEAR) &&
                        firstCalendar.get(Calendar.DAY_OF_YEAR) < secondCalendar.get(Calendar.DAY_OF_YEAR))
    }

    /**
     * 比较第一个时间戳的天数是否小于第二个时间戳的天数
     */
    @JvmStatic
    fun isLessThanSecondOfDays(firstTimestamp: Long, secondTimestamp: Long): Boolean {
        val firstCalendar = getCalendarByTimeMillis(firstTimestamp)
        val secondCalendar = getCalendarByTimeMillis(secondTimestamp)
        return isLessThanSecondOfDays(firstCalendar, secondCalendar)
    }

    /**
     * 通过时间戳比较传入的时间戳的天数是否小于当前时间戳的天数
     */
    @JvmStatic
    fun isLessThanNowOfDays(timestamp: Long): Boolean {
        return isLessThanSecondOfDays(getCalendarByTimeMillis(timestamp), getNow())
    }

    /**
     * 判断两个日历对象是否为同一天
     */
    @JvmStatic
    fun isSameDay(firstCalendar: Calendar, secondCalendar: Calendar): Boolean {
        return firstCalendar.get(Calendar.YEAR) == secondCalendar.get(Calendar.YEAR) &&
                firstCalendar.get(Calendar.DAY_OF_YEAR) == secondCalendar.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 判断两个时间戳是否为同一天
     */
    @JvmStatic
    fun isSameDay(firstTimestamp: Long, secondTimestamp: Long): Boolean {
        val firstCalendar = getCalendarByTimeMillis(firstTimestamp)
        val secondCalendar = getCalendarByTimeMillis(secondTimestamp)
        return isSameDay(firstCalendar, secondCalendar)
    }

    /**
     * 判断日历对象是否为今天
     */
    @JvmStatic
    fun isToday(calendar: Calendar): Boolean {
        return isSameDay(getToday(), calendar)
    }

    /**
     * 判断时间戳是否为今天
     */
    @JvmStatic
    fun isToday(timestamp: Long): Boolean {
        return isToday(getCalendarByTimeMillis(timestamp))
    }

    // ==================== 协程兼容方法 ====================

    /**
     * 协程兼容的延迟方法
     */
    @JvmStatic
    fun sleepCompat(millis: Long) {
        if (millis <= 0) return
        java.util.concurrent.locks.LockSupport.parkNanos(millis * NANOS_PER_MILLISECOND)
    }
}

