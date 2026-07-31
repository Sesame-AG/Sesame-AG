package io.github.aoguai.sesameag.hook.libxposed

import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    private val runtime = LibXposedRuntime(ApplicationHook())

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runtime.onModuleLoaded(this, param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        runtime.onPackageReady(this, param)
    }
}

