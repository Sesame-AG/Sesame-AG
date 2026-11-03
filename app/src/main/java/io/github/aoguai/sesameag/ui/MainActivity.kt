package io.github.aoguai.sesameag.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.SesameApplication.Companion.hasPermissions
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.service.ConnectionState
import io.github.aoguai.sesameag.service.LsposedServiceManager
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthItem
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.permissions.PermissionPolicy
import io.github.aoguai.sesameag.ui.permissions.PermissionRequirement
import io.github.aoguai.sesameag.ui.permissions.PermissionStatus
import io.github.aoguai.sesameag.ui.screen.MainScreen
import io.github.aoguai.sesameag.ui.navigation.LogSource
import io.github.aoguai.sesameag.ui.theme.AppTheme
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.task.customTasks.CustomTask
import io.github.aoguai.sesameag.storage.PrivateStorage
import io.github.aoguai.sesameag.storage.StorageBackup
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.IconManager
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.Logback
import io.github.aoguai.sesameag.util.ModuleDiagnostics
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainActivity : ComponentActivity() {
    private data class ExactAlarmManifestState(
        val targetSdkVersion: Int?,
        val requestsScheduleExactAlarm: Boolean,
        val usesExactAlarm: Boolean
    ) {
        val isAlwaysGranted: Boolean
            get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (targetSdkVersion != null && targetSdkVersion < Build.VERSION_CODES.S) ||
                usesExactAlarm

        val requiresManualSettingsCheck: Boolean
            get() = !isAlwaysGranted && requestsScheduleExactAlarm
    }

    private data class TargetPermissionSnapshot(
        val available: Boolean,
        val contextPackage: String,
        val targetBatteryIgnored: Boolean,
        val targetExactAlarmAllowed: Boolean?
    )

    private enum class PermissionRequestMode {
        AUTO_CRITICAL,
        MANUAL_CARD
    }

    private val viewModel: MainViewModel by viewModels()
    private val clearModuleDataFailures = MutableStateFlow<List<String>>(emptyList())
    private val confirmStorageRestore = MutableStateFlow(false)
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val before = PrivateStorage.state.value
            if (before.busy) return@launch
            PrivateStorage.update(before.copy(busy = true, message = "正在导出完整备份"))
            try {
                if (before.ready) StorageBackup.export(this@MainActivity, uri) else PrivateStorage.backupLegacy(uri)
                ToastUtil.showToast(this@MainActivity, "完整备份已导出并校验")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                PrivateStorage.update(before.copy(message = "备份失败：${failure.message}"))
                ToastUtil.showToast(this@MainActivity, "备份失败：${failure.message}")
            } finally { PrivateStorage.update(PrivateStorage.state.value.copy(busy = false)) }
        }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val before = PrivateStorage.state.value
            if (before.busy) return@launch
            PrivateStorage.update(before.copy(busy = true, message = "正在校验并恢复完整备份"))
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    if (before.ready) StorageBackup.restore(applicationContext, uri) else PrivateStorage.restoreLegacy(uri)
                    Config.unload()
                    io.github.aoguai.sesameag.util.maps.UserMap.unload()
                    io.github.aoguai.sesameag.data.Statistics.unload()
                    UserDataStoreManager.shutdownAll()
                    DataStore.init(Files.CONFIG_DIR)
                }
                viewModel.refreshUserConfigs()
                refreshEnvironment()
                ToastUtil.showToast(this@MainActivity, if (PrivateStorage.state.value.cleanupPending)
                    "备份已恢复，部分旧数据待清理" else "备份已恢复，请重新打开目标应用")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val state = PrivateStorage.state.value
                PrivateStorage.update(state.copy(message = "恢复失败：${failure.message}"))
                ToastUtil.showToast(this@MainActivity, "恢复失败：${failure.message}")
            } finally { PrivateStorage.update(PrivateStorage.state.value.copy(busy = false)) }
        }
    }
    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onRuntimePermissionRequestFinished(result)
        }
    private val permissionSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ModuleDiagnostics.event("permission_settings", "returned", "requirement=${pendingPermissionRequest ?: "manual_card"}")
            pendingPermissionRequest = null
            requestTargetPermissionSnapshot(clearCached = true)
            continuePermissionQueueOrAuto()
        }
    private var pendingPermissionRequest: PermissionRequirement? = null
    private var activePermissionMode: PermissionRequestMode? = null
    private var activePermissionOrder: List<PermissionRequirement> = emptyList()
    private val requestedPermissionsThisVisibility = linkedSetOf<PermissionRequirement>()
    private val requestedPermissionsThisQueue = linkedSetOf<PermissionRequirement>()
    private val deniedPermissionsThisVisibility = linkedSetOf<PermissionRequirement>()
    private var latestTargetPermissionSnapshot: TargetPermissionSnapshot? = null
    private var pendingTargetPermissionSnapshotToken: Long = 0L

    private val autoCriticalPermissions = listOf(
        PermissionRequirement.LSPOSED_TARGET_SCOPE
    )

    private val lspConnectionListener: (ConnectionState) -> Unit = { state ->
        runOnUiThread {
            if (state !is ConnectionState.Connected &&
                pendingPermissionRequest == PermissionRequirement.LSPOSED_TARGET_SCOPE
            ) {
                markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
            }
            refreshPermissionHealth()
            continuePermissionQueueOrAuto()
        }
    }

    private val targetPermissionSnapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT_RESULT) {
                return
            }
            val responseToken = intent.getLongExtra("requestToken", 0L)
            if (
                responseToken == 0L ||
                responseToken != pendingTargetPermissionSnapshotToken
            ) {
                return
            }
            latestTargetPermissionSnapshot = TargetPermissionSnapshot(
                available = intent.getBooleanExtra("available", false),
                contextPackage = intent.getStringExtra("contextPackage").orEmpty(),
                targetBatteryIgnored = intent.getBooleanExtra("targetBatteryIgnored", false),
                targetExactAlarmAllowed = if (intent.hasExtra("targetExactAlarmAllowed")) {
                    intent.getBooleanExtra("targetExactAlarmAllowed", false)
                } else {
                    null
                }
            ).takeIf { it.available && it.contextPackage == General.PACKAGE_NAME }
            pendingTargetPermissionSnapshotToken = 0L
            ModuleDiagnostics.event("target_permission_snapshot", if (latestTargetPermissionSnapshot != null) "received" else "unavailable",
                "exactAlarm=${latestTargetPermissionSnapshot?.targetExactAlarmAllowed} battery=${latestTargetPermissionSnapshot?.targetBatteryIgnored}")
            refreshPermissionHealth()
        }
    }

    // Shizuku 监听器
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            ModuleDiagnostics.event("shizuku_permission", "returned", "granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
            if (pendingPermissionRequest == PermissionRequirement.SHELL_EXECUTOR) {
                pendingPermissionRequest = null
            }
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ToastUtil.showToast(this, "Shizuku 授权成功！")

                lifecycleScope.launch {
                    CommandUtil.executeCommand(this@MainActivity, "echo init_shizuku")
                    refreshPermissionHealth()
                }
            } else {
                deniedPermissionsThisVisibility.add(PermissionRequirement.SHELL_EXECUTOR)
                ToastUtil.showToast(this, "Shizuku 授权被拒绝")
            }
            continuePermissionQueueOrAuto()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        ContextCompat.registerReceiver(
            this,
            targetPermissionSnapshotReceiver,
            IntentFilter(ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT_RESULT),
            ContextCompat.RECEIVER_EXPORTED
        )
        LsposedServiceManager.init()
        LsposedServiceManager.addConnectionListener(lspConnectionListener)
        setupShizuku()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CommandUtil.serviceStatus.collectLatest {
                    ModuleDiagnostics.event("executor", "changed", "state=${it.javaClass.simpleName} type=${(it as? CommandUtil.ServiceStatus.Active)?.type ?: "unknown"}")
                    refreshPermissionHealth()
                    viewModel.onExecutorStateChanged()
                    if (it is CommandUtil.ServiceStatus.Active && PrivateStorage.state.value.needsInspection) PrivateStorage.inspect()
                    if (activePermissionMode == PermissionRequestMode.MANUAL_CARD ||
                        pendingPermissionRequest == PermissionRequirement.SHELL_EXECUTOR
                    ) {
                        continuePermissionQueueOrAuto()
                    }
                }
            }
        }
        lifecycleScope.launch {
            var storageInitialized = false
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PrivateStorage.state.collectLatest { state ->
                    if (state.ready && !state.busy && !storageInitialized) {
                        storageInitialized = true
                        Log.init(this@MainActivity)
                        refreshEnvironment()
                    }
                    refreshPermissionHealth()
                }
            }
        }
        if (PrivateStorage.state.value.needsInspection) lifecycleScope.launch { PrivateStorage.inspect() }
        continuePermissionQueueOrAuto()
        if (io.github.aoguai.sesameag.storage.PrivateStorage.state.value.let { it.ready && it.cleanupPending }) {
            lifecycleScope.launch { io.github.aoguai.sesameag.storage.PrivateStorage.retryCleanup() }
        }

        // 4. 同步图标状态
        val prefs = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE)
        IconManager.syncIconState(this, prefs.getBoolean("is_icon_hidden", false))


        // 5. 设置 Compose 内容
        setContent {
            // 收集 ViewModel 状态
            val oneWord by viewModel.oneWord.collectAsStateWithLifecycle()
            val activeUser by viewModel.activeUser.collectAsStateWithLifecycle()
            val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
            //  获取实时的 UserEntity 列表
            val userList by viewModel.userList.collectAsStateWithLifecycle()
            val accountSlots by viewModel.accountSlots.collectAsStateWithLifecycle()
            val permissionHealth by viewModel.permissionHealth.collectAsStateWithLifecycle()
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()
            val storageState by io.github.aoguai.sesameag.storage.PrivateStorage.state.collectAsStateWithLifecycle()
            val clearFailurePaths by clearModuleDataFailures.collectAsStateWithLifecycle()
            val showRestoreConfirmation by confirmStorageRestore.collectAsStateWithLifecycle()

            // AppTheme 会处理状态栏颜色
            AppTheme(dynamicColor = isDynamicColor) {
                if (showRestoreConfirmation) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmStorageRestore.value = false },
                        title = { androidx.compose.material3.Text("恢复完整备份") },
                        text = { androidx.compose.material3.Text("将关闭目标应用，并用备份替换当前全部模块配置、账号记录和日志。请先完成目标应用中的操作。\n\n选择备份后，请保持本应用在前台，等待恢复完成后再操作。") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = {
                            confirmStorageRestore.value = false
                            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        }) { androidx.compose.material3.Text("选择备份") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmStorageRestore.value = false }) {
                            androidx.compose.material3.Text("取消")
                        } },
                    )
                }
                if (storageState.ready && storageState.busy) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {},
                        title = { androidx.compose.material3.Text(storageState.message) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                androidx.compose.material3.Text("请保持本应用在前台，等待操作完成。期间不要返回、切换应用、锁屏、打开其他应用或清理后台。")
                                androidx.compose.material3.LinearProgressIndicator()
                            }
                        },
                        confirmButton = {},
                    )
                    return@AppTheme
                }
                io.github.aoguai.sesameag.ui.screen.components.StorageMigrationDialog(
                    state = storageState,
                    onMigrate = { lifecycleScope.launch { if (storageState.needsInspection) PrivateStorage.inspect() else PrivateStorage.migrate() } },
                    onBackup = { backupLauncher.launch("sesame-backup-${System.currentTimeMillis()}.zip") },
                    onRestore = { confirmStorageRestore.value = true },
                    onAuthorize = { requestPermissionFromCard(PermissionRequirement.SHELL_EXECUTOR) },
                    onExit = ::finish,
                )
                if (!storageState.ready) return@AppTheme
                MainScreen(
                    oneWord = oneWord,
                    activeUserName = activeUser?.showName ?: "未载入账号",
                    hasActiveUser = activeUser != null,
                    moduleStatus = moduleStatus,
                    permissionHealth = permissionHealth,
                    viewModel = viewModel,
                    isDynamicColor = isDynamicColor, // 传给 MainScreen
                    // 传入回调
                    userList = userList, // 传入列表
                    accountSlots = accountSlots,
                    onPrepareManualTasks = ::ensureManualTaskConfigLoaded,
                    onRunManualTask = ::runManualTask,
                    clearModuleDataFailurePaths = clearFailurePaths,
                    onDismissClearModuleDataFailure = { clearModuleDataFailures.value = emptyList() },
                    onEvent = { event -> handleEvent(event) },
                    onExitRequested = ::finish,
                )
            }
        }
        requestHighestSupportedFrameRate()
    }

    private fun requestHighestSupportedFrameRate() {
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.supportedModes
        }
        val requestedFrameRate = modes?.maxOfOrNull { it.refreshRate } ?: return
        if (requestedFrameRate <= 60f) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setRequestedFrameRate(requestedFrameRate)
        } else {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = requestedFrameRate
            }
        }
    }

    /**
     * 定义 UI 事件
     */
    sealed class MainUiEvent {
        data object RefreshOneWord : MainUiEvent()
        data class OpenLog(val channel: LogChannel) : MainUiEvent()
        data class ToggleIconHidden(val isHidden: Boolean) : MainUiEvent()
        data object OpenExtend : MainUiEvent()
        data object ClearConfig : MainUiEvent()
        data object ExportStorageBackup : MainUiEvent()
        data object RestoreStorageBackup : MainUiEvent()
        data object RefreshEnvironment : MainUiEvent()
        data class RequestPermission(val requirement: PermissionRequirement) : MainUiEvent()
        data object OpenTargetApp : MainUiEvent()
    }

    /**
     * 统一处理事件
     */
    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            MainUiEvent.RefreshOneWord -> viewModel.fetchOneWord()
            is MainUiEvent.OpenLog -> Unit
            MainUiEvent.RefreshEnvironment -> {
                ModuleDiagnostics.event("environment_refresh", "requested")
                CommandUtil.connect(applicationContext)
                refreshEnvironment()
            }
            is MainUiEvent.RequestPermission -> requestPermissionFromCard(event.requirement)
            MainUiEvent.OpenTargetApp -> {
                ModuleDiagnostics.event("open_target", "requested")
                try {
                    val intent = packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME)
                    if (intent != null) {
                        viewModel.markExternalNavigation()
                        startActivity(intent)
                        ModuleDiagnostics.event("open_target", "launched")
                    } else {
                        ModuleDiagnostics.event("open_target", "launch_intent_unavailable", "target=${General.PACKAGE_NAME}")
                        ToastUtil.showToast(this, "暂时无法打开目标应用")
                    }
                } catch (e: Exception) {
                    viewModel.cancelExternalNavigation()
                    ModuleDiagnostics.event("open_target", "failed", "error=${e.javaClass.simpleName}")
                    Log.printStackTrace("MainActivity", "打开目标应用失败", e)
                    ToastUtil.showToast(this, "暂时无法打开目标应用")
                }
            }
            is MainUiEvent.ToggleIconHidden -> {
                val shouldHide = event.isHidden
                getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
            }

            MainUiEvent.OpenExtend -> Unit
            MainUiEvent.ExportStorageBackup -> backupLauncher.launch("sesame-backup-${System.currentTimeMillis()}.zip")
            MainUiEvent.RestoreStorageBackup -> confirmStorageRestore.value = true
            MainUiEvent.ClearConfig -> {
                ModuleDiagnostics.event("clear_module_data", "requested")
                clearModuleDataFailures.value = emptyList()
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { DataStore.shutdown() }
                    runCatching { UserDataStoreManager.shutdownAll() }
                    runCatching { PersistentScheduleRegistry.clearAll(applicationContext) }
                    runCatching { UnifiedScheduler.cleanup() }

                    val fileResult = Files.clearAllModuleData(applicationContext)
                    val preferencesCleared =
                        getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    val failedPaths = fileResult.failedPaths.toMutableList()
                    if (!preferencesCleared) {
                        failedPaths += "SharedPreferences:$PREFERENCES_KEY"
                    }
                    if (failedPaths.isNotEmpty()) {
                        Log.runtime(
                            "MainActivity",
                            "清除模块数据失败: count=${failedPaths.size}, first=${failedPaths.first()}"
                        )
                    }

                    runCatching {
                        Logback.reloadFileLogging(enableCaptureAppender = true)
                    }

                    if (!ModuleDiagnostics.clear(applicationContext)) {
                        failedPaths += "system_diagnostics"
                    }
                    ModuleDiagnostics.event("clear_module_data", if (failedPaths.isEmpty()) "completed" else "failed", "failed=${failedPaths.size}")
                    withContext(Dispatchers.Main) {
                        viewModel.refreshUserConfigs()
                        if (failedPaths.isEmpty()) {
                            viewModel.resetAccountGuide()
                            ThemeManager.resetToDefaults()
                            IconManager.syncIconState(this@MainActivity, false)
                            sendBroadcast(
                                Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                                    putExtra("configReload", true)
                                }
                            )
                            ToastUtil.showToast(this@MainActivity, "已清除全部模块数据，正在恢复默认配置")
                            recreate()
                        } else {
                            clearModuleDataFailures.value = failedPaths.toList()
                        }
                    }
                }
            }
        }
    }

    // --- 辅助方法 ---

    private suspend fun ensureManualTaskConfigLoaded() = withContext(Dispatchers.IO) {
        Model.initAllModel()
        val activeUser = DataStore.get("activedUser", io.github.aoguai.sesameag.entity.UserEntity::class.java)
        activeUser?.userId?.let { userId ->
            UserMap.setCurrentUserId(userId)
            Config.load(userId)
        }
    }

    private fun runManualTask(task: CustomTask, params: Map<String, Any>): LogSource? {
        val activeUserId = DataStore.get("activedUser", io.github.aoguai.sesameag.entity.UserEntity::class.java)?.userId
        val detail = "task=${task.name} account=${ModuleDiagnostics.account(activeUserId)}"
        ModuleDiagnostics.event("manual_task", "requested", detail)
        if (!AccountSlotRegistry.isExecutableUser(activeUserId)) {
            ModuleDiagnostics.event("manual_task", "rejected", "$detail reason=account_not_executable")
            ToastUtil.showToast(this, "当前账号不在可执行槽位，无法运行手动任务")
            return null
        }
        return try {
            val intent = Intent(ApplicationHookConstants.BroadcastActions.MANUAL_TASK)
            intent.putExtra("task", task.name)
            params.forEach { (key, value) ->
                when (value) {
                    is Int -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                }
            }
            sendBroadcast(intent)
            ModuleDiagnostics.event("manual_task", "sent", detail)
            ToastUtil.showToast(this, "已发送指令: ${task.displayName}")
            val logFile = Files.getLogFile(LogChannel.RECORD)
            if (logFile.exists()) {
                LogSource.FilePath(logFile.absolutePath)
            } else {
                ToastUtil.showToast(this, "日志文件尚未生成")
                null
            }
        } catch (e: Exception) {
            ModuleDiagnostics.event("manual_task", "failed", "$detail error=${e.javaClass.simpleName}")
            ToastUtil.showToast(this, "发送失败: ${e.message}")
            null
        }
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (!Shizuku.pingBinder()) return

        val granted = checkSelfPermission(ShizukuProvider.PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (granted && hasPermissions) {
            lifecycleScope.launch {
                CommandUtil.executeCommand(this@MainActivity, "echo init_shizuku")
                refreshPermissionHealth()
            }
        }
    }

    private fun refreshEnvironment() {
        LsposedServiceManager.refreshScope()
        viewModel.refreshModuleFrameworkStatus()
        hasPermissions = io.github.aoguai.sesameag.storage.PrivateStorage.isReady
        if (hasPermissions) {
            viewModel.initAppLogic()
            viewModel.refreshUserConfigs()
        }
        requestTargetPermissionSnapshot(clearCached = true)
        refreshPermissionHealth()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForegroundStarted()
        refreshEnvironment()
        if (
            pendingPermissionRequest == PermissionRequirement.LSPOSED_TARGET_SCOPE &&
            LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)
        ) {
            markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
        } else if (pendingPermissionRequest == PermissionRequirement.SHELL_EXECUTOR) {
            pendingPermissionRequest = null
        }
        CommandUtil.connect(applicationContext)
        continuePermissionQueueOrAuto()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.onForegroundStopped()
            CommandUtil.unbind(applicationContext)
        }
        if (!isChangingConfigurations && pendingPermissionRequest == null) {
            requestedPermissionsThisVisibility.clear()
            deniedPermissionsThisVisibility.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            CommandUtil.unbind(applicationContext)
        }
        LsposedServiceManager.removeConnectionListener(lspConnectionListener)
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        runCatching { unregisterReceiver(targetPermissionSnapshotReceiver) }
    }

    private fun onRuntimePermissionRequestFinished(result: Map<String, Boolean>) {
        val deniedPermission = pendingPermissionRequest
        ModuleDiagnostics.event("permission", "returned", "requirement=$deniedPermission granted=${result.values.all { it }} answered=${result.isNotEmpty()}")
        if (deniedPermission in setOf(
                PermissionRequirement.MODULE_FILE,
                PermissionRequirement.MODULE_NOTIFICATION
            ) && result.isNotEmpty() && !result.values.all { it }
        ) {
            deniedPermissionsThisVisibility.add(deniedPermission!!)
        }
        pendingPermissionRequest = null
        continuePermissionQueueOrAuto()
    }

    private fun requestPermissionFromCard(permission: PermissionRequirement) {
        if (permission == PermissionRequirement.MODULE_FILE) {
            if (io.github.aoguai.sesameag.storage.PrivateStorage.isReady) {
                lifecycleScope.launch { io.github.aoguai.sesameag.storage.PrivateStorage.retryCleanup() }
            } else {
                ToastUtil.showToast(this, "请先完成配置迁移")
            }
            return
        }
        val targetPackage = when (permission) {
            PermissionRequirement.TARGET_EXACT_ALARM, PermissionRequirement.TARGET_BATTERY -> General.PACKAGE_NAME
            else -> packageName
        }
        val settingsIntent = when (permission) {
            PermissionRequirement.MODULE_FILE -> null
            PermissionRequirement.MODULE_NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, targetPackage)
            PermissionRequirement.MODULE_EXACT_ALARM, PermissionRequirement.TARGET_EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$targetPackage"))
                } else null
            }
            PermissionRequirement.MODULE_BATTERY, PermissionRequirement.TARGET_BATTERY ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> null
        }
        if (settingsIntent != null) {
            ModuleDiagnostics.event("permission_settings", "requested", "requirement=$permission target=$targetPackage")
            if (targetPackage != packageName && !PermissionUtil.isPackageInstalled(this, targetPackage)) {
                ModuleDiagnostics.event("permission_settings", "target_not_visible", "target=$targetPackage")
                ToastUtil.showToast(this, "未检测到目标应用")
                return
            }
            val fallbackAction = when (permission) {
                PermissionRequirement.MODULE_FILE -> null
                PermissionRequirement.MODULE_EXACT_ALARM, PermissionRequirement.TARGET_EXACT_ALARM ->
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                else -> null
            }
            viewModel.markExternalNavigation()
            if (!PermissionUtil.startActivitySafely(this, settingsIntent, fallbackAction, permissionSettingsLauncher)) {
                viewModel.cancelExternalNavigation()
                ModuleDiagnostics.event("permission_settings", "launch_failed", "requirement=$permission")
                ToastUtil.showToast(this, "暂时无法打开系统设置")
            } else {
                ModuleDiagnostics.event("permission_settings", "launched", "requirement=$permission")
            }
            return
        }
        if (pendingPermissionRequest != null) return
        clearActivePermissionQueue()
        runPermissionQueue(listOf(permission), PermissionRequestMode.MANUAL_CARD)
    }

    private fun continuePermissionQueueOrAuto() {
        if (pendingPermissionRequest != null) {
            refreshPermissionHealth()
            return
        }
        val mode = activePermissionMode
        val order = activePermissionOrder
        if (mode != null && order.isNotEmpty()) {
            runPermissionQueue(order, mode)
        } else {
            runPermissionQueue(autoCriticalPermissions, PermissionRequestMode.AUTO_CRITICAL)
        }
    }

    private fun runPermissionQueue(
        order: List<PermissionRequirement>,
        mode: PermissionRequestMode
    ): Boolean {
        if (pendingPermissionRequest != null) {
            refreshPermissionHealth()
            return false
        }
        activePermissionMode = mode
        activePermissionOrder = order
        hasPermissions = io.github.aoguai.sesameag.storage.PrivateStorage.isReady
        if (hasPermissions) {
            viewModel.initAppLogic()
        }

        val snapshot = refreshPermissionHealth()
        for (permission in order) {
            val item = snapshot.item(permission) ?: continue
            if (!item.canRequest) continue
            if (!shouldRequest(permission, mode)) continue
            if (requestPermission(permission)) {
                return true
            }
            deniedPermissionsThisVisibility.add(permission)
            markRequestFinished(permission)
            if (permission != PermissionRequirement.LSPOSED_TARGET_SCOPE) {
                ToastUtil.showToast(this, "暂时无法发起请求，请重试")
            }
        }

        if (
            mode == PermissionRequestMode.MANUAL_CARD &&
            snapshot.item(PermissionRequirement.SHELL_EXECUTOR)?.status == PermissionStatus.REQUESTING
        ) {
            refreshPermissionHealth()
            return true
        }

        clearActivePermissionQueue()
        refreshPermissionHealth()
        return false
    }

    private fun shouldRequest(
        permission: PermissionRequirement,
        mode: PermissionRequestMode
    ): Boolean {
        if (pendingPermissionRequest == permission) {
            return false
        }
        val requestedSet = if (mode == PermissionRequestMode.AUTO_CRITICAL) {
            requestedPermissionsThisVisibility
        } else {
            requestedPermissionsThisQueue
        }
        if (!requestedSet.add(permission)) {
            return false
        }
        pendingPermissionRequest = permission
        refreshPermissionHealth()
        return true
    }

    private fun requestPermission(permission: PermissionRequirement): Boolean {
        ModuleDiagnostics.event("permission", "requested", "requirement=$permission mode=$activePermissionMode")
        viewModel.markExternalNavigation()
        val requested = when (permission) {
            PermissionRequirement.MODULE_FILE -> false

            PermissionRequirement.MODULE_NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    permission in deniedPermissionsThisVisibility &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    PermissionUtil.openAppSettings(this, permissionSettingsLauncher)
                } else {
                    PermissionUtil.checkOrRequestNotificationPermission(this, runtimePermissionsLauncher, permissionSettingsLauncher)
                }
            }

            PermissionRequirement.LSPOSED_TARGET_SCOPE -> requestLsposedTargetScope()

            PermissionRequirement.MODULE_EXACT_ALARM -> {
                PermissionUtil.checkOrRequestExactAlarmPermissions(
                    this,
                    packageName,
                    permissionSettingsLauncher
                )
            }

            PermissionRequirement.TARGET_EXACT_ALARM -> {
                PermissionUtil.checkOrRequestExactAlarmPermissions(
                    this,
                    General.PACKAGE_NAME,
                    permissionSettingsLauncher
                )
            }

            PermissionRequirement.MODULE_BATTERY -> {
                PermissionUtil.checkOrRequestBatteryPermissions(this, packageName, permissionSettingsLauncher)
            }

            PermissionRequirement.TARGET_BATTERY -> {
                PermissionUtil.checkOrRequestBatteryPermissions(this, General.PACKAGE_NAME, permissionSettingsLauncher)
            }

            PermissionRequirement.SHELL_EXECUTOR -> requestShellExecutor()
        }
        if (!requested) {
            viewModel.cancelExternalNavigation()
            ModuleDiagnostics.event("permission", "request_failed", "requirement=$permission")
        }
        return requested
    }

    private fun requestLsposedTargetScope(): Boolean {
        val sent = LsposedServiceManager.requestTargetScope { result ->
            runOnUiThread {
                markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
                ModuleDiagnostics.event("lsposed_scope", if (result.success) "granted" else "failed")
                if (result.success) {
                    ToastUtil.showToast(this, "已添加目标应用")
                } else {
                    deniedPermissionsThisVisibility.add(PermissionRequirement.LSPOSED_TARGET_SCOPE)
                    Log.runtime("MainActivity", "LSPosed scope request failed: ${result.message}")
                    ToastUtil.showToast(this, "添加失败，请重试或在 LSPosed 中选择目标应用")
                }
                continuePermissionQueueOrAuto()
            }
        }
        if (!sent) {
            markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
        }
        return sent
    }

    private fun requestShellExecutor(): Boolean {
        CommandUtil.connect(applicationContext)
        if (isShizukuPermissionMissing()) {
            return try {
                Shizuku.requestPermission(1234)
                true
            } catch (e: Exception) {
                ToastUtil.showToast(this, "Shizuku 授权请求失败: ${e.message}")
                false
            }
        }

        lifecycleScope.launch {
            CommandUtil.executeCommand(this@MainActivity, "echo init_shell")
            markRequestFinished(PermissionRequirement.SHELL_EXECUTOR)
            refreshPermissionHealth()
            continuePermissionQueueOrAuto()
        }
        return true
    }

    private fun requestTargetPermissionSnapshot(clearCached: Boolean = false): Boolean {
        if (clearCached) {
            latestTargetPermissionSnapshot = null
        }
        if (!PermissionUtil.isPackageInstalled(this, General.PACKAGE_NAME)) {
            latestTargetPermissionSnapshot = null
            pendingTargetPermissionSnapshotToken = 0L
            ModuleDiagnostics.event("target_permission_snapshot", "target_not_visible")
            return false
        }
        val requestToken = System.nanoTime()
        pendingTargetPermissionSnapshotToken = requestToken
        try {
            sendBroadcast(
                Intent(ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT).apply {
                    setPackage(General.PACKAGE_NAME)
                    putExtra("requestToken", requestToken)
                }
            )
        } catch (e: Exception) {
            pendingTargetPermissionSnapshotToken = 0L
            Log.printStackTrace("MainActivity", "Target permission refresh failed", e)
            return false
        }
        lifecycleScope.launch {
            // 权限快照广播的 350ms 超时兜底；仅对应请求仍未回包时刷新诊断，不延后正常回包处理。
            delay(350)
            if (pendingTargetPermissionSnapshotToken == requestToken) {
                pendingTargetPermissionSnapshotToken = 0L
                ModuleDiagnostics.event("target_permission_snapshot", "timeout", "limitMs=350")
                refreshPermissionHealth()
            }
        }
        return true
    }

    private fun markRequestFinished(permission: PermissionRequirement) {
        if (pendingPermissionRequest == permission) {
            pendingPermissionRequest = null
        }
    }

    private fun clearActivePermissionQueue() {
        activePermissionMode = null
        activePermissionOrder = emptyList()
        requestedPermissionsThisQueue.clear()
    }

    private fun refreshPermissionHealth(): PermissionHealthSnapshot {
        val snapshot = buildPermissionHealthSnapshot(pendingPermissionRequest)
        viewModel.setPermissionQueueBusy(pendingPermissionRequest != null || activePermissionOrder.isNotEmpty())
        viewModel.updatePermissionHealth(snapshot)
        return snapshot
    }

    private fun buildPermissionHealthSnapshot(
        requesting: PermissionRequirement?
    ): PermissionHealthSnapshot {
        val targetInstalled = PermissionUtil.isPackageInstalled(this, General.PACKAGE_NAME)
        val targetPermissionSnapshot = latestTargetPermissionSnapshot
        val targetBatteryIgnored = targetInstalled && (
            targetPermissionSnapshot?.targetBatteryIgnored == true ||
                PermissionUtil.checkBatteryPermissions(this, General.PACKAGE_NAME)
            )
        val targetExactAlarmManifest = packageExactAlarmManifestState(General.PACKAGE_NAME)
        val targetExactAlarmPermissionStatus = targetExactAlarmStatus(
            targetInstalled = targetInstalled,
            targetBatteryIgnored = targetBatteryIgnored,
            manifestState = targetExactAlarmManifest,
            hookExactAlarmAllowed = targetPermissionSnapshot?.targetExactAlarmAllowed,
            requesting = requesting
        )
        val items = listOf(
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_FILE,
                status = statusForGranted(
                    PermissionRequirement.MODULE_FILE,
                    io.github.aoguai.sesameag.storage.PrivateStorage.isReady,
                    requesting
                ),
                policy = PermissionPolicy.MANUAL_CRITICAL,
                title = "私有配置存储",
                description = io.github.aoguai.sesameag.storage.PrivateStorage.state.value.message,
                actionLabel = if (io.github.aoguai.sesameag.storage.PrivateStorage.state.value.cleanupPending) "清理旧副本" else null
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_NOTIFICATION,
                status = statusForGranted(
                    PermissionRequirement.MODULE_NOTIFICATION,
                    PermissionUtil.checkNotificationPermission(this),
                    requesting
                ),
                policy = PermissionPolicy.MANUAL_CARD,
                title = "模块通知",
                description = "接收模块通知",
                actionLabel = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    (PermissionRequirement.MODULE_NOTIFICATION in deniedPermissionsThisVisibility &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) "打开设置" else "开启通知"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.LSPOSED_TARGET_SCOPE,
                status = lsposedScopeStatus(targetInstalled, requesting),
                policy = PermissionPolicy.AUTO_CRITICAL,
                title = "目标应用作用域",
                description = if (targetInstalled) {
                    "在目标应用中启用模块"
                } else {
                    "安装目标应用，或在应用隐藏设置中将其设为可见"
                },
                actionLabel = if (targetInstalled) "添加目标应用" else null
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_EXACT_ALARM,
                status = statusForGranted(
                    PermissionRequirement.MODULE_EXACT_ALARM,
                    PermissionUtil.checkExactAlarmPermissions(this, packageName),
                    requesting
                ),
                policy = PermissionPolicy.MANUAL_CARD,
                title = "模块精确闹钟",
                description = "提高定时任务准点性",
                actionLabel = "打开设置"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.TARGET_EXACT_ALARM,
                status = targetExactAlarmPermissionStatus,
                policy = PermissionPolicy.MANUAL_CARD,
                title = "目标应用精确闹钟",
                description = "提高定时任务准点性",
                actionLabel = if (targetInstalled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    targetExactAlarmManifest.requestsScheduleExactAlarm
                ) "打开设置" else null,
                requestWhenUnavailable = targetInstalled &&
                    targetExactAlarmManifest.requiresManualSettingsCheck &&
                    targetExactAlarmPermissionStatus == PermissionStatus.UNAVAILABLE
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_BATTERY,
                status = statusForGranted(
                    PermissionRequirement.MODULE_BATTERY,
                    PermissionUtil.checkBatteryPermissions(this, packageName),
                    requesting
                ),
                policy = PermissionPolicy.MANUAL_CARD,
                title = "模块电池优化豁免",
                description = "减少后台任务被系统延迟",
                actionLabel = "打开设置"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.TARGET_BATTERY,
                status = if (!targetInstalled) {
                    PermissionStatus.UNAVAILABLE
                } else {
                    statusForGranted(
                        PermissionRequirement.TARGET_BATTERY,
                        targetBatteryIgnored,
                        requesting
                    )
                },
                policy = PermissionPolicy.MANUAL_CARD,
                title = "目标应用电池优化豁免",
                description = "减少后台任务被系统延迟",
                actionLabel = if (targetInstalled) "打开设置" else null
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.SHELL_EXECUTOR,
                status = shellExecutorStatus(requesting),
                policy = PermissionPolicy.MANUAL_CRITICAL,
                title = "Root/Shizuku",
                description = "Root 与 Shizuku 任一可用即可",
                actionLabel = if (isShizukuPermissionMissing()) "授权 Shizuku" else "连接",
                requestWhenUnavailable = true
            )
        )
        return PermissionHealthSnapshot(items = items)
    }

    private fun statusForGranted(
        requirement: PermissionRequirement,
        granted: Boolean,
        requesting: PermissionRequirement?
    ): PermissionStatus {
        if (granted) {
            markPermissionGranted(requirement)
        }
        return when {
            granted -> PermissionStatus.GRANTED
            requesting == requirement -> PermissionStatus.REQUESTING
            requirement in deniedPermissionsThisVisibility -> PermissionStatus.DENIED
            else -> PermissionStatus.MISSING
        }
    }

    private fun lsposedScopeStatus(
        targetInstalled: Boolean,
        requesting: PermissionRequirement?
    ): PermissionStatus {
        if (!targetInstalled) return PermissionStatus.UNAVAILABLE
        val frameworkStatus = LsposedServiceManager.connectedFrameworkStatus() ?: return PermissionStatus.UNAVAILABLE
        if (!frameworkStatus.isSupportedLsposed) return PermissionStatus.UNSUPPORTED
        if (LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)) {
            markPermissionGranted(PermissionRequirement.LSPOSED_TARGET_SCOPE)
            return PermissionStatus.GRANTED
        }
        return when {
            requesting == PermissionRequirement.LSPOSED_TARGET_SCOPE -> PermissionStatus.REQUESTING
            PermissionRequirement.LSPOSED_TARGET_SCOPE in deniedPermissionsThisVisibility -> PermissionStatus.DENIED
            else -> PermissionStatus.MISSING
        }
    }

    private fun shellExecutorStatus(requesting: PermissionRequirement?): PermissionStatus {
        return when (CommandUtil.serviceStatus.value) {
            is CommandUtil.ServiceStatus.Active -> PermissionStatus.GRANTED
            is CommandUtil.ServiceStatus.Loading -> PermissionStatus.REQUESTING
            is CommandUtil.ServiceStatus.Inactive,
            is CommandUtil.ServiceStatus.Error -> {
                if (requesting == PermissionRequirement.SHELL_EXECUTOR) {
                    PermissionStatus.REQUESTING
                } else if (isShizukuPermissionMissing()) {
                    if (PermissionRequirement.SHELL_EXECUTOR in deniedPermissionsThisVisibility) {
                        PermissionStatus.DENIED
                    } else {
                        PermissionStatus.MISSING
                    }
                } else {
                    PermissionStatus.UNAVAILABLE
                }
            }
        }
    }

    private fun isShizukuPermissionMissing(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                checkSelfPermission(ShizukuProvider.PERMISSION) != PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun markPermissionGranted(requirement: PermissionRequirement) {
        deniedPermissionsThisVisibility.remove(requirement)
        // 自动闭环只在当前可见周期内抑制重复弹窗；一旦权限恢复，后续再次被撤销时应允许重新申请。
        requestedPermissionsThisVisibility.remove(requirement)
    }

    private fun targetExactAlarmStatus(
        targetInstalled: Boolean,
        targetBatteryIgnored: Boolean,
        manifestState: ExactAlarmManifestState,
        hookExactAlarmAllowed: Boolean?,
        requesting: PermissionRequirement?
    ): PermissionStatus {
        if (!targetInstalled) return PermissionStatus.UNAVAILABLE
        if (
            hookExactAlarmAllowed == true ||
            manifestState.isAlwaysGranted ||
            targetBatteryIgnored
        ) {
            markPermissionGranted(PermissionRequirement.TARGET_EXACT_ALARM)
            return PermissionStatus.GRANTED
        }
        if (requesting == PermissionRequirement.TARGET_EXACT_ALARM) {
            return PermissionStatus.REQUESTING
        }
        if (hookExactAlarmAllowed == false) return PermissionStatus.MISSING
        if (!manifestState.requiresManualSettingsCheck) {
            return PermissionStatus.UNSUPPORTED
        }
        return PermissionStatus.UNAVAILABLE
    }

    private fun packageExactAlarmManifestState(packageName: String): ExactAlarmManifestState {
        val info = packageInfo(packageName)
        val requestedPermissions = info?.requestedPermissions?.toSet().orEmpty()
        return ExactAlarmManifestState(
            targetSdkVersion = info?.applicationInfo?.targetSdkVersion,
            requestsScheduleExactAlarm = Manifest.permission.SCHEDULE_EXACT_ALARM in requestedPermissions,
            usesExactAlarm = Manifest.permission.USE_EXACT_ALARM in requestedPermissions
        )
    }

    private fun packageInfo(packageName: String) = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    }.getOrNull()
}
