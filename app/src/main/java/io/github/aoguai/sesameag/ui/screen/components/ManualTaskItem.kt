package io.github.aoguai.sesameag.ui.screen.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.task.customTasks.CustomTask

private val toolDisplayNameMap = mapOf(
    "BIG_EATER_TOOL" to "加饭卡",
    "NEWEGGTOOL" to "新蛋卡",
    "FENCETOOL" to "篱笆卡"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTaskItem(
    task: CustomTask,
    onClick: () -> Unit,
    hasSettings: Boolean = false,
    specialFoodCount: String = "0",
    onSpecialFoodCountChange: (String) -> Unit = {},
    selectedTool: String = "BIG_EATER_TOOL",
    onToolChange: (String) -> Unit = {},
    toolCount: String = "1",
    onToolCountChange: (String) -> Unit = {},
    exchangeEnergyRainCard: Boolean = false,
    onExchangeEnergyRainCardChange: (Boolean) -> Unit = {}
) {
    var expanded by rememberSaveable(task.name) { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "立即运行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hasSettings) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "任务设置",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "运行任务",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        AnimatedVisibility(visible = hasSettings && expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                if (task == CustomTask.FOREST_ENERGY_RAIN) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = exchangeEnergyRainCard,
                                    role = Role.Switch,
                                    onValueChange = onExchangeEnergyRainCardChange,
                                ),
                    ) {
                        Text(
                            text = "兑换使用能量雨卡",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = exchangeEnergyRainCard,
                            onCheckedChange = null,
                        )
                    }
                } else if (task == CustomTask.FARM_SPECIAL_FOOD) {
                    OutlinedTextField(
                        value = specialFoodCount,
                        onValueChange = { onSpecialFoodCountChange(it.filter { c -> c.isDigit() }) },
                        label = { Text("使用总次数 (必须大于0)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else if (task == CustomTask.FARM_USE_TOOL) {
                    val tools = toolDisplayNameMap.keys.toList()
                    var toolExpanded by rememberSaveable(task.name) { mutableStateOf(false) }

                    ExposedDropdownMenuBox(
                        expanded = toolExpanded,
                        onExpandedChange = { toolExpanded = !toolExpanded }
                    ) {
                        OutlinedTextField(
                            value = toolDisplayNameMap[selectedTool] ?: selectedTool,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择道具") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toolExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = toolExpanded,
                            onDismissRequest = { toolExpanded = false }
                        ) {
                            tools.forEach { tool ->
                                DropdownMenuItem(
                                    text = { Text(toolDisplayNameMap[tool] ?: tool) },
                                    onClick = {
                                        onToolChange(tool)
                                        toolExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedTool == "NEWEGGTOOL") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = toolCount,
                            onValueChange = { onToolCountChange(it.filter { c -> c.isDigit() }) },
                            label = { Text("使用数量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

