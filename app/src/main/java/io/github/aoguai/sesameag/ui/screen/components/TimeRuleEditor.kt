package io.github.aoguai.sesameag.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.model.modelFieldExt.TimeFieldMeta
import io.github.aoguai.sesameag.util.TimeTriggerParseOptions
import io.github.aoguai.sesameag.util.TimeTriggerParser

private data class TimeRuleDraft(
    val blocked: Boolean,
    val start: String,
    val end: String? = null,
)

private data class PickerRequest(
    val title: String,
    val token: String,
    val allowDayEnd: Boolean,
    val allowSeconds: Boolean,
    val onConfirm: (String) -> Unit,
    val hourOnly: Boolean = false,
)

private data class PickerTime(val hour: Int, val minute: Int, val second: Int, val dayEnd: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRuleEditor(
    type: String,
    value: String,
    meta: TimeFieldMeta?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolvedMeta = meta ?: TimeFieldMeta()
    var disabled by remember(type, value) { mutableStateOf(meta?.allowDisable == true && value == "-1") }
    var rules by remember(type, value) { mutableStateOf(parseTimeRules(value)) }
    var pickerRequest by remember { mutableStateOf<PickerRequest?>(null) }

    fun publish(nextDisabled: Boolean, nextRules: List<TimeRuleDraft>) {
        disabled = nextDisabled
        rules = nextRules
        onValueChange(serializeTimeRules(type, nextDisabled, nextRules, resolvedMeta))
    }

    fun enableWithDefaults() {
        if (rules.isNotEmpty()) {
            publish(false, rules)
            return
        }
        val token = defaultTimeToken(resolvedMeta)
        val hasWindowSection = type == "TIME_WINDOW_LIST" ||
            type == "TIME_TRIGGER" && (resolvedMeta.allowWindows || resolvedMeta.allowBlockedWindows)
        val window = TimeRuleDraft(
            blocked = type == "TIME_TRIGGER" && !resolvedMeta.allowCheckpoints &&
                !resolvedMeta.allowWindows && resolvedMeta.allowBlockedWindows,
            start = token,
            end = if (hasWindowSection) {
                defaultEndToken(resolvedMeta)
            } else {
                null
            },
        )
        publish(false, listOf(window))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (resolvedMeta.allowDisable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = !disabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            if (enabled) enableWithDefaults() else publish(true, rules)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (disabled) "时间规则已关闭" else "时间规则已启用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (disabled) "开启后才会参与任务执行" else "修改后将在保存时校验并写入配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !disabled, onCheckedChange = null)
            }
        }

        if (disabled) {
            Text("当前未启用时间规则", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            when (type) {
                "HOUR_OF_DAY" -> {
                    val token = rules.firstOrNull()?.start
                        ?: value.takeUnless { it == "-1" }?.ifBlank { "0000" }
                        ?: "0000"
                    TimeValueButton("整点时刻", token) {
                        pickerRequest = PickerRequest("整点时刻", token, resolvedMeta.allowDayEnd, false, onConfirm = { next ->
                            publish(false, listOf(TimeRuleDraft(false, next)))
                        }, hourOnly = true)
                    }
                }
                "TIME_POINT" -> {
                    val token = rules.firstOrNull()?.start ?: defaultTimeToken(resolvedMeta)
                    TimeValueButton("执行时间", token, resolvedMeta.allowSeconds) {
                        pickerRequest = PickerRequest(
                            "执行时间",
                            token,
                            false,
                            resolvedMeta.allowSeconds,
                            onConfirm = { next ->
                                publish(false, listOf(TimeRuleDraft(false, next)))
                            },
                        )
                    }
                }
                "TIME_POINT_LIST" -> {
                    TimeRuleList(
                        title = "时间点",
                        emptyText = "暂无时间点",
                        rules = rules.withIndex().filter { it.value.end == null },
                        allowSeconds = resolvedMeta.allowSeconds,
                        onEdit = { index, next ->
                            publish(false, rules.toMutableList().also { it[index] = it[index].copy(start = next) })
                        },
                        onDelete = { index -> publish(false, rules.filterIndexed { item, _ -> item != index }) },
                        onAdd = { publish(false, rules + TimeRuleDraft(false, defaultTimeToken(resolvedMeta))) },
                    ) { pickerRequest = it }
                }
                "TIME_WINDOW_LIST" -> {
                    TimeWindowList(
                        title = "时间段",
                        emptyText = "暂无时间段",
                        rules = rules.withIndex().filter { it.value.end != null },
                        allowSeconds = resolvedMeta.allowSeconds,
                        allowBlocked = false,
                        onEdit = { index, next -> publish(false, rules.toMutableList().also { it[index] = next }) },
                        onDelete = { index -> publish(false, rules.filterIndexed { item, _ -> item != index }) },
                        onAdd = { publish(false, rules + TimeRuleDraft(false, defaultTimeToken(resolvedMeta), defaultEndToken(resolvedMeta))) },
                    ) { pickerRequest = it }
                }
                "TIME_TRIGGER" -> {
                    if (resolvedMeta.allowCheckpoints) {
                        TimeRuleList(
                            title = "时间点",
                            emptyText = "暂无时间点",
                            rules = rules.withIndex().filter { !it.value.blocked && it.value.end == null },
                            allowSeconds = resolvedMeta.allowSeconds,
                            onEdit = { index, next -> publish(false, rules.toMutableList().also { it[index] = it[index].copy(start = next) }) },
                            onDelete = { index -> publish(false, rules.filterIndexed { item, _ -> item != index }) },
                            onAdd = { publish(false, rules + TimeRuleDraft(false, defaultTimeToken(resolvedMeta))) },
                        ) { pickerRequest = it }
                    }
                    if (resolvedMeta.allowWindows) {
                        TimeWindowList(
                            title = "允许时间段",
                            emptyText = "暂无允许时间段",
                            rules = rules.withIndex().filter { !it.value.blocked && it.value.end != null },
                            allowSeconds = resolvedMeta.allowSeconds,
                            allowBlocked = false,
                            onEdit = { index, next -> publish(false, rules.toMutableList().also { it[index] = next }) },
                            onDelete = { index -> publish(false, rules.filterIndexed { item, _ -> item != index }) },
                            onAdd = { publish(false, rules + TimeRuleDraft(false, defaultTimeToken(resolvedMeta), defaultEndToken(resolvedMeta))) },
                        ) { pickerRequest = it }
                    }
                    if (resolvedMeta.allowBlockedWindows) {
                        TimeWindowList(
                            title = "禁止时间段",
                            emptyText = "暂无禁止时间段",
                            rules = rules.withIndex().filter { it.value.blocked && it.value.end != null },
                            allowSeconds = resolvedMeta.allowSeconds,
                            allowBlocked = true,
                            onEdit = { index, next -> publish(false, rules.toMutableList().also { it[index] = next.copy(blocked = true) }) },
                            onDelete = { index -> publish(false, rules.filterIndexed { item, _ -> item != index }) },
                            onAdd = { publish(false, rules + TimeRuleDraft(true, defaultTimeToken(resolvedMeta), defaultEndToken(resolvedMeta))) },
                        ) { pickerRequest = it }
                    }
                }
                else -> Unit
            }
            if (rules.any { it.end != null && sameTime(it.start, it.end.orEmpty()) }) {
                Text("开始时间和结束时间不能相同", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    pickerRequest?.let { request ->
        MaterialTimePickerDialog(
            request = request,
            onDismiss = { pickerRequest = null },
            onConfirm = { token ->
                pickerRequest = null
                request.onConfirm(token)
            },
        )
    }
}

@Composable
private fun TimeValueButton(
    label: String,
    token: String,
    allowSeconds: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("$label：${formatTimeToken(token, allowSeconds)}")
    }
}

@Composable
private fun TimeRuleList(
    title: String,
    emptyText: String,
    rules: List<IndexedValue<TimeRuleDraft>>,
    allowSeconds: Boolean,
    onEdit: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onRequest: (PickerRequest) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    TimeRuleSectionHeader(
        title = title,
        count = rules.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return
    if (rules.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        rules.forEachIndexed { position, indexed ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        onRequest(
                            PickerRequest(
                                "$title ${position + 1}",
                                indexed.value.start,
                                false,
                                allowSeconds,
                                onConfirm = { next -> onEdit(indexed.index, next) },
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(formatTimeToken(indexed.value.start, allowSeconds))
                }
                IconButton(onClick = { onDelete(indexed.index) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除$title")
                }
            }
        }
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Text("添加$title")
    }
}

@Composable
private fun TimeWindowList(
    title: String,
    emptyText: String,
    rules: List<IndexedValue<TimeRuleDraft>>,
    allowSeconds: Boolean,
    allowBlocked: Boolean,
    onEdit: (Int, TimeRuleDraft) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onRequest: (PickerRequest) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    TimeRuleSectionHeader(
        title = title,
        count = rules.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return
    if (rules.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        rules.forEachIndexed { position, indexed ->
            val rule = indexed.value
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onRequest(
                                    PickerRequest(
                                        "$title ${position + 1} 开始",
                                        rule.start,
                                        false,
                                        allowSeconds,
                                        onConfirm = { next ->
                                            onEdit(indexed.index, rule.copy(start = next))
                                        },
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(formatTimeToken(rule.start, allowSeconds)) }
                        OutlinedButton(
                            onClick = {
                                onRequest(
                                    PickerRequest(
                                        "$title ${position + 1} 结束",
                                        rule.end.orEmpty(),
                                        true,
                                        allowSeconds,
                                        onConfirm = { next ->
                                            onEdit(indexed.index, rule.copy(end = next))
                                        },
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(formatTimeToken(rule.end.orEmpty(), allowSeconds)) }
                    }
                    if (allowBlocked) {
                        Text(
                            "禁止规则",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = { onDelete(indexed.index) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除$title")
                }
            }
        }
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Text("添加$title")
    }
}

@Composable
private fun TimeRuleSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title（$count）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "折叠$title" else "展开$title",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialTimePickerDialog(
    request: PickerRequest,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = remember(request.token) { parsePickerTime(request.token) }
    val state = rememberTimePickerState(
        initialHour = initial.hour.coerceIn(0, 23),
        initialMinute = initial.minute,
        is24Hour = true,
    )
    var seconds by remember(request.token) { mutableStateOf(initial.second.toString().padStart(2, '0')) }
    var dayEnd by remember(request.token) { mutableStateOf(initial.dayEnd && request.allowDayEnd) }
    val secondsValid = !request.allowSeconds || seconds.toIntOrNull()?.let { it in 0..59 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.allowDayEnd) {
                    FilterChip(
                        selected = dayEnd,
                        onClick = { dayEnd = !dayEnd },
                        label = { Text("结束时间为 24:00") },
                    )
                }
                if (!dayEnd) {
                    TimePicker(state = state)
                    if (request.allowSeconds) {
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { next -> seconds = next.filter(Char::isDigit).take(2) },
                            label = { Text("秒") },
                            singleLine = true,
                            isError = !secondsValid,
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                onClick = {
                    val second = seconds.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onConfirm(
                        if (dayEnd) {
                            if (request.allowSeconds) "240000" else "2400"
                        } else {
                            val minute = if (request.hourOnly) 0 else state.minute
                            val base = String.format("%02d%02d", state.hour, minute)
                            if (request.allowSeconds) "$base${second.toString().padStart(2, '0')}" else base
                        },
                    )
                },
                enabled = secondsValid,
            ) { Text("确定") }
        },
    )
}

private fun parseTimeRules(value: String): List<TimeRuleDraft> {
    if (value.isBlank() || value == "-1") return emptyList()
    return value.split(',').mapNotNull { raw ->
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return@mapNotNull null
        val blocked = trimmed.startsWith('!')
        val clean = trimmed.removePrefix("!")
        val parts = clean.split('-', limit = 2)
        val start = normalizeTimeToken(parts[0], allowDayEnd = false) ?: return@mapNotNull null
        val end = parts.getOrNull(1)?.let { normalizeTimeToken(it, allowDayEnd = true) }
        TimeRuleDraft(blocked, start, end)
    }
}

private fun serializeTimeRules(type: String, disabled: Boolean, rules: List<TimeRuleDraft>, meta: TimeFieldMeta): String {
    if (disabled && meta.allowDisable) return "-1"
    if (type == "HOUR_OF_DAY") return rules.firstOrNull()?.start ?: "0000"
    val raw = rules.joinToString(",") { rule ->
        buildString {
            if (rule.blocked) append('!')
            append(rule.start)
            rule.end?.let { append('-').append(it) }
        }
    }
    if (raw.isBlank()) return if (meta.allowDisable) "-1" else raw
    val normalized = TimeTriggerParser.normalize(
        raw,
        TimeTriggerParseOptions(
            allowCheckpoints = meta.allowCheckpoints,
            allowWindows = meta.allowWindows,
            allowBlockedWindows = meta.allowBlockedWindows,
        ),
        null,
    )
    return if (normalized == "-1") raw else normalized
}

private fun defaultTimeToken(meta: TimeFieldMeta): String = if (meta.allowSeconds) "000000" else "0000"

private fun defaultEndToken(meta: TimeFieldMeta): String = if (meta.allowSeconds) "003000" else "0030"

private fun normalizeTimeToken(raw: String, allowDayEnd: Boolean): String? {
    val digits = raw.filter(Char::isDigit)
    val normalized = when (digits.length) {
        1, 2 -> digits.padStart(2, '0') + "00"
        3 -> "0$digits"
        4, 6 -> digits
        else -> return null
    }
    if (allowDayEnd && (normalized == "2400" || normalized == "240000")) return normalized
    val hour = normalized.take(2).toIntOrNull() ?: return null
    val minute = normalized.substring(2, 4).toIntOrNull() ?: return null
    val second = normalized.substring(4).toIntOrNull() ?: 0
    return if (hour in 0..23 && minute in 0..59 && second in 0..59) normalized else null
}

private fun formatTimeToken(raw: String, allowSeconds: Boolean): String {
    val digits = raw.filter(Char::isDigit)
    if (digits == "2400" || digits == "240000") return "24:00"
    val normalized = if (allowSeconds) {
        digits.padStart(6, '0')
    } else {
        digits.padStart(4, '0').take(4)
    }
    return if (allowSeconds) {
        "${normalized.take(2)}:${normalized.substring(2, 4)}:${normalized.takeLast(2)}"
    } else {
        "${normalized.take(2)}:${normalized.takeLast(2)}"
    }
}

private fun parsePickerTime(raw: String): PickerTime {
    val digits = normalizeTimeToken(raw, allowDayEnd = true) ?: "0000"
    if (digits == "2400" || digits == "240000") return PickerTime(24, 0, 0, true)
    val withSeconds = if (digits.length == 6) digits else digits + "00"
    return PickerTime(
        hour = withSeconds.take(2).toIntOrNull() ?: 0,
        minute = withSeconds.substring(2, 4).toIntOrNull() ?: 0,
        second = withSeconds.substring(4, 6).toIntOrNull() ?: 0,
        dayEnd = false,
    )
}

private fun sameTime(first: String, second: String): Boolean = timeSecond(first) == timeSecond(second)

private fun timeSecond(raw: String): Int? {
    val normalized = normalizeTimeToken(raw, allowDayEnd = true) ?: return null
    if (normalized == "2400" || normalized == "240000") return 24 * 60 * 60
    val digits = normalized.padEnd(6, '0')
    val hour = digits.take(2).toIntOrNull() ?: return null
    val minute = digits.substring(2, 4).toIntOrNull() ?: return null
    val second = digits.substring(4, 6).toIntOrNull() ?: return null
    return hour * 60 * 60 + minute * 60 + second
}
