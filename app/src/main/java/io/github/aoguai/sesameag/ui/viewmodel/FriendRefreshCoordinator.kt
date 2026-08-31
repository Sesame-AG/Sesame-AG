package io.github.aoguai.sesameag.ui.viewmodel

import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FriendRefreshUiState(
    val userId: String = "",
    val refreshAvailable: Boolean = false,
    val checkingRefreshAvailability: Boolean = false,
    val refreshing: Boolean = false,
    val lastRefreshMessage: String = "",
)

data class FriendRefreshCompletion(
    val userId: String,
    val success: Boolean,
    val message: String,
)

class FriendRefreshCoordinator(
    private val coroutineScope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FriendRefreshUiState())
    val state: StateFlow<FriendRefreshUiState> = _state.asStateFlow()

    private var refreshAvailabilityToken: Long = 0L
    private var refreshRequestToken: Long = 0L
    private var showRefreshAvailabilityMessage: Boolean = false

    fun bindUser(userId: String) {
        val normalizedUserId = userId.trim()
        if (_state.value.userId == normalizedUserId) return
        refreshAvailabilityToken = 0L
        refreshRequestToken = 0L
        showRefreshAvailabilityMessage = false
        _state.value = FriendRefreshUiState(userId = normalizedUserId)
    }

    fun requestRefreshAvailability(
        context: Context,
        showUnavailableMessage: Boolean = false,
    ) {
        val currentState = _state.value
        val userId = currentState.userId
        if (userId.isEmpty()) {
            refreshAvailabilityToken = 0L
            showRefreshAvailabilityMessage = false
            _state.value = currentState.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false,
                lastRefreshMessage = "当前账号尚未载入，请先打开目标应用并返回模块",
            )
            return
        }

        val token = System.currentTimeMillis()
        refreshAvailabilityToken = token
        showRefreshAvailabilityMessage = showUnavailableMessage
        _state.value = currentState.copy(
            refreshAvailable = false,
            checkingRefreshAvailability = true,
        )

        try {
            context.applicationContext.sendBroadcast(
                Intent(ApplicationHookConstants.BroadcastActions.HOOK_READY).apply {
                    putExtra("userId", userId)
                },
            )
        } catch (t: Throwable) {
            refreshAvailabilityToken = 0L
            showRefreshAvailabilityMessage = false
            val message = "检测目标应用状态失败：${t.message ?: t.javaClass.simpleName}"
            _state.value = _state.value.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false,
                lastRefreshMessage = if (showUnavailableMessage) message else _state.value.lastRefreshMessage,
            )
            return
        }

        coroutineScope.launch {
            delay(2_000L)
            val latestState = _state.value
            if (
                refreshAvailabilityToken != token ||
                latestState.userId != userId ||
                !latestState.checkingRefreshAvailability
            ) {
                return@launch
            }
            refreshAvailabilityToken = 0L
            val shouldShowMessage = showRefreshAvailabilityMessage
            showRefreshAvailabilityMessage = false
            _state.value = latestState.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false,
                lastRefreshMessage = if (shouldShowMessage) REFRESH_UNAVAILABLE_MESSAGE else latestState.lastRefreshMessage,
            )
        }
    }

    fun handleRefreshAvailabilityResult(
        resultUserId: String,
        ready: Boolean,
        message: String,
    ) {
        val currentState = _state.value
        val normalizedResultUserId = resultUserId.trim()
        if (
            currentState.userId.isNotEmpty() &&
            normalizedResultUserId.isNotEmpty() &&
            currentState.userId != normalizedResultUserId
        ) {
            return
        }

        refreshAvailabilityToken = 0L
        val shouldShowMessage = showRefreshAvailabilityMessage
        showRefreshAvailabilityMessage = false
        val normalizedMessage = message.ifBlank {
            if (ready) "" else REFRESH_UNAVAILABLE_MESSAGE
        }
        _state.value = currentState.copy(
            refreshAvailable = ready,
            checkingRefreshAvailability = false,
            lastRefreshMessage = when {
                ready && currentState.lastRefreshMessage == REFRESH_UNAVAILABLE_MESSAGE -> ""
                !ready && shouldShowMessage -> normalizedMessage
                else -> currentState.lastRefreshMessage
            },
        )
    }

    fun requestRefresh(context: Context) {
        val currentState = _state.value
        val userId = currentState.userId
        if (userId.isEmpty()) {
            _state.value = currentState.copy(lastRefreshMessage = "当前账号尚未载入，请先打开目标应用并返回模块")
            return
        }
        if (!currentState.refreshAvailable) {
            _state.value = currentState.copy(lastRefreshMessage = REFRESH_UNAVAILABLE_MESSAGE)
            requestRefreshAvailability(context, showUnavailableMessage = true)
            return
        }
        if (currentState.refreshing) return

        val token = System.currentTimeMillis()
        refreshRequestToken = token
        _state.value = currentState.copy(
            refreshing = true,
            lastRefreshMessage = "正在刷新好友...",
        )

        try {
            context.applicationContext.sendBroadcast(
                Intent(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS).apply {
                    putExtra("userId", userId)
                    putExtra("manual", true)
                },
            )
        } catch (t: Throwable) {
            refreshRequestToken = 0L
            _state.value = _state.value.copy(
                refreshing = false,
                lastRefreshMessage = "发送刷新指令失败：${t.message ?: t.javaClass.simpleName}",
            )
            return
        }

        coroutineScope.launch {
            delay(10_000L)
            val latestState = _state.value
            if (!latestState.refreshing || refreshRequestToken != token || latestState.userId != userId) {
                return@launch
            }
            refreshRequestToken = 0L
            _state.value = latestState.copy(
                refreshing = false,
                lastRefreshMessage = "已发送刷新指令，未收到完成回执",
            )
        }
    }

    fun handleRefreshResult(
        resultUserId: String,
        success: Boolean,
        message: String,
        profiles: Int,
        groups: Int,
    ): FriendRefreshCompletion? {
        val currentState = _state.value
        val normalizedResultUserId = resultUserId.trim()
        if (
            currentState.userId.isNotEmpty() &&
            normalizedResultUserId.isNotEmpty() &&
            currentState.userId != normalizedResultUserId
        ) {
            return null
        }

        val userId = normalizedResultUserId.ifEmpty { currentState.userId }
        if (userId.isEmpty()) {
            val failureMessage = message.ifBlank { "刷新好友失败：账号为空" }
            _state.value = currentState.copy(
                refreshing = false,
                lastRefreshMessage = failureMessage,
            )
            return FriendRefreshCompletion(userId = "", success = false, message = failureMessage)
        }

        refreshRequestToken = 0L
        val normalizedMessage = message.ifBlank {
            if (success) {
                "好友刷新完成: profiles=$profiles, groups=$groups"
            } else {
                "好友刷新失败"
            }
        }
        _state.value = currentState.copy(
            refreshing = false,
            lastRefreshMessage = normalizedMessage,
        )
        return FriendRefreshCompletion(userId = userId, success = success, message = normalizedMessage)
    }

    private companion object {
        const val REFRESH_UNAVAILABLE_MESSAGE = "请先打开目标应用并回到模块，再刷新好友列表"
    }
}
