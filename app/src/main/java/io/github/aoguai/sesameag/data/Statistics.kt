package io.github.aoguai.sesameag.data

import io.github.aoguai.sesameag.storage.StoreAccess

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.util.Calendar

/**
 * 能量统计（按 年/月/日 维度统计：收取/帮助/浇水）
 *
 * 说明：
 * - 该实现参考了早期衍生版本中的同名能力，但为兼容多账号，这里将统计文件存储到用户目录下：
 *   `config/{uid}/statistics.json`
 * - 统计对象常驻内存；在 AntForest 任务开始时 load，在结束时 save
 */
@JsonIgnoreProperties(ignoreUnknown = true)
object Statistics {

    @JsonIgnoreProperties(ignoreUnknown = true)
    class TimeStatistics() {
        var time: Int = 0
        var collected: Int = 0
        var helped: Int = 0
        var watered: Int = 0

        constructor(time: Int) : this() {
            reset(time)
        }

        fun reset(time: Int) {
            this.time = time
            collected = 0
            helped = 0
            watered = 0
        }
    }

    enum class TimeType { YEAR, MONTH, DAY }

    enum class DataType { TIME, COLLECTED, HELPED, WATERED }

    private const val TAG = "Statistics"

    @JsonIgnore
    @Volatile
    private var loadedUserId: String? = null

    @JvmField
    var year: TimeStatistics = TimeStatistics()

    @JvmField
    var month: TimeStatistics = TimeStatistics()

    @JvmField
    var day: TimeStatistics = TimeStatistics()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Snapshot(
        var year: TimeStatistics? = null,
        var month: TimeStatistics? = null,
        var day: TimeStatistics? = null
    )

    private fun snapshot(): Snapshot = Snapshot(year = year, month = month, day = day)

    private fun applySnapshot(snapshot: Snapshot) {
        year = snapshot.year ?: TimeStatistics()
        month = snapshot.month ?: TimeStatistics()
        day = snapshot.day ?: TimeStatistics()
    }

    private fun getStatisticsFile(userId: String): File? {
        return Files.getTargetFileofUser(userId, "statistics.json")
    }

    private fun ensureLoaded(userId: String?): Boolean {
        if (userId.isNullOrEmpty()) {
            Log.error(TAG, "Invalid userId, skip statistics operation")
            return false
        }
        if (userId != loadedUserId) {
            load(userId)
        }
        return true
    }

    /**
     * 增加指定数据类型的统计量
     *
     * @param userId 用户ID
     * @param dt     数据类型（收集、帮助、浇水）
     * @param i      增加的数量（g）
     */
    @JvmStatic
    fun addData(userId: String?, dt: DataType, i: Int): Unit = StoreAccess.transaction {
        if (i == 0) return@transaction
        if (!ensureLoaded(userId)) return@transaction

        when (dt) {
            DataType.COLLECTED -> {
                day.collected += i
                month.collected += i
                year.collected += i
            }
            DataType.HELPED -> {
                day.helped += i
                month.helped += i
                year.helped += i
            }
            DataType.WATERED -> {
                day.watered += i
                month.watered += i
                year.watered += i
            }
            DataType.TIME -> Unit
        }
    }

    /**
     * 获取指定时间和数据类型的统计值
     */
    @JvmStatic
    fun getData(userId: String?, tt: TimeType, dt: DataType): Int {
        if (!ensureLoaded(userId)) return 0

        val ts = when (tt) {
            TimeType.YEAR -> year
            TimeType.MONTH -> month
            TimeType.DAY -> day
        }

        return when (dt) {
            DataType.TIME -> ts.time
            DataType.COLLECTED -> ts.collected
            DataType.HELPED -> ts.helped
            DataType.WATERED -> ts.watered
        }
    }

    /**
     * 获取统计文本信息
     */
    @JvmStatic
    fun getText(userId: String?): String {
        if (!ensureLoaded(userId)) return ""
        return "今年  收: " + getData(userId, TimeType.YEAR, DataType.COLLECTED) +
            " 帮: " + getData(userId, TimeType.YEAR, DataType.HELPED) +
            " 浇: " + getData(userId, TimeType.YEAR, DataType.WATERED) +
            "\n今月  收: " + getData(userId, TimeType.MONTH, DataType.COLLECTED) +
            " 帮: " + getData(userId, TimeType.MONTH, DataType.HELPED) +
            " 浇: " + getData(userId, TimeType.MONTH, DataType.WATERED) +
            "\n今日  收: " + getData(userId, TimeType.DAY, DataType.COLLECTED) +
            " 帮: " + getData(userId, TimeType.DAY, DataType.HELPED) +
            " 浇: " + getData(userId, TimeType.DAY, DataType.WATERED)
    }

    /**
     * 加载统计数据
     */
    @JvmStatic
    fun load(userId: String?): Statistics = StoreAccess.transaction {
        loadedUserId = userId
        if (userId.isNullOrEmpty()) {
            resetToDefault()
            return@transaction this
        }

        try {
            val statisticsFile = getStatisticsFile(userId)
            if (statisticsFile == null) {
                resetToDefault()
                return@transaction this
            }

            if (statisticsFile.exists() && statisticsFile.length() > 0) {
                val json = Files.readFromFile(statisticsFile)
                if (json.isNotBlank()) {
                    try {
                        val parsed = JsonUtil.parseObject(json, Snapshot::class.java)
                        applySnapshot(parsed)
                        validateAndInitialize()

                        val formatted = JsonUtil.formatJson(snapshot())
                        if (formatted != json) {
                            Log.record(TAG, "重新格式化 statistics.json")
                            Files.write2File(formatted, statisticsFile)
                        }
                    } catch (e: Exception) {
                        Log.printStackTrace(TAG, "statistics.json 加载失败，保留原文件", e)
                        throw e
                    }
                } else {
                    resetToDefault()
                }
            } else {
                resetToDefault()
            }
        } catch (t: Throwable) {
            unload()
            Log.printStackTrace(TAG, "统计加载失败，保留原文件", t)
            throw t
        }

        return@transaction this
    }

    /**
     * 验证并初始化统计数据
     */
    private fun validateAndInitialize() {
        val now = Calendar.getInstance()
        if (year.time == 0) year = TimeStatistics(now.get(Calendar.YEAR))
        if (month.time == 0) month = TimeStatistics(now.get(Calendar.MONTH) + 1)
        if (day.time == 0) day = TimeStatistics(now.get(Calendar.DAY_OF_MONTH))
        updateDay(now)
    }

    /**
     * 重置统计数据为默认值
     */
    private fun resetToDefault() {
        try {
            val now = Calendar.getInstance()
            year = TimeStatistics(now.get(Calendar.YEAR))
            month = TimeStatistics(now.get(Calendar.MONTH) + 1)
            day = TimeStatistics(now.get(Calendar.DAY_OF_MONTH))

            val uid = loadedUserId
            if (!uid.isNullOrEmpty()) {
                val statisticsFile = getStatisticsFile(uid)
                if (statisticsFile != null) {
                    Files.write2File(JsonUtil.formatJson(snapshot()), statisticsFile)
                }
            }

            Log.record(TAG, "已重置为默认值")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "resetToDefault Exception", e)
            throw e
        }
    }

    /**
     * 卸载当前统计数据（仅清空内存，不删除文件）
     */
    @JvmStatic
    fun unload(): Unit = StoreAccess.transaction {
        loadedUserId = null
        year = TimeStatistics()
        month = TimeStatistics()
        day = TimeStatistics()
    }

    /**
     * 保存统计数据
     */
    @JvmStatic
    fun save(userId: String?): Unit = StoreAccess.transaction {
        save(userId, Calendar.getInstance())
    }

    /**
     * 保存统计数据并更新日期
     */
    @JvmStatic
    fun save(userId: String?, nowDate: Calendar): Unit = StoreAccess.transaction {
        if (!ensureLoaded(userId)) return@transaction
        val uid = userId ?: return@transaction

        val reset = updateDay(nowDate)
        if (reset) {
            Log.record(TAG, "重置 statistics.json")
        } else {
            Log.record(TAG, "保存 statistics.json")
        }

        val statisticsFile = getStatisticsFile(uid)
        if (statisticsFile != null) {
            Files.write2File(JsonUtil.formatJson(snapshot()), statisticsFile)
        }
    }

    /**
     * 更新日期并重置统计数据
     *
     * @return 如果日期已更改，返回 true；否则返回 false
     */
    @JvmStatic
    fun updateDay(nowDate: Calendar): Boolean {
        val currentYear = nowDate.get(Calendar.YEAR)
        val currentMonth = nowDate.get(Calendar.MONTH) + 1
        val currentDay = nowDate.get(Calendar.DAY_OF_MONTH)

        if (currentYear != year.time) {
            year.reset(currentYear)
            month.reset(currentMonth)
            day.reset(currentDay)
            return true
        }
        if (currentMonth != month.time) {
            month.reset(currentMonth)
            day.reset(currentDay)
            return true
        }
        if (currentDay != day.time) {
            day.reset(currentDay)
            return true
        }
        return false
    }
}


