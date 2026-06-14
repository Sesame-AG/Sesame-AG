package io.github.aoguai.sesameag.model

import android.app.TimePickerDialog
import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeWindowListModelField
import io.github.aoguai.sesameag.ui.widget.ListDialog
import io.github.aoguai.sesameag.util.SesameAgUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import java.lang.reflect.Field

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
            SimpleEntity("antStall", "新村"),
            SimpleEntity("antDodo", "神奇物种"),
            SimpleEntity("antFishPond", "福气鱼池"),
            SimpleEntity("antCooperate", "蚂蚁森林合种"),
            SimpleEntity("antSports", "运动"),
            SimpleEntity("antMember", "会员"),
            SimpleEntity("antSesameCredit", "芝麻信用"),
            SimpleEntity("EcoProtection", "生态保护"),
            SimpleEntity("greenFinance", "绿色经营"),
            SimpleEntity("reserve", "保护地"),
            SimpleEntity("other", "其他任务")
        )
    }

    private fun getUserDisplayNameList(): Pair<List<String>, List<String>> {
        val uids = SesameAgUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
        val displayNames = mutableListOf<String>()
        val validUids = mutableListOf<String>()
        val backupUid = UserMap.currentUid
        uids.forEach { uid ->
            try {
                UserMap.loadSelf(uid)
                val user = UserMap.get(uid)
                displayNames.add(user?.showName ?: uid)
                validUids.add(uid)
            } catch (e: Exception) {
                displayNames.add(uid)
                validUids.add(uid)
            }
        }
        if (!backupUid.isNullOrEmpty()) {
            UserMap.setCurrentUserId(backupUid)
            UserMap.loadSelf(backupUid)
        }
        return Pair(displayNames, validUids)
    }

    private fun resetToDefault() {
        onlyOnceDaily.setObjectValue(false)
        autoHandleOnceDaily.setObjectValue(false)
        autoHandleOnceDailyTimes.setObjectValue("-1")
        onlyOnceDailyList.setObjectValue(LinkedHashSet<String?>())
    }

    @JvmStatic
    fun save(userId: String) {
        if (userId.isEmpty()) return
        try {
            val file = Files.getCustomSetFile(userId) ?: return
            val data = mutableMapOf<String, Any?>()
            data[onlyOnceDaily.code] = onlyOnceDaily.value
            data[onlyOnceDailyList.code] = onlyOnceDailyList.value
            data[autoHandleOnceDaily.code] = autoHandleOnceDaily.value
            data[autoHandleOnceDailyTimes.code] = autoHandleOnceDailyTimes.value
            val json = JsonUtil.formatJson(data)
            Files.write2File(json, file)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "Failed to save custom settings", e)
        }
    }

    @JvmStatic
    fun load(userId: String) {
        if (userId.isEmpty()) return
        try {
            val file = Files.getCustomSetFile(userId) ?: return
            if (!file.exists()) {
                resetToDefault()
                return
            }
            val json = Files.readFromFile(file)
            if (json.isEmpty()) {
                resetToDefault()
                return
            }
            val data = JsonUtil.copyMapper().readValue(json, Map::class.java)
            if (data.containsKey(onlyOnceDaily.code)) onlyOnceDaily.setObjectValue(data[onlyOnceDaily.code])
            if (data.containsKey(onlyOnceDailyList.code)) onlyOnceDailyList.setObjectValue(data[onlyOnceDailyList.code])
            if (data.containsKey(autoHandleOnceDaily.code)) autoHandleOnceDaily.setObjectValue(data[autoHandleOnceDaily.code])
            if (data.containsKey(autoHandleOnceDailyTimes.code)) autoHandleOnceDailyTimes.setObjectValue(data[autoHandleOnceDailyTimes.code])
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "Failed to load custom settings", e)
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
            taskInfo.contains("新村") || taskInfo.contains("antStall") -> "antStall"
            taskInfo.contains("神奇物种") || taskInfo.contains("antDodo") -> "antDodo"
            taskInfo.contains("福气鱼池") || taskInfo.contains("antFishPond") -> "antFishPond"
            taskInfo.contains("运动") || taskInfo.contains("antSports") -> "antSports"
            taskInfo.contains("芝麻信用") || taskInfo.contains("antSesameCredit") -> "antSesameCredit"
            taskInfo.contains("会员") || taskInfo.contains("antMember") -> "antMember"
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
            if (enableLog) Log.record("当日单次运行模式生效: 今日已完成全量运行，已启用跳过黑名单任务")
        }

        return OnceDailyStatus(isEnabled, isFinished)
    }


    @JvmStatic
    fun showSingleRunMenu(context: Context, onRefresh: () -> Unit) {
        val (displayNames, userIds) = getUserDisplayNameList()
        if (userIds.isEmpty()) {
            ToastUtil.showToast(context, "未发现任何用户配置")
            return
        }
        AlertDialog.Builder(context)
            .setTitle("请选择操作目标账号")
            .setItems(displayNames.toTypedArray()) { _, which ->
                val selectedUid = userIds[which]
                val selectedShowName = displayNames[which]
                load(selectedUid)
                showAccountOps(context, selectedUid, selectedShowName, onRefresh)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class TimeWindowRow(
        var start: String,
        var end: String
    )

    private fun parseTimeWindowRows(configValue: String?): MutableList<TimeWindowRow> {
        val rows = mutableListOf<TimeWindowRow>()
        configValue.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.contains("-") }
            .forEach { token ->
                val parts = token.split("-", limit = 2)
                if (parts.size == 2) {
                    rows += TimeWindowRow(parts[0], parts[1])
                }
            }
        if (rows.isEmpty()) {
            rows += TimeWindowRow("0600", "0650")
        }
        return rows
    }

    private fun formatTimeToken(timeToken: String): String {
        val normalized = timeToken.filter { it.isDigit() }.padStart(4, '0')
        return "${normalized.substring(0, 2)}:${normalized.substring(2, 4)}"
    }

    private fun pickTimeToken(context: Context, initialToken: String, onPicked: (String) -> Unit) {
        val initialCalendar = TimeUtil.getTodayCalendarByTimeStr(initialToken)
            ?: TimeUtil.getTodayCalendarByTimeStr("0000")
            ?: return
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                onPicked(String.format("%02d%02d", hourOfDay, minute))
            },
            initialCalendar.get(java.util.Calendar.HOUR_OF_DAY),
            initialCalendar.get(java.util.Calendar.MINUTE),
            true
        ).show()
    }

    private fun showAutoHandleTimeWindowDialog(
        context: Context,
        uid: String,
        showName: String,
        onRefresh: () -> Unit
    ) {
        val rows = parseTimeWindowRows(autoHandleOnceDailyTimes.getConfigValue())
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scrollView = ScrollView(context).apply {
            addView(container)
        }

        fun renderRows() {
            container.removeAllViews()

            rows.forEachIndexed { index, row ->
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val startButton = Button(context).apply {
                    text = formatTimeToken(row.start)
                    setOnClickListener {
                        pickTimeToken(context, row.start) { picked ->
                            row.start = picked
                            renderRows()
                        }
                    }
                }
                val separator = TextView(context).apply {
                    text = "  至  "
                }
                val endButton = Button(context).apply {
                    text = formatTimeToken(row.end)
                    setOnClickListener {
                        pickTimeToken(context, row.end) { picked ->
                            row.end = picked
                            renderRows()
                        }
                    }
                }
                val removeButton = Button(context).apply {
                    text = "删除"
                    isEnabled = rows.size > 1
                    setOnClickListener {
                        if (rows.size > 1) {
                            rows.removeAt(index)
                            renderRows()
                        }
                    }
                }
                rowLayout.addView(startButton)
                rowLayout.addView(separator)
                rowLayout.addView(endButton)
                rowLayout.addView(removeButton)
                container.addView(rowLayout)
            }

            container.addView(Button(context).apply {
                text = "新增时间段"
                setOnClickListener {
                    rows += TimeWindowRow("0000", "0030")
                    renderRows()
                }
            })

            container.addView(TextView(context).apply {
                text = "时间段采用开始包含、结束不包含；自动全量模式开启后，命中这些时间段时会临时放开单次运行限制。"
            })
        }

        renderRows()

        AlertDialog.Builder(context)
            .setTitle("设置 ${showName} 非单次运行时段")
            .setView(scrollView)
            .setPositiveButton(R.string.ok) { _, _ ->
                val serialized = rows
                    .map { "${it.start}-${it.end}" }
                    .joinToString(",")
                if (serialized.isBlank()) {
                    autoHandleOnceDailyTimes.reset()
                } else {
                    autoHandleOnceDailyTimes.setConfigValue(serialized)
                }
                save(uid)
                onRefresh()
                showAccountOps(context, uid, showName, onRefresh)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAccountOps(context: Context, uid: String, showName: String, onRefresh: () -> Unit) {
        val isFinished = try {
            Status.hasFlagToday(StatusFlags.FLAG_ONCE_DAILY_FINISHED)
        } catch (e: Throwable) {
            false
        }
        val statusText = when {
            onlyOnceDaily.value != true -> "单次运行：已关闭"
            autoHandleOnceDaily.value == true -> "单次运行：自动模式"
            isFinished -> "单次运行：今日已完成"
            else -> "单次运行：已开启"
        }
        val ops = arrayOf(statusText, "设置黑名单模块", "设置非单次运行的时段")
        AlertDialog.Builder(context)
            .setTitle("账号：$showName")
            .setItems(ops) { _, which ->
                if (which == 0) {
                    val currentOnlyOnce = onlyOnceDaily.value == true
                    val currentAuto = autoHandleOnceDaily.value == true
                    if (!currentOnlyOnce) {
                        onlyOnceDaily.value = true
                        autoHandleOnceDaily.value = false
                    } else if (!currentAuto) {
                        autoHandleOnceDaily.value = true
                    } else {
                        onlyOnceDaily.value = false
                        autoHandleOnceDaily.value = false
                    }
                    save(uid)
                    onRefresh()
                    showAccountOps(context, uid, showName, onRefresh)
                } else if (which == 1) {
                    ListDialog.show(context, "黑名单 | $showName", onlyOnceDailyList)
                    try {
                        val dialogField: Field = ListDialog::class.java.getDeclaredField("listDialog")
                        dialogField.isAccessible = true
                        val dialog = dialogField.get(null) as? androidx.appcompat.app.AlertDialog
                        dialog?.setOnDismissListener { save(uid) }
                    } catch (e: Exception) {
                    }
                } else if (which == 2) {
                    showAutoHandleTimeWindowDialog(context, uid, showName, onRefresh)
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }
}

private class SimpleEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}


