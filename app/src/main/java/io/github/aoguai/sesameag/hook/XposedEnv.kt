package io.github.aoguai.sesameag.hook

import android.content.pm.ApplicationInfo


object XposedEnv {
    lateinit var classLoader: ClassLoader
    lateinit var appInfo: ApplicationInfo
    lateinit var packageName: String
    lateinit var processName: String

    @Volatile
    var runtimeIdentity: RuntimeIdentity? = null
}

