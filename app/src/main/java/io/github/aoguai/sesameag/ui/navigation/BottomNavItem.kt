package io.github.aoguai.sesameag.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(
    val label: String,
    val icon: ImageVector
) {
    data object Logs : BottomNavItem("日志", Icons.AutoMirrored.Rounded.Article)
    data object Home : BottomNavItem("主页", Icons.Rounded.Home)
    data object Settings : BottomNavItem("设置", Icons.Rounded.Settings)
}
