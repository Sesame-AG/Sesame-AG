package io.github.aoguai.sesameag.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.customTasks.CustomTask
import io.github.aoguai.sesameag.ui.screen.ManualTaskScreen
import io.github.aoguai.sesameag.ui.theme.AppTheme
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.maps.UserMap

/**
 * 手动任务 Fragment (Compose 实现)
 * 采用列表展示所有可用的子任务，点击即可运行
 */
class ManualTaskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化配置
        ensureConfigLoaded()

        setContent {
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()
            AppTheme(dynamicColor = isDynamicColor) {
                ManualTaskScreen(
                    onBackClick = { finish() },
                    onTaskClick = { task, params -> runTask(task, params) }
                )
            }
        }
    }

    private fun ensureConfigLoaded() {
        Model.initAllModel()
        val activeUser = DataStore.get("activedUser", UserEntity::class.java)
        activeUser?.userId?.let { uid ->
            UserMap.setCurrentUserId(uid)
            Config.load(uid)
        }
    }

    private fun runTask(task: CustomTask, params: Map<String, Any>) {
        val activeUserId = DataStore.get("activedUser", UserEntity::class.java)?.userId
        if (!AccountSlotRegistry.isExecutableUser(activeUserId)) {
            ToastUtil.showToast(this, "当前账号不在可执行槽位，无法运行手动任务")
            return
        }
        try {
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
            ToastUtil.showToast(this, "🚀 已发送指令: ${task.displayName}")
            openRecordLog()
        } catch (e: Exception) {
            ToastUtil.showToast(this, "❌ 发送失败: ${e.message}")
        }
    }

    private fun openRecordLog() {
        val logFile = Files.getLogFile(LogChannel.RECORD)
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件尚未生成")
            return
        }
        val intent = Intent(this, LogViewerActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }
}





