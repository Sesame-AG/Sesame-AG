package io.github.aoguai.sesameag.ui.screen.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.screen.card.ModuleStatusCard
import io.github.aoguai.sesameag.ui.screen.card.OneWordCard
import io.github.aoguai.sesameag.ui.screen.card.ServicesStatusCard
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil.ServiceStatus
import io.github.aoguai.sesameag.util.OfficialBuildVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeContent(
    activeUser: UserEntity?,
    moduleStatus: MainViewModel.ModuleStatus,
    serviceStatus: ServiceStatus,
    permissionHealth: PermissionHealthSnapshot,
    oneWord: String,
    isOneWordLoading: Boolean,
    isLegalAccepted: Boolean,
    isSavingLegalAcceptance: Boolean,
    onLegalAcceptedChange: (Boolean) -> Unit,
    onDialogVisibilityChange: (Boolean) -> Unit,
    onExternalNavigation: () -> Unit,
    onOneWordClick: () -> Unit,
    onEvent: (MainActivity.MainUiEvent) -> Unit,
) {
    val context = LocalContext.current
    var isServiceCardExpanded by rememberSaveable { mutableStateOf(false) }
    var showOfficialSignatureDialog by rememberSaveable { mutableStateOf(false) }
    var showActivationDialog by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(showOfficialSignatureDialog, showActivationDialog) {
        onDialogVisibilityChange(showOfficialSignatureDialog || showActivationDialog)
        onDispose { }
    }

    val isOfficiallySigned by produceState(
        initialValue = false,
        key1 = context.applicationContext,
    ) {
        value = withContext(Dispatchers.IO) {
            OfficialBuildVerifier.isOfficiallySigned(context.applicationContext)
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "本应用开源免费,严禁倒卖",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isOfficiallySigned) {
                    Text(
                        text = "✓ 官方签名构建",
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .clickable { showOfficialSignatureDialog = true },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
        // 1. 模块状态
        item {
            ModuleStatusCard(
                status = moduleStatus,
                permissionHealth = permissionHealth,
                hasActiveUser = !activeUser?.userId.isNullOrBlank(),
                onExternalNavigation = onExternalNavigation,
                onDialogVisibilityChange = { showActivationDialog = it },
                isLegalAccepted = isLegalAccepted,
                isSavingLegalAcceptance = isSavingLegalAcceptance,
                onRefresh = { onEvent(MainActivity.MainUiEvent.RefreshEnvironment) },
                onLegalAcceptedChange = onLegalAcceptedChange,
            )
        }

        // 2. 服务权限
        item {
            ServicesStatusCard(
                status = serviceStatus,
                permissionHealth = permissionHealth,
                ownerUserId = activeUser?.userId,
                expanded = isServiceCardExpanded,
                onClick = { isServiceCardExpanded = !isServiceCardExpanded },
                onRefresh = { onEvent(MainActivity.MainUiEvent.RefreshEnvironment) },
                onRequest = { onEvent(MainActivity.MainUiEvent.RequestPermission(it)) },
            )
        }

        // 3. 一言
        item {
            OneWordCard(
                oneWord = oneWord,
                isLoading = isOneWordLoading,
                onClick = onOneWordClick,
            )
        }
    }

    CommonAlertDialog(
        showDialog = showOfficialSignatureDialog,
        onDismissRequest = { showOfficialSignatureDialog = false },
        onConfirm = { showOfficialSignatureDialog = false },
        title = "官方签名构建",
        text = "当前安装 APK 的签名证书与官方发布证书匹配。该标识只验证 APK 的签名来源，不代表下载、转发或售卖渠道获得官方授权；请继续以官方仓库的正式发布记录为准。",
        confirmText = "知道了",
        showCancelButton = false,
    )
}
