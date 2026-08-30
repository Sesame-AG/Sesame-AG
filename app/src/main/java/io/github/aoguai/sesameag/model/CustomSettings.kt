package io.github.aoguai.sesameag.model

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeWindowListModelField
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.LinkedHashSet

/**
 * 自定义设置管理类
 * 负责“每日单次运行”功能的逻辑封装、配置持久化及 UI 交互。
 */
object CustomSettings {
    private const val TAG = "CustomSettings"

    val onlyOnceDaily = BooleanModelField("onlyOnceDaily", "选中每日只运行一次的模块", false)
    val autoHandleOnceDaily = BooleanModelField("autoHandleOnceDaily", "定时自动关闭单次运行", false)

    val autoHandleOnceDailyTimes = TimeWindowListModelField(
        "autoHandleOnceDailyTimes",
        "自动全量时间段",
        "-1",
        allowDisable = true
    )

    val onlyOnceDailyList = SelectModelField(
        "onlyOnceDailyList",
        "每日只运行一次 | 模块选择",
        LinkedHashSet<String?>(),
        getModuleList()
    )

    private fun getModuleList(): List<MapperEntity> {
        return listOf(
            SimpleEntity("antForest", "蚂蚁森林"),
            SimpleEntity("antFarm", "蚂蚁庄园"),
            SimpleEntity("antOcean", "海洋"),
            SimpleEntity("antOrchard", "农场"),
            SimpleEntity("goldenBeanTreasure", "金豆夺宝"),
            SimpleEntity("antStall", "新村"),
            SimpleEntity("antDodo", "神奇物种"),
            SimpleEntity("antFishPond", "福气鱼池"),
            SimpleEntity("antCooperate", "蚂蚁森林合种"),
            SimpleEntity("antSports", "运动"),
            SimpleEntity("antMember", "会员"),
            SimpleEntity("myBankWelfare", "网商银行"),
            SimpleEntity("antSesameCredit", "芝麻信用"),
            SimpleEntity("EcoProtection", "生态保护"),
            SimpleEntity("greenFinance", "绿色经营"),
            SimpleEntity("reserve", "保护地"),
            SimpleEntity("other", "其他任务")
        )
    }

    private fun resetToDefault() {
        onlyOnceDaily.setObjectValue(false)
        autoHandleOnceDaily.setObjectValue(false)
        autoHandleOnceDailyTimes.setObjectValue("-1")
        onlyOnceDailyList.setObjectValue(LinkedHashSet<String?>())
    }

    @JvmStatic
    fun save(userId: String) {
        trySave(userId)
    }

    fun trySave(userId: String): Boolean {
        if (userId.isEmpty()) return false
        return try {
            val file = Files.getCustomSetFile(userId) ?: return false
            val data = mutableMapOf<String, Any?>()
            data[onlyOnceDaily.code] = onlyOnceDaily.value
            data[onlyOnceDailyList.code] = onlyOnceDailyList.value
            data[autoHandleOnceDaily.code] = autoHandleOnceDaily.value
            data[autoHandleOnceDailyTimes.code] = autoHandleOnceDailyTimes.value
            val json = JsonUtil.formatJson(data)
            Files.write2File(json, file)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "Failed to save custom settings", e)
            false
        }
    }

    @JvmStatic
    fun load(userId: String) {
        if (userId.isEmpty()) return
        resetToDefault()
        try {
            val file = Files.getCustomSetFile(userId) ?: return
            if (!file.exists()) {
                return
            }
            val json = Files.readFromFile(file)
            if (json.isBlank()) {
                return
            }
            val data = JsonUtil.copyMapper().readValue(json, Map::class.java)
            applyLoadedValues(data)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "Failed to load custom settings, keeping defaults", e)
            Log.runtime(TAG, "自定义设置加载失败，已回退默认值:userId=$userId")
            Log.record(TAG, "自定义设置加载失败，已回退默认值:userId=$userId")
        }
    }

    private fun applyLoadedValues(data: Map<*, *>) {
        if (data.containsKey(onlyOnceDaily.code)) {
            onlyOnceDaily.setObjectValue(data[onlyOnceDaily.code])
        }
        if (data.containsKey(onlyOnceDailyList.code)) {
            onlyOnceDailyList.setObjectValue(data[onlyOnceDailyList.code])
        }
        if (data.containsKey(autoHandleOnceDaily.code)) {
            autoHandleOnceDaily.setObjectValue(data[autoHandleOnceDaily.code])
        }
        if (data.containsKey(autoHandleOnceDailyTimes.code)) {
            autoHandleOnceDailyTimes.setObjectValue(data[autoHandleOnceDailyTimes.code])
        }
    }

    fun loadForTaskRunner() {
        val currentUid = UserMap.currentUid
        if (!currentUid.isNullOrEmpty()) load(currentUid)
    }

    fun getModuleId(taskInfo: String?): String? {
        if (taskInfo == null) return null
        return when {
            taskInfo.contains("合种") || taskInfo.contains("antCooperate") -> "antCooperate"
            taskInfo.contains("蚂蚁森林") || taskInfo.contains("antForest") -> "antForest"
            taskInfo.contains("蚂蚁庄园") || taskInfo.contains("antFarm") -> "antFarm"
            taskInfo.contains("海洋") || taskInfo.contains("antOcean") -> "antOcean"
            taskInfo.contains("农场") || taskInfo.contains("antOrchard") -> "antOrchard"
            taskInfo == "金豆夺宝" || taskInfo == "goldenBeanTreasure" -> "goldenBeanTreasure"
            taskInfo.contains("新村") || taskInfo.contains("antStall") -> "antStall"
            taskInfo.contains("神奇物种") || taskInfo.contains("antDodo") -> "antDodo"
            taskInfo.contains("福气鱼池") || taskInfo.contains("antFishPond") -> "antFishPond"
            taskInfo.contains("运动") || taskInfo.contains("antSports") -> "antSports"
            taskInfo.contains("芝麻信用") || taskInfo.contains("antSesameCredit") -> "antSesameCredit"
            taskInfo.contains("会员") || taskInfo.contains("antMember") -> "antMember"
            taskInfo.contains("网商银行") || taskInfo.contains("网商福利金") || taskInfo.contains("MyBankWelfare") -> "myBankWelfare"
            taskInfo.contains("生态保护") || taskInfo.contains("EcoProtection") -> "EcoProtection"
            taskInfo.contains("绿色经营") || taskInfo.contains("greenFinance") -> "greenFinance"
            taskInfo.contains("保护地") || taskInfo.contains("reserve") -> "reserve"
            taskInfo.contains("其他任务") || taskInfo.contains("other") -> "other"
            else -> null
        }
    }

    fun isOnceDailyBlackListed(taskInfo: String?, status: OnceDailyStatus? = null): Boolean {
        val s = status ?: getOnceDailyStatus(false)
        // 只有当单次运行模式生效，且今日已经完成过首轮全量运行的情况下，才执行黑名单排除
        if (s.isEnabledOverride && s.isFinishedToday) {
            val moduleId = getModuleId(taskInfo)
            if (moduleId != null) {
                return onlyOnceDailyList.value?.contains(moduleId) == true
            }
        }
        return false
    }

    data class OnceDailyStatus(
        val isEnabledOverride: Boolean,
        val isFinishedToday: Boolean
    )

    @JvmStatic
    fun getOnceDailyStatus(enableLog: Boolean = false): OnceDailyStatus {
        val configEnabled = onlyOnceDaily.value == true
        val isFinished = try {
            Status.hasFlagToday(StatusFlags.FLAG_ONCE_DAILY_FINISHED)
        } catch (e: Throwable) {
            false
        }

        val now = System.currentTimeMillis()
        val isSpecialTime = !autoHandleOnceDailyTimes.isDisabled() && autoHandleOnceDailyTimes.isActive(now)

        var isEnabled = configEnabled

        if (isSpecialTime && autoHandleOnceDaily.value == true) {
            isEnabled = false
            if (enableLog) Log.record("自动单次运行触发: 现在处于自动全量运行时段，本次将运行所有已开启的任务")
        } else if (enableLog && autoHandleOnceDaily.value == true) {
            Log.record("已设置自动全量运行，时段为：${autoHandleOnceDailyTimes.value ?: ""}")
        }

        // 如果今日尚未完成首次全量运行，则不启用“跳过”拦截逻辑
        if (isEnabled && !isFinished) {
            isEnabled = false
            if (enableLog) Log.record("当日单次运行模式生效: 今日尚未完成首次全量运行，本次将运行所有任务")
        } else if (isEnabled) {
            if (enableLog) Log.record("当日单次运行模式生效: 今日已完成全量运行，将按已选模块跳过后续运行")
        }

        return OnceDailyStatus(isEnabled, isFinished)
    }

}

private class SimpleEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}


