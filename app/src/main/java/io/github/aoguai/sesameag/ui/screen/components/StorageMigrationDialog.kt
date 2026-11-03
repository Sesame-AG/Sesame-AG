package io.github.aoguai.sesameag.ui.screen.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.storage.PrivateStorage

@Composable
fun StorageMigrationDialog(state: PrivateStorage.State, onMigrate: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onAuthorize: () -> Unit, onExit: () -> Unit) {
    if (state.ready) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("迁移配置与日志") },
        text = {
            Column {
                Text(if (state.busy || state.needsInspection) state.message else "${state.message}\n\n备份和迁移会关闭目标应用，请先完成其中的操作。操作开始后，请保持本应用在前台，等待完成后再操作。")
                if (state.busy) {
                    Spacer(Modifier.height(16.dp))
                    Text("请保持本应用在前台，等待操作完成。期间不要返回、切换应用、锁屏、打开目标应用或清理后台。")
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator()
                }
                if (!state.busy) {
                    if (state.needsInspection) TextButton(onClick = onAuthorize) { Text("授权 Root / Shizuku") }
                    else TextButton(onClick = onBackup) { Text("先导出完整备份") }
                    TextButton(onClick = onRestore) { Text("从完整备份恢复") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onMigrate, enabled = !state.busy) { Text(if (state.needsInspection) "重新检查" else "迁移并关闭目标应用") } },
        dismissButton = { TextButton(onClick = onExit, enabled = !state.busy) { Text("稍后处理") } },
    )
}
