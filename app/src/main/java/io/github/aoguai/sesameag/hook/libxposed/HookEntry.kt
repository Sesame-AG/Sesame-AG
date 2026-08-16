package io.github.aoguai.sesameag.hook.libxposed

import android.util.Log
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    private val runtime = LibXposedRuntime(ApplicationHook())

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            runtime.onModuleLoaded(this, param)
        } catch (t: Throwable) {
            log(Log.ERROR, "HookEntry", "onModuleLoaded failed: ${t.javaClass.simpleName}", t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        try {
            runtime.onPackageReady(this, param)
        } catch (t: Throwable) {
            log(Log.ERROR, "HookEntry", "onPackageReady failed: ${t.javaClass.simpleName}", t)
        }
    }
}

