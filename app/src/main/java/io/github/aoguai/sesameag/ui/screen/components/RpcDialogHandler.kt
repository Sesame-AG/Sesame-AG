package io.github.aoguai.sesameag.ui.screen.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.aoguai.sesameag.ui.viewmodel.RpcDebugViewModel
import io.github.aoguai.sesameag.ui.viewmodel.RpcDialogState
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ToastUtil

@Composable
fun RpcDialogHandler(state: RpcDialogState, viewModel: RpcDebugViewModel) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    when (state) {
        is RpcDialogState.None -> {}

        is RpcDialogState.Edit -> {
            var name by rememberSaveable(state.item?.id.orEmpty(), state.initialName) { mutableStateOf(state.initialName) }
            var description by rememberSaveable(state.item?.id.orEmpty(), state.initialDesc) { mutableStateOf(state.initialDesc) }
            var json by rememberSaveable(state.item?.id.orEmpty(), state.initialJson) { mutableStateOf(state.initialJson) }
            var scheduleEnabled by rememberSaveable(state.item?.id.orEmpty(), state.initialScheduleEnabled) { mutableStateOf(state.initialScheduleEnabled) }
            var dailyCountText by rememberSaveable(state.item?.id.orEmpty(), state.initialDailyCount) {
                mutableStateOf(
                    if (state.initialDailyCount > 0) state.initialDailyCount.toString() else "1"
                )
            }
            val onScheduleEnabledChange: (Boolean) -> Unit = { checked ->
                scheduleEnabled = checked
                if (!checked) dailyCountText = "0"
                val current = dailyCountText.toIntOrNull() ?: 0
                if (checked && current <= 0) dailyCountText = "1"
            }

            Dialog(
                onDismissRequest = { viewModel.dismissDialog() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .imePadding()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // --- 顶部区域：标题 + 导入按钮 ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween // 标题在左，按钮在右
                        ) {
                            Text(
                                text = if (state.item == null) "新建调试项" else "编辑调试项",
                                style = MaterialTheme.typography.headlineSmall
                            )

                            TextButton(
                                onClick = {
                                    // 1. 读取剪贴板
                                    val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText.isBlank()) {
                                        ToastUtil.makeText(context, "剪贴板为空", 0).show()
                                        return@TextButton
                                    }
                                    Log.d("RpcUI", "剪贴板原始内容: [$clipText]")
                                    val parsed = viewModel.parseJsonFields(clipText)
                                    Log.d("RpcUI", "解析结果 Name: ${parsed.name}")
                                    name = parsed.name
                                    description = parsed.description
                                    json = try {
                                        if (parsed.method.isNotEmpty()) {
                                            val map = mapOf(
                                                "methodName" to parsed.method,
                                                "requestData" to parsed.requestData
                                            )
                                            viewModel.formatJsonFromRaw(map)
                                        } else {
                                            ""
                                        }
                                    } catch (e: Exception) {
                                        ""
                                    }

                                    ToastUtil.makeText(context, "已导入剪贴板数据", 0).show()
                                }
                            ) {
                                Icon(Icons.Default.ImportExport, null, modifier = Modifier.size(18.dp)) // 换个图标，或者用 ContentPaste
                                Spacer(Modifier.width(4.dp))
                                Text("从剪贴板导入")
                            }
                        }

                        // ... 下面的输入框保持不变 ...

                        // 1. 名称输入框
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称 (可选)") },
                            placeholder = { Text("例如：领森林能量") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // 2. 描述输入框
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("功能描述 (可选)") },
                            modifier = Modifier.fillMaxWidth(),
//                            minLines = 2,
                            maxLines = 4
                        )

                        // 3. 定时执行设置（与“自定义RPC定时执行”使用同一份 JSON 配置）
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .toggleable(
                                                value = scheduleEnabled,
                                                role = Role.Switch,
                                                onValueChange = onScheduleEnabledChange,
                                            ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "执行配置", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "手动运行不受此开关影响；定时执行建议只用于查询类 RPC。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = scheduleEnabled,
                                        onCheckedChange = null,
                                    )
                                }

                                OutlinedTextField(
                                    value = dailyCountText,
                                    onValueChange = { dailyCountText = it.filter { c -> c.isDigit() } },
                                    label = { Text("每日最多执行/尝试次数") },
                                    enabled = scheduleEnabled,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "触发点：\n每次自动任务调度周期开始、业务模块执行前。\n触发条件：\n本条开启定时、每日次数大于 0，且\n基础配置开关“自定义RPC | 配置文件定时执行(慎用)”已开启。\n失败或返回异常也会消耗一次当日次数，结果写入 capture 日志。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 3. JSON 数据输入框
                        OutlinedTextField(
                            value = json,
                            onValueChange = { json = it },
                            label = { Text("RPC 数据 (JSON)") },
                            placeholder = {
                                Text(
                                    text = """
                                        {
                                          "methodName": "com.alipay.xxx",
                                          "requestData": [...]
                                        }
                                    """.trimIndent(),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(248.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val formatted = viewModel.tryFormatJson(json)
                                        if (formatted != null) {
                                            json = formatted
                                            ToastUtil.makeText(context, "JSON 已格式化", 0).show()
                                        } else {
                                            ToastUtil.makeText(context, "格式错误", 0).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, "格式化")
                                }
                            }
                        )

                        // 底部按钮组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.dismissDialog() }) {
                                Text("取消")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val dailyCount = dailyCountText.trim().toIntOrNull() ?: 0
                                    viewModel.saveItem(name, description, json, scheduleEnabled, dailyCount, state.item)
                                },
                                modifier = Modifier.width(120.dp)
                            ) {
                                Text("保存")
                            }
                        }
                    }
                }
            }
        }

        is RpcDialogState.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("确认删除") },
                text = { Text("确定要删除 \"${state.item.getDisplayName()}\" 吗？") },
                confirmButton = {
                    TextButton(onClick = { viewModel.deleteItem(state.item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("取消") }
                }
            )
        }

        is RpcDialogState.RestoreConfirm -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("确认恢复") },
                text = { Text("将恢复 ${state.items.size} 项数据，当前列表将被覆盖。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmRestore(state.items) }) {
                        Text("恢复")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("取消") }
                }
            )
        }
    }
}

