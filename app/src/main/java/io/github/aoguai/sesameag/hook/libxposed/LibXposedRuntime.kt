package io.github.aoguai.sesameag.hook.libxposed

import android.util.Log
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.hook.XposedEnv
import io.github.aoguai.sesameag.util.Log as SesameLog
import io.github.aoguai.sesameag.util.ModuleStatus
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Bridges the API 102 module lifecycle to the existing application hook runtime.
 *
 * The framework attaches [module] before [onModuleLoaded] runs. The adapter therefore only
 * exposes that interface to [ApplicationHook] after the supported runtime has been verified.
 */
internal class LibXposedRuntime(
    private val applicationHook: ApplicationHook,
) {
    private var processName: String? = null
    private var active = false
    private var packageReady = false

    fun onModuleLoaded(module: XposedModule, param: ModuleLoadedParam) {
        if (processName != null) {
            module.log(Log.WARN, TAG, "Ignoring duplicate onModuleLoaded callback")
            return
        }

        val identityDecision = RuntimeIdentityGuard.verifyModuleLoaded(module.moduleApplicationInfo)
        if (!identityDecision.accepted) {
            module.log(Log.ERROR, TAG, "instance_rejected: ${identityDecision.reasonCode}")
            module.detach()
            return
        }

        processName = param.processName
        val frameworkName = runCatching { module.frameworkName }.getOrDefault("Unknown")
        val apiVersion = runCatching { module.apiVersion }.getOrDefault(0)
        if (!ModuleStatus.isSupportedLsposedFramework(frameworkName, apiVersion)) {
            module.log(
                Log.ERROR,
                TAG,
                "Unsupported runtime: $frameworkName API $apiVersion; requires LSPosed API ${ModuleStatus.MIN_SUPPORTED_LIBXPOSED_API}+"
            )
            module.detach()
            return
        }

        active = true
        applicationHook.attachLibXposedRuntime(module)
        val frameworkVersion = runCatching { module.frameworkVersion }.getOrDefault("unknown")
        val frameworkVersionCode = runCatching { module.frameworkVersionCode }.getOrDefault(-1L)
        val moduleProcess = runCatching { module.moduleApplicationInfo.processName }.getOrDefault("unknown")
        SesameLog.setWarningErrorMirror { priority, message ->
            module.log(priority, TAG, message)
        }
        SesameLog.runtime(
            TAG,
            "Initialized for process ${param.processName}; framework=$frameworkName $frameworkVersion $frameworkVersionCode api=$apiVersion module_process=$moduleProcess",
        )
    }

    fun onPackageReady(module: XposedModule, param: PackageReadyParam) {
        if (!active || packageReady || param.packageName != General.PACKAGE_NAME) {
            return
        }

        val targetProcessName = processName ?: run {
            module.log(Log.ERROR, TAG, "Package callback arrived before module runtime initialization")
            module.detach()
            return
        }
        val identityDecision = RuntimeIdentityGuard.verifyPackageReady(
            applicationInfo = param.applicationInfo,
            packageName = param.packageName,
            processName = targetProcessName,
        )
        if (!identityDecision.accepted) {
            val reasonCode = identityDecision.reasonCode ?: "unknown"
            val priority = if (reasonCode == "target_unsupported_process") Log.INFO else Log.ERROR
            module.log(
                priority,
                TAG,
                "instance_rejected: $reasonCode process=$targetProcessName " +
                    "process_role=${if (targetProcessName == General.PACKAGE_NAME) "main" else "auxiliary"}",
            )
            module.detach()
            return
        }
        packageReady = true
        module.log(
            Log.INFO,
            TAG,
            "instance_accepted: process=$targetProcessName " +
                "process_role=${if (targetProcessName == General.PACKAGE_NAME) "main" else "capture_only"}",
        )

        try {
            XposedEnv.classLoader = param.classLoader
            XposedEnv.appInfo = param.applicationInfo
            XposedEnv.packageName = param.packageName
            XposedEnv.processName = targetProcessName
            applicationHook.loadPackage(param)
            SesameLog.runtime(TAG, "Hooked ${param.packageName} in process $targetProcessName via onPackageReady")
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "Hook failed - ${t.javaClass.simpleName}", t)
        } finally {
            // One scoped package is enough for this entry; hooks remain active after detaching.
            module.detach()
        }
    }

    private companion object {
        const val TAG = "LibXposedRuntime"
    }
}
