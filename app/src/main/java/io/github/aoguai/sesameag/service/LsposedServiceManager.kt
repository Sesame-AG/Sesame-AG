package io.github.aoguai.sesameag.service


import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object LsposedServiceManager {

    private const val TAG = "LsposedServiceManager"

    /** 当前连接状态，线程安全 */
    private val _connectionState = AtomicReference<ConnectionState>(ConnectionState.Connecting)

    /** 外部获取当前状态 */
    val connectionState: ConnectionState
        get() = _connectionState.get()

    /** 已连接的服务（如果有） */
    val service: XposedService?
        get() = (_connectionState.get() as? ConnectionState.Connected)?.service

    private val _scopePackages = AtomicReference<Set<String>>(emptySet())

    val scopePackages: Set<String>
        get() = _scopePackages.get()

    /** 模块是否激活 */
    val isModuleActivated: Boolean
        get() = _connectionState.get() is ConnectionState.Connected

    /** 状态监听器列表 */
    private val listeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()

    /** ✨ 修复：使用 AtomicBoolean 保证值比较的正确性 */
    private val isInitialized = AtomicBoolean(false)

    /** 初始化 ServiceManager 并注册 XposedService 监听 */
    fun init() {
        if (!isInitialized.compareAndSet(false, true)) return

        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                val frameworkName = runCatching { boundService.frameworkName }.getOrDefault("Xposed")
                val frameworkVersion = runCatching { boundService.frameworkVersion }.getOrDefault("")
                if (isModuleActivated) {
                    Log.record(TAG, "Another Xposed service tried to connect: $frameworkName. Ignoring.")
                    return
                }
                Log.record(TAG, "LSPosed service connected: $frameworkName v$frameworkVersion")
                updateState(ConnectionState.Connected(boundService))
                refreshScope()
            }

            override fun onServiceDied(deadService: XposedService) {
                // 检查 service 属性而不是直接比较，避免在多线程环境下的竞态条件
                if (service == deadService) {
                    Log.record(TAG, "LSPosed service died.")
                    _scopePackages.set(emptySet())
                    updateState(ConnectionState.Disconnected)
                }
            }
        }

        XposedServiceHelper.registerListener(listener)
        Log.record(TAG, "ServiceManager initialized and listener registered.")
    }

    /** 添加状态监听器，添加后立即触发一次当前状态 */
    fun addConnectionListener(listener: (ConnectionState) -> Unit) {
        listeners.add(listener)
        listener(connectionState)
    }

    /** 移除状态监听器 */
    fun removeConnectionListener(listener: (ConnectionState) -> Unit) {
        listeners.remove(listener)
    }

    fun refreshScope(): Set<String> {
        val activeService = service ?: run {
            _scopePackages.set(emptySet())
            return emptySet()
        }
        val scope = runCatching {
            activeService.scope.toSet()
        }.onFailure {
            Log.printStackTrace(TAG, "Refresh LSPosed scope failed", it)
        }.getOrDefault(emptySet())
        _scopePackages.set(scope)
        return scope
    }

    fun hasTargetScope(packageName: String = General.PACKAGE_NAME): Boolean {
        val scope = scopePackages.ifEmpty { refreshScope() }
        return packageName in scope
    }

    fun requestTargetScope(onFinished: (ScopeRequestResult) -> Unit): Boolean {
        val activeService = service ?: run {
            onFinished(ScopeRequestResult(false, message = "LSPosed service is not connected"))
            return false
        }
        val apiVersion = runCatching { activeService.apiVersion }.getOrDefault(0)
        if (apiVersion < 101) {
            onFinished(ScopeRequestResult(false, message = "Unsupported libxposed API: $apiVersion"))
            return false
        }

        return try {
            activeService.requestScope(
                listOf(General.PACKAGE_NAME),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        refreshScope()
                        onFinished(ScopeRequestResult(true, approved = approved))
                    }

                    override fun onScopeRequestFailed(message: String) {
                        refreshScope()
                        onFinished(ScopeRequestResult(false, message = message))
                    }
                }
            )
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Request LSPosed scope failed", t)
            refreshScope()
            onFinished(ScopeRequestResult(false, message = t.message.orEmpty()))
            false
        }
    }

    /** 更新状态并通知监听器，线程安全 */
    private fun updateState(newState: ConnectionState) {
        _connectionState.set(newState)
        notifyListeners(newState)
    }

    /** 通知所有监听器状态变化 */
    private fun notifyListeners(state: ConnectionState) {
        for (listener in listeners) {
            listener(state)
        }
    }
}

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val service: XposedService) : ConnectionState
    data object Disconnected : ConnectionState
}

data class ScopeRequestResult(
    val success: Boolean,
    val approved: List<String> = emptyList(),
    val message: String = ""
)
