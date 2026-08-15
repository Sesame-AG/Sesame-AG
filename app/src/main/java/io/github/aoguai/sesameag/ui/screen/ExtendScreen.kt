package io.github.aoguai.sesameag.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aoguai.sesameag.ui.viewmodel.ExtendDialog
import io.github.aoguai.sesameag.ui.viewmodel.ExtendViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendScreen(
    onBackClick: () -> Unit,
    onOpenLog: (String) -> Unit,
    viewModel: ExtendViewModel = viewModel()
) {
    val context = LocalContext.current

    // 初始化数据
    LaunchedEffect(Unit) {
        viewModel.loadData(context, onOpenLog)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扩展功能") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.menuItems) { item ->
                ExtendItemCard(title = item.title, onClick = item.onClick)
            }
        }

        // 处理弹窗逻辑
        DialogHandler(viewModel, context)
    }
}

@Composable
fun ExtendItemCard(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick // Material3 Card 自带 onClick
    ) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun DialogHandler(viewModel: ExtendViewModel, context: android.content.Context) {
    when (val dialog = viewModel.currentDialog) {
        is ExtendDialog.None -> {}

        is ExtendDialog.ClearPhotoConfirm -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("清空图片") },
                text = { Text("确认清空 ${dialog.count} 组光盘行动图片？") },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearPhotos(context) }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("取消")
                    }
                }
            )
        }

        is ExtendDialog.WritePhotoTest -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Test") },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.writePhotoTest(context) }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("取消")
                    }
                }
            )
        }

        is ExtendDialog.InputDialog -> {
            var text by remember { mutableStateOf(dialog.initialValue) }
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text(dialog.title) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dialog.onConfirm(text) }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
