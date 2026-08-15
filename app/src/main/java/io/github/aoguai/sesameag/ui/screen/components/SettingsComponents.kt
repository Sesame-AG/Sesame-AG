package io.github.aoguai.sesameag.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SettingsSaveIconButton(
    isDirty: Boolean,
    isSaving: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String = "保存",
    onSave: () -> Unit,
) {
    val buttonEnabled = isDirty && enabled && !isSaving
    val accessibilityLabel = if (isSaving) "${contentDescription}中" else contentDescription
    val modifier = Modifier.semantics { this.contentDescription = accessibilityLabel }
    if (isDirty || isSaving) {
        FilledIconButton(modifier = modifier, onClick = onSave, enabled = buttonEnabled) {
            SettingsSaveIcon(isSaving)
        }
    } else {
        IconButton(modifier = modifier, onClick = onSave, enabled = buttonEnabled) {
            SettingsSaveIcon(isSaving)
        }
    }
}

@Composable
fun SettingsExitDraftDialog(
    isSaving: Boolean = false,
    saveEnabled: Boolean = true,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onContinue() },
        title = { Text("保存修改") },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = saveEnabled && !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    SettingsSaveIcon(isSaving)
                    Spacer(Modifier.width(8.dp))
                    Text("保存并退出")
                }
                TextButton(
                    onClick = onContinue,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("继续编辑")
                }
                TextButton(
                    onClick = onDiscard,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("放弃")
                }
            }
        },
    )
}

@Composable
private fun SettingsSaveIcon(
    isSaving: Boolean,
) {
    if (isSaving) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = LocalContentColor.current,
            strokeWidth = 2.dp,
        )
    } else {
        Icon(Icons.Outlined.Save, contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DelayedLoadingIndicator(
    modifier: Modifier = Modifier.size(48.dp),
    delayMillis: Long = 300L,
) {
    var visible by remember(delayMillis) { mutableStateOf(false) }
    LaunchedEffect(delayMillis) {
        delay(delayMillis)
        visible = true
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (visible) {
            LoadingIndicator(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}
