package io.github.aoguai.sesameag.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Overview : AppRoute

    @Serializable
    data object Automation : AppRoute

    @Serializable
    data object Logs : AppRoute

    @Serializable
    data object More : AppRoute

    @Serializable
    data class AccountSettings(val userId: String, val userName: String) : AppRoute

    @Serializable
    data class FriendCenter(val userId: String, val userName: String) : AppRoute

    @Serializable
    data class OnceDailySettings(val userId: String, val userName: String) : AppRoute

    @Serializable
    data object ManualTasks : AppRoute

    @Serializable
    data object RpcDebug : AppRoute

    @Serializable
    data object ExtendTools : AppRoute

    @Serializable
    data class LogDetails(val source: LogSource) : AppRoute
}

@Serializable
sealed interface LogSource {
    @Serializable
    data class Channel(val name: String) : LogSource

    @Serializable
    data class FilePath(val path: String) : LogSource
}

enum class TopLevelDestination(val label: String) {
    OVERVIEW("概览"),
    AUTOMATION("自动化"),
    LOGS("日志"),
    MORE("更多"),
}
