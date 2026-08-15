package io.github.aoguai.sesameag.ui.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aoguai.sesameag.ui.extension.parseHtml

@Composable
fun CommonAlertDialog(
    showDialog: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String, // C++ 传来的原始 HTML 字符串
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    confirmText: String = "确认",
    dismissText: String = "取消",
    confirmButtonColor: Color = MaterialTheme.colorScheme.primary,
    showCancelButton: Boolean = true
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            icon = icon?.let { { Icon(it, null, tint = iconTint) } },
            title = { Text(text = title) },
            text = {
                Text(
                    text = text.parseHtml(),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm()
                        onDismissRequest()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = confirmButtonColor)
                ) {
                    Text(confirmText)
                }
            },
            dismissButton = if (showCancelButton) {
                {
                    TextButton(onClick = onDismissRequest) {
                        Text(dismissText)
                    }
                }
            } else null,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}
