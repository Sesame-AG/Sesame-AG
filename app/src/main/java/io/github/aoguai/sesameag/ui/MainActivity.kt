package io.github.aoguai.sesameag.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.SesameApplication.Companion.hasPermissions
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.service.ConnectionState
import io.github.aoguai.sesameag.service.LsposedServiceManager
import io.github.aoguai.sesameag.ui.extension.openUrl
import io.github.aoguai.sesameag.ui.extension.performNavigationToSettings
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthItem
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.permissions.PermissionPolicy
import io.github.aoguai.sesameag.ui.permissions.PermissionRequirement
import io.github.aoguai.sesameag.ui.permissions.PermissionStatus
import io.github.aoguai.sesameag.ui.screen.MainScreen
import io.github.aoguai.sesameag.ui.theme.AppTheme
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.IconManager
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.ToastUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

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
    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onRuntimePermissionRequestFinished(result)
        }
    private val exactAlarmPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onExactAlarmPermissionRequestFinished(result.resultCode)
        }
    private var pendingPermissionRequest: PermissionRequirement? = null
    private var activePermissionMode: PermissionRequestMode? = null
    private var activePermissionOrder: List<PermissionRequirement> = emptyList()
    private val requestedPermissionsThisVisibility = linkedSetOf<PermissionRequirement>()
    private val requestedPermissionsThisQueue = linkedSetOf<PermissionRequirement>()
    private val deniedPermissionsThisVisibility = linkedSetOf<PermissionRequirement>()
    private val grantedPermissionsThisVisibility = linkedSetOf<PermissionRequirement>()
    private var latestTargetPermissionSnapshot: TargetPermissionSnapshot? = null
    private var awaitingTargetPermissionSnapshot = false
    private var pendingTargetPermissionSnapshotToken: Long = 0L
    private var pendingPermissionCheckCallback: ((Boolean) -> Unit)? = null

    private val autoCriticalPermissions = listOf(
        PermissionRequirement.MODULE_FILE,
        PermissionRequirement.MODULE_NOTIFICATION,
        PermissionRequirement.LSPOSED_TARGET_SCOPE
    )

    private val manualPermissionOrder = listOf(
        PermissionRequirement.MODULE_FILE,
        PermissionRequirement.MODULE_NOTIFICATION,
        PermissionRequirement.LSPOSED_TARGET_SCOPE,
        PermissionRequirement.MODULE_EXACT_ALARM,
        PermissionRequirement.TARGET_EXACT_ALARM,
        PermissionRequirement.MODULE_BATTERY,
        PermissionRequirement.TARGET_BATTERY,
        PermissionRequirement.SHELL_EXECUTOR
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
                    intent.extras?.get("targetExactAlarmAllowed") as? Boolean
                } else {
                    null
                }
            ).takeIf { it.available && it.contextPackage == General.PACKAGE_NAME }
            awaitingTargetPermissionSnapshot = false
            pendingTargetPermissionSnapshotToken = 0L
            refreshPermissionHealth()
            if (activePermissionMode == PermissionRequestMode.MANUAL_CARD && pendingPermissionRequest == null) {
                finishPermissionCheckFromCard()
            }
        }
    }

    // Shizuku 监听器
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
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
                    refreshPermissionHealth()
                    if (activePermissionMode == PermissionRequestMode.MANUAL_CARD ||
                        pendingPermissionRequest == PermissionRequirement.SHELL_EXECUTOR
                    ) {
                        continuePermissionQueueOrAuto()
                    }
                }
            }
        }
        continuePermissionQueueOrAuto()

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
            val permissionHealth by viewModel.permissionHealth.collectAsStateWithLifecycle()
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()

            // AppTheme 会处理状态栏颜色
            AppTheme(dynamicColor = isDynamicColor) {
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
                    // 🔥 处理跳转逻辑
                    onNavigateToSettings = { selectedUser ->
                        performNavigationToSettings(selectedUser)
                    },
                    onEvent = { event -> handleEvent(event) }
                )
            }
        }
    }

    /**
     * 定义 UI 事件
     */
    sealed class MainUiEvent {
        data object RefreshOneWord : MainUiEvent()
        data class OpenLog(val channel: LogChannel) : MainUiEvent()
        data object OpenGithub : MainUiEvent()
        data class ToggleIconHidden(val isHidden: Boolean) : MainUiEvent()
        data object OpenExtend : MainUiEvent()
        data object ClearConfig : MainUiEvent()
        data class RequestPermissionCheck(val onCompleted: (Boolean) -> Unit = {}) : MainUiEvent()
    }

    /**
     * 统一处理事件
     */
    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            MainUiEvent.RefreshOneWord -> viewModel.fetchOneWord()
            is MainUiEvent.OpenLog -> openLogChannel(event.channel)
            MainUiEvent.OpenGithub -> openUrl(General.PROJECT_HOMEPAGE_URL)
            is MainUiEvent.RequestPermissionCheck -> {
                requestAllPermissionsFromCard(event.onCompleted)
            }
            is MainUiEvent.ToggleIconHidden -> {
                val shouldHide = event.isHidden
                getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
            }

            MainUiEvent.OpenExtend -> startActivity(Intent(this, ExtendActivity::class.java))
            MainUiEvent.ClearConfig -> {
                // 🔥 这里只负责执行逻辑，不再负责弹窗
                if (Files.delFile(Files.CONFIG_DIR)) {
                    ToastUtil.showToast(this, "🙂 清空配置成功")
                    // 可选：重载配置或刷新 UI
                    viewModel.refreshUserConfigs()
                } else {
                    ToastUtil.showToast(this, "😭 清空配置失败")
                }
            }
        }
    }

    private fun openLogChannel(channel: LogChannel) {
        openLogFile(Files.getLogFile(channel))
    }

    // --- 辅助方法 ---

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

    override fun onResume() {
        super.onResume()
        val pendingBeforeResume = pendingPermissionRequest
        requestTargetPermissionSnapshot(clearCached = true)
        LsposedServiceManager.refreshScope()
        if (
            pendingBeforeResume == PermissionRequirement.LSPOSED_TARGET_SCOPE &&
            LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)
        ) {
            markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
        } else if (!shouldKeepPendingPermissionOnResume(pendingBeforeResume)) {
            pendingPermissionRequest = null
        }
        CommandUtil.connect(applicationContext)
        continuePermissionQueueOrAuto()
        if (hasPermissions) viewModel.refreshUserConfigs()
        refreshPermissionHealth()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            CommandUtil.unbind(applicationContext)
        }
        if (!isChangingConfigurations && pendingPermissionRequest == null) {
            requestedPermissionsThisVisibility.clear()
            deniedPermissionsThisVisibility.clear()
            grantedPermissionsThisVisibility.clear()
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

    private fun openLogFile(logFile: File) {
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件不存在: ${logFile.name}")
            return
        }
        val intent = Intent(this, LogViewerActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }

    private fun onRuntimePermissionRequestFinished(result: Map<String, Boolean>) {
        val deniedPermission = pendingPermissionRequest
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

    private fun onExactAlarmPermissionRequestFinished(resultCode: Int) {
        val permission = pendingPermissionRequest
        if (
            permission == PermissionRequirement.MODULE_EXACT_ALARM ||
            permission == PermissionRequirement.TARGET_EXACT_ALARM
        ) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                grantedPermissionsThisVisibility.add(permission)
                deniedPermissionsThisVisibility.remove(permission)
            } else {
                grantedPermissionsThisVisibility.remove(permission)
                deniedPermissionsThisVisibility.add(permission)
            }
        }
        pendingPermissionRequest = null
        continuePermissionQueueOrAuto()
    }

    private fun requestAllPermissionsFromCard(onCompleted: (Boolean) -> Unit = {}) {
        pendingPermissionCheckCallback = onCompleted
        activePermissionMode = PermissionRequestMode.MANUAL_CARD
        activePermissionOrder = manualPermissionOrder
        requestedPermissionsThisQueue.clear()
        LsposedServiceManager.refreshScope()
        CommandUtil.connect(applicationContext)
        awaitingTargetPermissionSnapshot = requestTargetPermissionSnapshot(clearCached = true)
        if (awaitingTargetPermissionSnapshot) {
            refreshPermissionHealth()
            lifecycleScope.launch {
                delay(350)
                if (
                    awaitingTargetPermissionSnapshot &&
                    activePermissionMode == PermissionRequestMode.MANUAL_CARD &&
                    pendingPermissionRequest == null
                ) {
                    awaitingTargetPermissionSnapshot = false
                    pendingTargetPermissionSnapshotToken = 0L
                    finishPermissionCheckFromCard()
                }
            }
            return
        }
        finishPermissionCheckFromCard()
    }

    private fun finishPermissionCheckFromCard() {
        val requested = runPermissionQueue(manualPermissionOrder, PermissionRequestMode.MANUAL_CARD)
        val snapshot = refreshPermissionHealth()
        if (!requested && !snapshot.hasCriticalIssue && snapshot.attentionCount == 0) {
            ToastUtil.showToast(this, "权限检查已完成")
        }
        completePendingPermissionCheck(!requested && !snapshot.hasRequestableIssue)
    }

    private fun completePendingPermissionCheck(canToggleDetails: Boolean) {
        pendingPermissionCheckCallback?.invoke(canToggleDetails)
        pendingPermissionCheckCallback = null
    }

    private fun continuePermissionQueueOrAuto() {
        if (pendingPermissionRequest != null) {
            refreshPermissionHealth()
            return
        }
        if (activePermissionMode == PermissionRequestMode.MANUAL_CARD && awaitingTargetPermissionSnapshot) {
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
        hasPermissions = PermissionUtil.checkFilePermissions(this)
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
            markRequestFinished(permission)
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
        return when (permission) {
            PermissionRequirement.MODULE_FILE -> {
                PermissionUtil.checkOrRequestFilePermissions(this, runtimePermissionsLauncher)
            }

            PermissionRequirement.MODULE_NOTIFICATION -> {
                PermissionUtil.checkOrRequestNotificationPermission(this, runtimePermissionsLauncher)
            }

            PermissionRequirement.LSPOSED_TARGET_SCOPE -> requestLsposedTargetScope()

            PermissionRequirement.MODULE_EXACT_ALARM -> {
                PermissionUtil.checkOrRequestExactAlarmPermissions(
                    this,
                    packageName,
                    exactAlarmPermissionLauncher
                )
            }

            PermissionRequirement.TARGET_EXACT_ALARM -> {
                PermissionUtil.checkOrRequestExactAlarmPermissions(
                    this,
                    General.PACKAGE_NAME,
                    exactAlarmPermissionLauncher
                )
            }

            PermissionRequirement.MODULE_BATTERY -> {
                PermissionUtil.checkOrRequestBatteryPermissions(this, packageName)
            }

            PermissionRequirement.TARGET_BATTERY -> {
                PermissionUtil.checkOrRequestBatteryPermissions(this, General.PACKAGE_NAME)
            }

            PermissionRequirement.SHELL_EXECUTOR -> requestShellExecutor()
        }
    }

    private fun requestLsposedTargetScope(): Boolean {
        val sent = LsposedServiceManager.requestTargetScope { result ->
            runOnUiThread {
                markRequestFinished(PermissionRequirement.LSPOSED_TARGET_SCOPE)
                if (result.success) {
                    ToastUtil.showToast(this, "LSPosed 作用域已更新")
                } else if (result.message.isNotBlank()) {
                    ToastUtil.showToast(this, "LSPosed 作用域申请失败: ${result.message}")
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
            awaitingTargetPermissionSnapshot = false
            pendingTargetPermissionSnapshotToken = 0L
            return false
        }
        val requestToken = System.nanoTime()
        pendingTargetPermissionSnapshotToken = requestToken
        sendBroadcast(
            Intent(ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT).apply {
                setPackage(General.PACKAGE_NAME)
                putExtra("requestToken", requestToken)
            }
        )
        return true
    }

    private fun markRequestFinished(permission: PermissionRequirement) {
        if (pendingPermissionRequest == permission) {
            pendingPermissionRequest = null
        }
    }

    private fun shouldKeepPendingPermissionOnResume(permission: PermissionRequirement?): Boolean {
        return permission == PermissionRequirement.LSPOSED_TARGET_SCOPE ||
            permission == PermissionRequirement.MODULE_EXACT_ALARM ||
            permission == PermissionRequirement.TARGET_EXACT_ALARM
    }

    private fun clearActivePermissionQueue() {
        activePermissionMode = null
        activePermissionOrder = emptyList()
        requestedPermissionsThisQueue.clear()
    }

    private fun refreshPermissionHealth(): PermissionHealthSnapshot {
        val snapshot = buildPermissionHealthSnapshot(pendingPermissionRequest)
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
        val targetExactAlarmGrantedThisVisibility =
            PermissionRequirement.TARGET_EXACT_ALARM in grantedPermissionsThisVisibility
        val targetExactAlarmDeniedThisVisibility =
            PermissionRequirement.TARGET_EXACT_ALARM in deniedPermissionsThisVisibility
        val targetExactAlarmManifest = packageExactAlarmManifestState(General.PACKAGE_NAME)
        val targetExactAlarmPermissionStatus = targetExactAlarmStatus(
            targetInstalled = targetInstalled,
            targetBatteryIgnored = targetBatteryIgnored,
            manifestState = targetExactAlarmManifest,
            hookExactAlarmAllowed = targetPermissionSnapshot?.targetExactAlarmAllowed,
            requesting = requesting
        )
        val targetExactAlarmDescription = when {
            !targetInstalled -> "未检测到目标应用，安装并打开后才能检查目标应用的精确闹钟状态"
            targetExactAlarmGrantedThisVisibility -> "本次返回模块后已收到目标应用精确闹钟授权结果"
            targetPermissionSnapshot?.targetExactAlarmAllowed == true -> "目标应用已上报精确闹钟可用"
            targetExactAlarmDeniedThisVisibility -> "本次返回模块后仍未授予目标应用精确闹钟，请在目标应用系统设置页内完成授权"
            targetPermissionSnapshot?.targetExactAlarmAllowed == false -> {
                "目标应用已上报精确闹钟未授权，请在目标应用系统设置页内完成授权"
            }
            targetBatteryIgnored -> "目标包已在电池优化白名单内，系统允许使用精确闹钟"
            targetExactAlarmManifest.isAlwaysGranted -> "当前目标包无需额外授权即可使用精确闹钟"
            targetExactAlarmManifest.requiresManualSettingsCheck -> {
                "Android 无法可靠读取跨包授权结果，请先打开目标应用触发授权，再到系统设置里人工确认"
            }

            else -> "目标包未声明可由模块引导的精确闹钟授权入口，通常需要在系统设置中手动确认"
        }
        val items = listOf(
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_FILE,
                status = statusForGranted(
                    PermissionRequirement.MODULE_FILE,
                    PermissionUtil.checkFilePermissions(this),
                    requesting
                ),
                policy = PermissionPolicy.AUTO_CRITICAL,
                title = "模块文件访问",
                description = "用于读取与写入配置、日志和调试数据；首次使用请先完成这一项",
                actionLabel = "申请文件权限"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.MODULE_NOTIFICATION,
                status = statusForGranted(
                    PermissionRequirement.MODULE_NOTIFICATION,
                    PermissionUtil.checkNotificationPermission(this),
                    requesting
                ),
                policy = PermissionPolicy.AUTO_CRITICAL,
                title = "模块通知",
                description = "用于显示运行、异常和命令服务通知；授权后返回本页即可继续检查",
                actionLabel = "申请通知权限"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.LSPOSED_TARGET_SCOPE,
                status = lsposedScopeStatus(targetInstalled, requesting),
                policy = PermissionPolicy.AUTO_CRITICAL,
                title = "LSPosed 目标应用作用域",
                description = "首次使用请把目标应用加入模块作用域，然后重新打开目标应用或返回本页复查",
                actionLabel = if (targetInstalled) "申请作用域" else null
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
                description = "用于提高模块侧持久调度的准点性；系统弹出授权页后返回本页即可",
                actionLabel = "申请精确闹钟"
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.TARGET_EXACT_ALARM,
                status = targetExactAlarmPermissionStatus,
                policy = PermissionPolicy.MANUAL_CARD,
                title = "目标应用精确闹钟",
                description = targetExactAlarmDescription,
                actionLabel = if (targetInstalled) "申请目标精确闹钟" else null,
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
                description = "降低模块侧后台服务和调度被系统限制的概率；通常需要在系统设置中确认",
                actionLabel = "申请模块电池权限"
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
                description = "降低目标应用进程和 Hook 工作流被系统限制的概率；处理完后请回到模块复查",
                actionLabel = if (targetInstalled) "申请目标电池权限" else null
            ),
            PermissionHealthItem(
                requirement = PermissionRequirement.SHELL_EXECUTOR,
                status = shellExecutorStatus(requesting),
                policy = PermissionPolicy.MANUAL_CARD,
                title = "Root/Shizuku 执行器",
                description = "用于诊断和辅助命令，不影响普通自动任务；需要时再处理即可",
                actionLabel = if (isShizukuPermissionMissing()) "申请 Shizuku 授权" else null
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
        val state = LsposedServiceManager.connectionState
        if (state !is ConnectionState.Connected) return PermissionStatus.UNAVAILABLE
        val apiVersion = runCatching { state.service.apiVersion }.getOrDefault(0)
        if (apiVersion < 101) return PermissionStatus.UNSUPPORTED
        if (LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)) {
            markPermissionGranted(PermissionRequirement.LSPOSED_TARGET_SCOPE)
            return PermissionStatus.GRANTED
        }
        return if (requesting == PermissionRequirement.LSPOSED_TARGET_SCOPE) {
            PermissionStatus.REQUESTING
        } else {
            PermissionStatus.MISSING
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
            targetBatteryIgnored ||
            PermissionRequirement.TARGET_EXACT_ALARM in grantedPermissionsThisVisibility
        ) {
            markPermissionGranted(PermissionRequirement.TARGET_EXACT_ALARM)
            return PermissionStatus.GRANTED
        }
        if (requesting == PermissionRequirement.TARGET_EXACT_ALARM) {
            return PermissionStatus.REQUESTING
        }
        if (PermissionRequirement.TARGET_EXACT_ALARM in deniedPermissionsThisVisibility) {
            return PermissionStatus.DENIED
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
