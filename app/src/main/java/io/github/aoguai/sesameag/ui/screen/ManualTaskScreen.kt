package io.github.aoguai.sesameag.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.task.customTasks.CustomTask
import io.github.aoguai.sesameag.task.customTasks.ManualTaskModel
import io.github.aoguai.sesameag.ui.screen.components.ManualTaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTaskScreen(
    onBackClick: () -> Unit,
    onTaskClick: (CustomTask, Map<String, Any>) -> Unit
) {
    val tasks = CustomTask.entries.toTypedArray()
    val subTasks = remember { tasks.filter { !it.isModule }.toTypedArray() }
    val moduleTasks = remember { tasks.filter { it.isModule }.toTypedArray() }
    // 从模型系统中读取实例（此时 getFields() 返回的字段已被 Config.load 挂载了正确的值）
    val manualTaskModel = remember { Model.getModel(ManualTaskModel::class.java) }
    val title = manualTaskModel?.getName() ?: "手动调度任务"

    val initialExchangeEnergyRainCard = remember {
        Model.getModelConfigMap()[ManualTaskModel::class.java.simpleName]
            ?.getModelFieldExt<BooleanModelField>("exchangeEnergyRainCard")
            ?.value ?: false
    }
    // 子任务状态
    var specialFoodCount by remember { mutableStateOf("1") }

    // 道具使用状态
    var selectedTool by remember { mutableStateOf("BIG_EATER_TOOL") }
    var toolCount by remember { mutableStateOf("1") }

    // 能量雨状态
    var exchangeEnergyRainCard by remember { mutableStateOf(initialExchangeEnergyRainCard) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(subTasks) { task ->
                val params = when (task) {
                    CustomTask.FOREST_ENERGY_RAIN -> mapOf(
                        "exchangeEnergyRainCard" to exchangeEnergyRainCard
                    )

                    CustomTask.FARM_SPECIAL_FOOD -> {
                        val count = specialFoodCount.toIntOrNull() ?: 0
                        mapOf("specialFoodCount" to count)
                    }

                    CustomTask.FARM_USE_TOOL -> mapOf(
                        "toolType" to selectedTool,
                        "toolCount" to (toolCount.toIntOrNull() ?: 1)
                    )

                    else -> emptyMap()
                }

                ManualTaskItem(
                    task = task,
                    onClick = { onTaskClick(task, params) },
                    hasSettings = task == CustomTask.FOREST_ENERGY_RAIN || task == CustomTask.FARM_SPECIAL_FOOD || task == CustomTask.FARM_USE_TOOL,
                    specialFoodCount = specialFoodCount,
                    onSpecialFoodCountChange = { specialFoodCount = it },
                    selectedTool = selectedTool,
                    onToolChange = { selectedTool = it },
                    toolCount = toolCount,
                    onToolCountChange = { toolCount = it },
                    exchangeEnergyRainCard = exchangeEnergyRainCard,
                    onExchangeEnergyRainCardChange = { exchangeEnergyRainCard = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray)
            }

            // 任务模块整体手动触发区
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "任务模块手动触发",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(moduleTasks) { task ->
                ManualTaskItem(
                    task = task,
                    onClick = { onTaskClick(task, emptyMap()) },
                    hasSettings = false,
                    specialFoodCount = specialFoodCount,
                    onSpecialFoodCountChange = { specialFoodCount = it },
                    selectedTool = selectedTool,
                    onToolChange = { selectedTool = it },
                    toolCount = toolCount,
                    onToolCountChange = { toolCount = it },
                    exchangeEnergyRainCard = exchangeEnergyRainCard,
                    onExchangeEnergyRainCardChange = { exchangeEnergyRainCard = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray)
            }
        }
    }
}

