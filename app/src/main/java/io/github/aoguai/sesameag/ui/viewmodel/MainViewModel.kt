package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.AccountSlotSnapshot
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import io.github.aoguai.sesameag.service.ConnectionState
import io.github.aoguai.sesameag.service.LsposedServiceManager
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.permissions.PermissionRequirement
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.DirectoryWatcher
import io.github.aoguai.sesameag.util.SesameAgUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.IconManager
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogCatalog
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.ModuleDiagnostics
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主界面 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {



    // --- 内部状态定义 ---
    sealed class ModuleStatus {
        enum class UnsupportedReason {
            API_TOO_LOW,
            NON_LSPOSED
        }

        data object Loading : ModuleStatus()
        data object NotActivated : ModuleStatus()
        data class Unsupported(
            val frameworkName: String,
            val frameworkVersion: String,
            val apiVersion: Int,
            val reason: UnsupportedReason
        ) : ModuleStatus()
        data class PrerequisitesMissing(
            val frameworkName: String,
            val frameworkVersion: String,
            val apiVersion: Int,
        ) : ModuleStatus()
        data class Activated(
            val frameworkName: String,     // 仅支持维护的框架名称 (LSPosed)
            val frameworkVersion: String,  // 版本号
            val apiVersion: Int            // API版本
        ) : ModuleStatus()
    }



    companion object {
        private const val TAG = "MainViewModel"
    }

    sealed interface AccountConfigState {
        data object Loading : AccountConfigState
        data class Ready(val userIds: List<String>) : AccountConfigState
        data object Unavailable : AccountConfigState
    }

    private val _accountConfigState = MutableStateFlow<AccountConfigState>(AccountConfigState.Loading)
    private val _accountGuide = MutableStateFlow<String?>(null)
    val accountGuide = _accountGuide.asStateFlow()
    private var foregroundActive = false
    private var homeVisible = false
    private var guidanceShown = false
    private var skipNextForegroundGuide = false
    private var permissionQueueBusy = false
    private var homeDialogVisible = false
    private var configRefreshJob: Job? = null
    private var legalRefreshJob: Job? = null
    private var collectionJob: Job? = null
    private var retriedCollection = false

    // 1. 定义状态
    private val _oneWord = MutableStateFlow("正在获取句子...")
    val oneWord: StateFlow<String> = _oneWord.asStateFlow()

    private val _isOneWordLoading = MutableStateFlow(false)
    val isOneWordLoading = _isOneWordLoading.asStateFlow()

    private val _moduleStatus = MutableStateFlow<ModuleStatus>(ModuleStatus.Loading)
    val moduleStatus: StateFlow<ModuleStatus> = _moduleStatus.asStateFlow()

    private val _activeUser = MutableStateFlow<UserEntity?>(null)
    val activeUser: StateFlow<UserEntity?> = _activeUser.asStateFlow()

    private val _userList = MutableStateFlow<List<UserEntity>>(emptyList())
    val userList: StateFlow<List<UserEntity>> = _userList.asStateFlow()

    private val _accountSlots = MutableStateFlow(AccountSlotSnapshot(
        migrationState = io.github.aoguai.sesameag.hook.AccountSlotMigrationState.READY,
        activeUserIds = emptyList(), candidateUserIds = emptyList(), errorCode = "storage_not_ready",
    ))
    val accountSlots: StateFlow<AccountSlotSnapshot> = _accountSlots.asStateFlow()

    private val _isLegalAccepted = MutableStateFlow(false)
    val isLegalAccepted: StateFlow<Boolean> = _isLegalAccepted.asStateFlow()

    private val _isSavingLegalAcceptance = MutableStateFlow(false)
    val isSavingLegalAcceptance = _isSavingLegalAcceptance.asStateFlow()

    private val _permissionHealth = MutableStateFlow(PermissionHealthSnapshot.EMPTY)
    val permissionHealth: StateFlow<PermissionHealthSnapshot> = _permissionHealth.asStateFlow()

    // --- 监听器 ---

    // 监听 LSPosed 服务连接 (仅用于更新详细版本信息)
    private val serviceListener: (ConnectionState) -> Unit = { _ ->
        LsposedServiceManager.refreshScope()
        refreshModuleFrameworkStatus()
    }

    private val accountContextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApplicationHookConstants.BroadcastActions.ACCOUNT_CONTEXT_CHANGED) {
                return
            }
            applyAccountContext(intent)
            refreshUserConfigs()
        }
    }

    private var isInitialized = false
    private var accountContextReceiverRegistered = false

    init {
        LsposedServiceManager.init()
        LsposedServiceManager.addConnectionListener(serviceListener)
    }

    fun initAppLogic(): Boolean {
        if (!io.github.aoguai.sesameag.storage.PrivateStorage.isReady) return false
        if (isInitialized) return false
        isInitialized = true
        registerAccountContextReceiver()

        viewModelScope.launch(Dispatchers.IO) {
            initEnvironment()

            // 加载初始数据
            refreshUserConfigs()
            fetchOneWord()
            // 初始检查状态
            refreshModuleFrameworkStatus()
            refreshActiveUser()
            refreshLegalAcceptanceState()
            startConfigDirectoryObserver()
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        LsposedServiceManager.removeConnectionListener(serviceListener)
        unregisterAccountContextReceiver()
    }



    /**
     * 刷新模块框架激活状态
     */
    fun refreshModuleFrameworkStatus() {
        val config = _accountConfigState.value
        val userId = _activeUser.value?.userId
        val slots = _accountSlots.value
        val frameworkStatus = LsposedServiceManager.connectedFrameworkStatus()
        _moduleStatus.value = if (LsposedServiceManager.connectionState !is ConnectionState.Connected || frameworkStatus == null) {
            ModuleStatus.NotActivated
        } else if (frameworkStatus.isSupportedLsposed) {
            if (_permissionHealth.value.areRequiredPermissionsGranted &&
                _isLegalAccepted.value && !_isSavingLegalAcceptance.value
            ) {
                ModuleStatus.Activated(
                    frameworkName = frameworkStatus.frameworkName,
                    frameworkVersion = frameworkStatus.frameworkVersion,
                    apiVersion = frameworkStatus.apiVersion
                )
            } else {
                ModuleStatus.PrerequisitesMissing(
                    frameworkName = frameworkStatus.frameworkName,
                    frameworkVersion = frameworkStatus.frameworkVersion,
                    apiVersion = frameworkStatus.apiVersion,
                )
            }
        } else {
            ModuleStatus.Unsupported(
                frameworkName = frameworkStatus.frameworkName,
                frameworkVersion = frameworkStatus.frameworkVersion,
                apiVersion = frameworkStatus.apiVersion,
                reason = if (!frameworkStatus.hasRequiredApi) {
                    ModuleStatus.UnsupportedReason.API_TOO_LOW
                } else {
                    ModuleStatus.UnsupportedReason.NON_LSPOSED
                }
            )
        }
        ModuleDiagnostics.state(_permissionHealth.value, config.javaClass.simpleName,
            (config as? AccountConfigState.Ready)?.userIds?.size, userId, _isLegalAccepted.value, slots)
        maybeShowAccountGuide()
    }

    fun onForegroundStarted() {
        if (!foregroundActive) {
            foregroundActive = true
            guidanceShown = skipNextForegroundGuide
            _accountConfigState.value = AccountConfigState.Loading
            ModuleDiagnostics.event("foreground", "entered")
        }
        skipNextForegroundGuide = false
        maybeShowAccountGuide()
    }

    fun onForegroundStopped() {
        foregroundActive = false
        _accountGuide.value = null
    }

    fun markExternalNavigation() {
        skipNextForegroundGuide = true
        guidanceShown = true
        _accountGuide.value = null
    }

    fun cancelExternalNavigation() { skipNextForegroundGuide = false }

    fun setPermissionQueueBusy(busy: Boolean) {
        permissionQueueBusy = busy
        maybeShowAccountGuide()
    }

    fun setHomeDialogVisible(visible: Boolean) {
        homeDialogVisible = visible
        maybeShowAccountGuide()
    }

    fun resetAccountGuide() {
        guidanceShown = false
        skipNextForegroundGuide = false
        _accountGuide.value = null
        _accountConfigState.value = AccountConfigState.Loading
    }

    fun setHomeVisible(visible: Boolean) {
        if (homeVisible == visible) return
        homeVisible = visible
        if (!visible) {
            collectionJob?.cancel()
            _accountGuide.value = null
            return
        }
        retriedCollection = CommandUtil.serviceStatus.value is CommandUtil.ServiceStatus.Active
        collectHomeDiagnostics("home_enter")
        maybeShowAccountGuide()
    }

    fun onExecutorStateChanged() {
        if (homeVisible && !retriedCollection && CommandUtil.serviceStatus.value is CommandUtil.ServiceStatus.Active) {
            retriedCollection = true
            collectionJob?.cancel()
            collectHomeDiagnostics("executor_ready")
        }
    }

    private fun collectHomeDiagnostics(reason: String) {
        val previous = collectionJob
        collectionJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            try {
                ModuleDiagnostics.environment(getApplication(), reason)
                ModuleDiagnostics.collectLogcat(getApplication(), reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModuleDiagnostics.event("home_diagnostics", "failed", "error=${e.javaClass.simpleName}")
            }
        }
    }

    private fun maybeShowAccountGuide() {
        val config = _accountConfigState.value as? AccountConfigState.Ready ?: return
        if (config.userIds.isNotEmpty()) {
            _accountGuide.value = null
            return
        }
        if (!foregroundActive || !homeVisible || guidanceShown || permissionQueueBusy || homeDialogVisible) return
        if (LsposedServiceManager.connectedFrameworkStatus()?.isSupportedLsposed != true) return
        if (_permissionHealth.value.item(PermissionRequirement.MODULE_FILE)?.isGranted != true ||
            _permissionHealth.value.item(PermissionRequirement.LSPOSED_TARGET_SCOPE)?.isGranted != true) return
        guidanceShown = true
        _accountGuide.value = "initial"
        ModuleDiagnostics.event("account_guide", "shown", "trigger=foreground")
    }

    fun showAccountGuideForLegal() {
        guidanceShown = true
        val reason = when (_accountConfigState.value) {
            AccountConfigState.Loading -> "loading"
            AccountConfigState.Unavailable -> "unreadable"
            is AccountConfigState.Ready -> "legal"
        }
        _accountGuide.value = reason
        ModuleDiagnostics.event("legal_acceptance", "account_unavailable", "reason=$reason")
    }

    fun dismissAccountGuide() {
        if (_accountGuide.value == null) return
        _accountGuide.value = null
        ModuleDiagnostics.event("account_guide", "dismissed")
    }

    /**
     * 刷新当前激活用户
     * 从 DataStore (文件) 读取
     */
    private fun refreshActiveUser() {
        try {
            val activeUserEntity = DataStore.get("activedUser", UserEntity::class.java)
            val resolvedActiveUser = activeUserEntity ?: recoverActiveUserSnapshot()
            if (_activeUser.value?.userId != resolvedActiveUser?.userId) _isLegalAccepted.value = false
            _activeUser.value = resolvedActiveUser
            UserMap.setCurrentUserId(resolvedActiveUser?.userId?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            Log.e(TAG, "Read active user failed", e)
            _activeUser.value = null
            UserMap.setCurrentUserId(null)
        }
    }

    private fun recoverActiveUserSnapshot(): UserEntity? {
        val fallbackUserId = UserMap.currentUid
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: resolveExistingUserConfigIds().singleOrNull()
            ?: return null
        val snapshot = UserMap.readSelf(fallbackUserId) ?: return null
        runCatching { DataStore.put("activedUser", snapshot) }
            .onFailure { Log.w(TAG, "Recover active user snapshot failed: ${it.message}") }
        return snapshot
    }

    private fun applyAccountContext(intent: Intent) {
        val sessionUserId = intent.getStringExtra("userId")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val snapshotUserId = intent.getStringExtra("activeUserId")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val activeUserId = snapshotUserId ?: sessionUserId
        val snapshotShowName = intent.getStringExtra("activeUserShowName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val snapshotNickName = intent.getStringExtra("activeUserNickName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val snapshotRemarkName = intent.getStringExtra("activeUserRemarkName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val snapshotRealName = intent.getStringExtra("activeUserRealName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (activeUserId != null) {
            _activeUser.value = UserEntity(
                userId = activeUserId,
                account = intent.getStringExtra("activeUserAccount"),
                friendStatus = if (intent.hasExtra("activeUserFriendStatus")) {
                    intent.getIntExtra("activeUserFriendStatus", 0)
                } else {
                    null
                },
                realName = snapshotRealName,
                nickName = snapshotNickName ?: snapshotShowName ?: activeUserId,
                remarkName = snapshotRemarkName
            )
            UserMap.setCurrentUserId(activeUserId)
        } else {
            refreshActiveUser()
        }

        if (intent.hasExtra("legalAccepted")) {
            _isLegalAccepted.value = intent.getBooleanExtra("legalAccepted", false)
        } else {
            refreshLegalAcceptanceState()
        }
    }

    @OptIn(FlowPreview::class)
    private fun startConfigDirectoryObserver() {
        viewModelScope.launch(Dispatchers.IO) {
                DirectoryWatcher.observeDirectoryChanges(Files.CONFIG_DIR)
                // 合并同一轮配置写入产生的连续目录事件，避免反复刷新账号与配置列表。
                .debounce(100)
                .collectLatest {
                    refreshUserConfigs()
                    refreshActiveUser()
                    refreshLegalAcceptanceState()
                }
        }
    }

    /**
     * 刷新用户配置
     */
    fun refreshUserConfigs() {
        configRefreshJob?.cancel()
        configRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                io.github.aoguai.sesameag.storage.PrivateStorage.requireReady()
                val latestUserIds = Files.listExistingUserConfigIds(requireReadable = true)
                val newList = mutableListOf<UserEntity>()
                for (userId in latestUserIds) {
                    UserMap.readSelf(userId)?.let { newList.add(it) }
                }
                currentCoroutineContext().ensureActive()
                _userList.value = newList
                _accountSlots.value = AccountSlotRegistry.snapshot()
                refreshActiveUser()
                _accountConfigState.value = AccountConfigState.Ready(latestUserIds)
                refreshLegalAcceptanceState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _accountConfigState.value = AccountConfigState.Unavailable
                _isLegalAccepted.value = false
                ModuleDiagnostics.event("account_config", "unreadable", "error=${e.javaClass.simpleName}")
                Log.e(TAG, "Error reloading user configs", e)
            } finally {
                refreshModuleFrameworkStatus()
            }
        }
    }


    private fun initEnvironment() {
        try {
            LsposedServiceManager.init()
            DataStore.init(Files.CONFIG_DIR)
        } catch (e: Exception) {
            Log.e(TAG, "Environment init failed", e)
        }
    }

    private fun registerAccountContextReceiver() {
        if (accountContextReceiverRegistered) return
        val application = getApplication<Application>()
        val filter = IntentFilter(ApplicationHookConstants.BroadcastActions.ACCOUNT_CONTEXT_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(accountContextReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(accountContextReceiver, filter)
            }
            accountContextReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Register account context receiver failed", e)
        }
    }

    private fun unregisterAccountContextReceiver() {
        if (!accountContextReceiverRegistered) return
        val application = getApplication<Application>()
        try {
            application.unregisterReceiver(accountContextReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Unregister account context receiver failed", e)
        } finally {
            accountContextReceiverRegistered = false
        }
    }

    fun clearAllTodayFlags(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ModuleDiagnostics.event("clear_daily_flags", "requested", "account=${ModuleDiagnostics.account(userId)}")
            val result = Status.clearAllTodayFlagsForUser(userId)
            ModuleDiagnostics.event("clear_daily_flags", if (result.isSuccess) "completed" else "failed",
                "account=${ModuleDiagnostics.account(userId)} written=${result.written} removed=${result.removedCount}")
            if (result.isSuccess && result.written) {
                getApplication<Application>().sendBroadcast(
                    Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                        putExtra("userId", result.userId)
                    },
                )
            }
            val message = when {
                !result.isSuccess -> result.errorMessage ?: "每日标识清除失败"
                !result.written -> "没有可删除的每日标识"
                else -> "已删除 ${result.removedCount} 个每日标识"
            }
            withContext(Dispatchers.Main) {
                ToastUtil.showUiToast(getApplication(), message)
            }
        }
    }

    fun setExecutableAccountSlot(userId: String?, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ModuleDiagnostics.event("account_slot", "requested", "account=${ModuleDiagnostics.account(userId)} enabled=$enabled")
            val result = if (enabled) {
                AccountSlotRegistry.addExecutableSlot(getApplication<Application>(), userId)
            } else {
                AccountSlotRegistry.removeExecutableSlot(getApplication<Application>(), userId)
            }
            ModuleDiagnostics.event("account_slot", if (result.replaced) "completed" else "rejected",
                "account=${ModuleDiagnostics.account(userId)} enabled=$enabled reason=${result.reasonCode ?: "none"}")
            if (!result.replaced) {
                val message = when (result.reasonCode) {
                    "registry_unavailable" -> "可执行账号配置暂不可读取，请稍后重试"
                    "account_slot_full" -> "可执行槽位已满"
                    "unknown_slot_candidate" -> "账号配置已变化，请刷新后重试"
                    else -> "可执行账号操作失败，请稍后重试"
                }
                ToastUtil.showUiToast(getApplication(), message)
            }
            refreshUserConfigs()
        }
    }

    fun setLegalAccepted(accepted: Boolean) {
        val targetUserId = _activeUser.value?.userId?.trim()?.takeIf { it.isNotEmpty() }
        val config = _accountConfigState.value as? AccountConfigState.Ready
        if (targetUserId == null || config == null || targetUserId !in config.userIds) {
            showAccountGuideForLegal()
            return
        }
        if (!_isSavingLegalAcceptance.compareAndSet(false, true)) return
        ModuleDiagnostics.event("legal_acceptance", "requested", "account=${ModuleDiagnostics.account(targetUserId)} accepted=$accepted")
        refreshModuleFrameworkStatus()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stillPresent = targetUserId in Files.listExistingUserConfigIds(requireReadable = true)
                val saveSuccess = stillPresent && Config.saveLegalAcceptedForCurrentVersion(targetUserId, accepted)
                ModuleDiagnostics.event("legal_acceptance", if (saveSuccess) "saved" else "failed",
                    "account=${ModuleDiagnostics.account(targetUserId)} accepted=$accepted")

                if (!saveSuccess) {
                    Log.e(TAG, "Save legal acceptance failed")
                    ToastUtil.showUiToast(getApplication(), "保存失败，请重试")
                    refreshLegalAcceptanceState()
                    return@launch
                }

                refreshLegalAcceptanceState()
                sendConfigReloadBroadcast(targetUserId)
            } catch (e: Exception) {
                ModuleDiagnostics.event("legal_acceptance", "failed", "account=${ModuleDiagnostics.account(targetUserId)} error=${e.javaClass.simpleName}")
                Log.e(TAG, "Update legal acceptance failed", e)
                ToastUtil.showUiToast(getApplication(), "保存失败，请重试")
                refreshLegalAcceptanceState()
            } finally {
                _isSavingLegalAcceptance.value = false
                refreshModuleFrameworkStatus()
            }
        }
    }

    private fun refreshLegalAcceptanceState() {
        legalRefreshJob?.cancel()
        legalRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeUserId = _activeUser.value?.userId
                val config = _accountConfigState.value as? AccountConfigState.Ready
                val accepted = !activeUserId.isNullOrBlank() && config != null && activeUserId in config.userIds &&
                    Config.readLegalAcceptedForCurrentVersion(activeUserId)
                currentCoroutineContext().ensureActive()
                if (_activeUser.value?.userId == activeUserId) _isLegalAccepted.value = accepted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Read legal acceptance failed", e)
                _isLegalAccepted.value = false
            } finally {
                refreshModuleFrameworkStatus()
            }
        }
    }

    fun fetchOneWord() {
        viewModelScope.launch {
            _isOneWordLoading.value = true
            val result = withContext(Dispatchers.IO) { SesameAgUtil.getOneWord() }
            _oneWord.value = result
            _isOneWordLoading.value = false
        }
    }

    fun syncIconState(isHidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            IconManager.syncIconState(getApplication(), isHidden)
        }
    }

    fun updatePermissionHealth(snapshot: PermissionHealthSnapshot) {
        _permissionHealth.value = snapshot
        if (snapshot.item(PermissionRequirement.MODULE_FILE)?.isGranted != true) {
            _accountConfigState.value = AccountConfigState.Unavailable
            _isLegalAccepted.value = false
            _accountGuide.value = null
        }
        refreshModuleFrameworkStatus()
    }

    fun clearAllLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val logFiles = LogCatalog.loggerNames()
                .filter { it != LogChannel.SYSTEM.loggerName }
                .map { loggerName -> File(Files.LOG_DIR, LogCatalog.fileName(loggerName)) }
                .distinctBy { it.absolutePath }

            val failedCount = logFiles.count { file ->
                file.exists() && !Files.clearFile(file)
            } + if (ModuleDiagnostics.clear(context)) 0 else 1
            ModuleDiagnostics.event("clear_all_logs", if (failedCount == 0) "completed" else "failed", "failed=$failedCount")
            RpcTrafficCapture.resetCaptureSession()

            withContext(Dispatchers.Main) {
                ToastUtil.showUiToast(
                    context,
                    if (failedCount == 0) "所有日志已清空" else "部分日志清空失败：$failedCount"
                )
            }
        }
    }

    private fun resolveActiveUserId(): String? {
        DataStore.get("activedUser", UserEntity::class.java)?.userId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        UserMap.currentUid
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return resolveExistingUserConfigIds().singleOrNull()
    }

    private fun resolveExistingUserConfigIds(): List<String> {
        return Files.listExistingUserConfigIds()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun sendConfigReloadBroadcast(userId: String) {
        try {
            getApplication<Application>().sendBroadcast(
                Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                    putExtra("userId", userId)
                    putExtra("configReload", true)
                }
            )
            ModuleDiagnostics.event("config_reload", "sent", "account=${ModuleDiagnostics.account(userId)}")
        } catch (e: Exception) {
            ModuleDiagnostics.event("config_reload", "failed", "account=${ModuleDiagnostics.account(userId)} error=${e.javaClass.simpleName}")
            throw e
        }
    }
}


