package io.github.aoguai.sesameag

import android.app.Application
import android.content.Context
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ToastUtil

/**
 * 芝麻粒应用主类
 *
 * 负责应用初始化
 */
class SesameApplication : Application() {

    companion object {
        const val PREFERENCES_KEY = "sesame-ag"
        var hasPermissions: Boolean = false
        @JvmStatic
        @Volatile
        var appContext: Context? = null
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        ToastUtil.init(this)
        io.github.aoguai.sesameag.storage.PrivateStorage.initialize(this)
        Log.init(this)
        io.github.aoguai.sesameag.util.ModuleDiagnostics.event("application", "created")
        ThemeManager.init(this)
    }
}
