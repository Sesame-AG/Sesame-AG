package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.service.ConnectionState
import io.github.aoguai.sesameag.service.LsposedServiceManager
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.DirectoryWatcher
import io.github.aoguai.sesameag.util.SesameAgUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.IconManager
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogCatalog
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
        data object Loading : ModuleStatus()
        data object NotActivated : ModuleStatus()
        data class Unsupported(
            val frameworkName: String,
            val frameworkVersion: String,
            val apiVersion: Int
        ) : ModuleStatus()
        data class Activated(
            val frameworkName: String,     // 框架名称 (LSPosed, LSPatch...)
            val frameworkVersion: String,  // 版本号 (LSPosed才有，其他可能为空)
            val apiVersion: Int            // API版本
        ) : ModuleStatus()
    }



    companion object {
        const val TAG = "MainViewModel"
    }

    // 1. 定义状态
    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)

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

    private val _isLegalAccepted = MutableStateFlow(false)
    val isLegalAccepted: StateFlow<Boolean> = _isLegalAccepted.asStateFlow()

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

    fun initAppLogic(): Boolean {
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
            // 注册监听
            LsposedServiceManager.addConnectionListener(serviceListener)
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
    private fun refreshModuleFrameworkStatus() {
        val lspState = LsposedServiceManager.connectionState

        if (lspState is ConnectionState.Connected) {
            val service = lspState.service
            val frameworkName = runCatching { service.frameworkName }.getOrDefault("Xposed")
            val frameworkVersion = runCatching { service.frameworkVersion }.getOrDefault("")
            val apiVersion = runCatching { service.apiVersion }.getOrDefault(0)
            _moduleStatus.value = if (apiVersion >= 101) {
                ModuleStatus.Activated(
                    frameworkName = frameworkName,
                    frameworkVersion = frameworkVersion,
                    apiVersion = apiVersion
                )
            } else {
                ModuleStatus.Unsupported(
                    frameworkName = frameworkName,
                    frameworkVersion = frameworkVersion,
                    apiVersion = apiVersion
                )
            }
        } else {
            _moduleStatus.value = ModuleStatus.NotActivated
        }
    }

    /**
     * 刷新当前激活用户
     * 从 DataStore (文件) 读取
     */
    private fun refreshActiveUser() {
        try {
            val activeUserEntity = DataStore.get("activedUser", UserEntity::class.java)
            _activeUser.value = activeUserEntity
            UserMap.setCurrentUserId(activeUserEntity?.userId?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            Log.e(TAG, "Read active user failed", e)
            _activeUser.value = null
            UserMap.setCurrentUserId(null)
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latestUserIds = SesameAgUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
                val newList = mutableListOf<UserEntity>()
                for (userId in latestUserIds) {
                    UserMap.loadSelf(userId)
                    UserMap.get(userId)?.let { newList.add(it) }
                }
                _userList.value = newList
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading user configs", e)
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

    fun setLegalAccepted(accepted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetUserId = resolveActiveUserId()
                val existingUserIds = resolveExistingUserConfigIds()
                val saveSuccess = if (!targetUserId.isNullOrEmpty()) {
                    Config.saveLegalAcceptedForCurrentVersion(targetUserId, accepted)
                } else if (existingUserIds.isNotEmpty()) {
                    Config.saveLegalAcceptedForUsers(existingUserIds, accepted)
                } else {
                    Log.e(TAG, "Cannot save legal acceptance: active user is unknown")
                    false
                }

                if (!saveSuccess) {
                    Log.e(TAG, "Save legal acceptance failed")
                    refreshLegalAcceptanceState()
                    return@launch
                }

                val readUserId = targetUserId ?: existingUserIds.singleOrNull()
                _isLegalAccepted.value = Config.readLegalAcceptedForCurrentVersion(readUserId)

                if (!targetUserId.isNullOrEmpty()) {
                    sendConfigReloadBroadcast(targetUserId)
                } else {
                    existingUserIds.forEach { sendConfigReloadBroadcast(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update legal acceptance failed", e)
                refreshLegalAcceptanceState()
            }
        }
    }

    private fun refreshLegalAcceptanceState() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeUserId = resolveActiveUserId()
                _isLegalAccepted.value = Config.readLegalAcceptedForCurrentVersion(activeUserId)
            } catch (e: Exception) {
                Log.e(TAG, "Read legal acceptance failed", e)
                _isLegalAccepted.value = false
            }
        }
    }

    fun fetchOneWord() {
        viewModelScope.launch {
            _isOneWordLoading.value = true
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { SesameAgUtil.getOneWord() }
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < 2500) delay(500 - elapsedTime)
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
    }

    fun clearAllLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val logFiles = LogCatalog.loggerNames()
                .map { loggerName -> File(Files.LOG_DIR, LogCatalog.fileName(loggerName)) }
                .distinctBy { it.absolutePath }

            val failedCount = logFiles.count { file ->
                file.exists() && !Files.clearFile(file)
            }

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
        return SesameAgUtil.getFolderList(Files.CONFIG_DIR.absolutePath)
            .map { it.trim() }
            .filter { it.isNotEmpty() && Files.getConfigV2File(it).exists() }
            .distinct()
    }

    private fun sendConfigReloadBroadcast(userId: String) {
        getApplication<Application>().sendBroadcast(
            Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                putExtra("userId", userId)
                putExtra("configReload", true)
            }
        )
    }
}


