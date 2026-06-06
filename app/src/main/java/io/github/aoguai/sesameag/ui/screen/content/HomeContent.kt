package io.github.aoguai.sesameag.ui.screen.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.extension.openUrl
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.screen.card.ModuleStatusCard
import io.github.aoguai.sesameag.ui.screen.card.OneWordCard
import io.github.aoguai.sesameag.ui.screen.card.ServicesStatusCard
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil.ServiceStatus
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.ToastUtil

@Composable
fun HomeContent(
    moduleStatus: MainViewModel.ModuleStatus,
    serviceStatus: ServiceStatus,
    permissionHealth: PermissionHealthSnapshot,
    oneWord: String,
    isOneWordLoading: Boolean,
    isLegalAccepted: Boolean,
    onLegalAcceptedChange: (Boolean) -> Unit,
    onOneWordClick: () -> Unit,
    onEvent: (MainActivity.MainUiEvent) -> Unit
) {
    val context = LocalContext.current
    var isServiceCardExpanded by remember { mutableStateOf(false) }

    var isStatusCardExpanded by remember { mutableStateOf(false) }
    val legalNoticeUrl = "https://github.com/Sesame-AG/Sesame-AG/blob/dev/LEGAL.md"
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "本应用开源免费,严禁倒卖",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
        // 1. 模块状态
        item {
            ModuleStatusCard(
                status = moduleStatus,
                expanded = isStatusCardExpanded,
                onClick = {
                    if (
                        moduleStatus is MainViewModel.ModuleStatus.NotActivated ||
                        moduleStatus is MainViewModel.ModuleStatus.Unsupported
                    ) {
                        isStatusCardExpanded = !isStatusCardExpanded//此处不可省略
                    }
                }
            )
        }

        // 2. 服务权限
        item {
            ServicesStatusCard(
                status = serviceStatus,
                permissionHealth = permissionHealth,
                expanded = isServiceCardExpanded,
                onClick = {
                    onEvent(
                        MainActivity.MainUiEvent.RequestPermissionCheck { canToggleDetails ->
                            if (canToggleDetails) {
                                isServiceCardExpanded = !isServiceCardExpanded // 此处不可省略
                            }
                        }
                    )
                }
            )
        }

        // 3. LEGAL 说明确认
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isLegalAccepted,
                    onCheckedChange = onLegalAcceptedChange
                )
                Text(
                    text = "我已阅读、理解并接受 LICENSE 与 LEGAL 中的相关说明",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { context.openUrl(legalNoticeUrl) },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    textDecoration = TextDecoration.Underline
                )
            }
        }

        // 4. 一言
        item {
            OneWordCard( // 提取出的一言卡片组件
                oneWord = oneWord,
                isLoading = isOneWordLoading,
                onClick = onOneWordClick,
                onLongClick = {
                    onEvent(MainActivity.MainUiEvent.OpenLog(LogChannel.DEBUG))
                    ToastUtil.showToast(context, "准备起飞🛫")
                }
            )
        }


    }
}
