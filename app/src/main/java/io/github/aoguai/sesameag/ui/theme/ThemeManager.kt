package io.github.aoguai.sesameag.ui.theme

import android.content.Context
import android.content.SharedPreferences
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {
    // 默认开启
    private val _isDynamicColor = MutableStateFlow(true)
    val isDynamicColor = _isDynamicColor.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
        // 初始化时读取配置
        _isDynamicColor.value = prefs.getBoolean("dynamic_color", true)
    }

    fun setDynamicColor(enabled: Boolean) {
        _isDynamicColor.value = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun resetToDefaults() {
        _isDynamicColor.value = true
    }
}
