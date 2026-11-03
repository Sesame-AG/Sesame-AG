package io.github.aoguai.sesameag.hook

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.SerialTaskQueue
import java.io.File

/**
 * Verifies that the module is running against the one supported Android user and target process.
 * Main-process execution is trusted separately from capture-only Alipay lite processes.
 */
data class RuntimeIdentity(
    val moduleUid: Int,
    val targetUid: Int,
    val targetSourceDir: String,
    val targetProcessName: String,
)

data class RuntimeIdentityDecision(
    val accepted: Boolean,
    val reasonCode: String? = null,
)

object RuntimeIdentityGuard {
    private data class ModuleSnapshot(
        val uid: Int,
        val sourceDir: String,
    )

    private data class TargetSnapshot(
        val uid: Int,
        val sourceDir: String,
        val processName: String,
    )

    private const val PRIMARY_ANDROID_USER_ID = 0
    private const val ANDROID_PER_USER_RANGE = 100_000
    private data class IdentityState(
        val moduleSnapshot: ModuleSnapshot? = null,
        val targetSnapshot: TargetSnapshot? = null,
        val attachedIdentity: RuntimeIdentity? = null,
        val lastDecision: RuntimeIdentityDecision = RuntimeIdentityDecision(false, "identity_not_verified"),
    )
    private val updates = SerialTaskQueue()
    private val attachments = SerialTaskQueue()
    @Volatile private var state = IdentityState()
    private val moduleSnapshot get() = state.moduleSnapshot
    private val targetSnapshot get() = state.targetSnapshot
    private val attachedIdentity get() = state.attachedIdentity
    private val lastDecision get() = state.lastDecision

    fun verifyModuleLoaded(applicationInfo: ApplicationInfo): RuntimeIdentityDecision = updates.execute {
        val packageName = applicationInfo.packageName.orEmpty()
        val sourceDir = applicationInfo.sourceDir.orEmpty()
        val userId = androidUserId(applicationInfo.uid)
        val decision = when {
            packageName != General.MODULE_PACKAGE_NAME -> reject("module_package_mismatch")
            userId != PRIMARY_ANDROID_USER_ID -> reject("module_non_primary_user")
            sourceDir.isBlank() -> reject("module_source_missing")
            else -> {
                state = state.copy(moduleSnapshot = ModuleSnapshot(applicationInfo.uid, sourceDir))
                accept()
            }
        }
        if (!decision.accepted) {
            android.util.Log.e("ApplicationHook", "module=${General.MODULE_PACKAGE_NAME} instance_rejected stage=module reason=${decision.reasonCode} " +
                "package=$packageName uid=${applicationInfo.uid} user=$userId source=$sourceDir")
        }
        state = state.copy(lastDecision = decision, attachedIdentity = if (decision.accepted) state.attachedIdentity else null)
        decision
    }

    fun verifyPackageReady(
        applicationInfo: ApplicationInfo,
        packageName: String?,
        processName: String?,
    ): RuntimeIdentityDecision = updates.execute {
        val module = moduleSnapshot ?: return@execute rejectAndStore("module_identity_missing")
        val appPackageName = applicationInfo.packageName.orEmpty()
        val appProcessName = applicationInfo.processName.orEmpty()
        val targetProcessName = processName ?: return@execute rejectAndStore("target_process_missing")
        val sourceDir = applicationInfo.sourceDir.orEmpty()
        val userId = androidUserId(applicationInfo.uid)
        val decision = when {
            packageName != General.PACKAGE_NAME -> reject("target_package_mismatch")
            appPackageName != General.PACKAGE_NAME -> reject("target_application_package_mismatch")
            !isSupportedTargetProcess(targetProcessName) -> reject("target_unsupported_process")
            targetProcessName == General.PACKAGE_NAME && appProcessName != General.PACKAGE_NAME ->
                reject("target_application_process_mismatch")
            userId != PRIMARY_ANDROID_USER_ID -> reject("target_non_primary_user")
            sourceDir.isBlank() -> reject("target_source_missing")
            androidUserId(module.uid) != PRIMARY_ANDROID_USER_ID -> reject("module_non_primary_user")
            else -> {
                state = state.copy(
                    targetSnapshot = TargetSnapshot(applicationInfo.uid, sourceDir, targetProcessName),
                    attachedIdentity = null,
                )
                accept()
            }
        }
        if (!decision.accepted) {
            android.util.Log.println(
                if (decision.reasonCode == "target_unsupported_process") android.util.Log.INFO else android.util.Log.ERROR,
                "ApplicationHook", "module=${General.MODULE_PACKAGE_NAME} instance_rejected stage=package reason=${decision.reasonCode} " +
                "package=$packageName applicationPackage=$appPackageName uid=${applicationInfo.uid} user=$userId " +
                "process=$targetProcessName applicationProcess=$appProcessName source=$sourceDir")
        }
        state = state.copy(lastDecision = decision, attachedIdentity = if (decision.accepted) state.attachedIdentity else null)
        decision
    }

    @Suppress("DEPRECATION")
    suspend fun verifyApplicationAttach(context: Context): RuntimeIdentityDecision = attachments.submit {
        val pending = updates.execute {
            val decision = when {
                state.moduleSnapshot == null -> reject("module_identity_missing")
                state.targetSnapshot == null -> reject("target_identity_missing")
                else -> RuntimeIdentityDecision(false, "module_identity_pending")
            }
            state.copy(attachedIdentity = null, lastDecision = decision).also { state = it }
        }
        val module = pending.moduleSnapshot ?: return@submit pending.lastDecision
        val target = pending.targetSnapshot ?: return@submit pending.lastDecision
        val contextInfo = context.applicationInfo
        var metadataStep = "query_target"
        val decision = if (contextInfo == null) reject("target_context_missing") else runCatching {
            val targetInfo = context.packageManager.getApplicationInfo(General.PACKAGE_NAME, 0)
            metadataStep = "compare_identity"
            when {
                context.packageName != General.PACKAGE_NAME -> reject("target_context_package_mismatch")
                !matchesTargetIdentity(contextInfo, target) -> reject("target_context_mismatch")
                Application.getProcessName() != target.processName -> reject("target_runtime_process_mismatch")
                !matchesTargetIdentity(targetInfo, target) -> reject("target_package_manager_mismatch")
                !matchesTargetSources(contextInfo, targetInfo, target) -> reject("target_source_mismatch")
                else -> accept()
            }.also { result ->
                if (!result.accepted) {
                    android.util.Log.e("ApplicationHook", "module=${General.MODULE_PACKAGE_NAME} instance_rejected stage=attach reason=${result.reasonCode} " +
                        "moduleUid=${module.uid} moduleSource=${module.sourceDir} " +
                        "targetPackage=${targetInfo.packageName} targetUid=${targetInfo.uid} targetUser=${androidUserId(targetInfo.uid)} targetSource=${targetInfo.sourceDir} " +
                        "contextPackage=${context.packageName} contextUid=${contextInfo.uid} contextSource=${contextInfo.sourceDir}")
                }
            }
        }.getOrElse {
            android.util.Log.e("ApplicationHook", "module=${General.MODULE_PACKAGE_NAME} package_metadata_unavailable step=$metadataStep " +
                "context=${context.packageName} uid=${contextInfo.uid} source=${contextInfo.sourceDir} " +
                "error=${it.javaClass.simpleName}", it)
            reject("package_metadata_unavailable")
        }

        val confirmed = decision.accepted &&
            CommandUtil.verifyRuntimeIdentity(context, module.uid, module.sourceDir)
        updates.execute {
            if (state !== pending) return@execute reject("identity_changed")
            val result = when {
                !decision.accepted -> decision
                !confirmed -> reject("module_identity_unverified")
                else -> accept()
            }
            state = pending.copy(
                attachedIdentity = if (result.accepted) RuntimeIdentity(
                    moduleUid = module.uid,
                    targetUid = target.uid,
                    targetSourceDir = target.sourceDir,
                    targetProcessName = target.processName,
                ) else null,
                lastDecision = result,
            )
            result
        }
    }

    fun isPackageReady(): Boolean = state.let {
        it.lastDecision.accepted && it.targetSnapshot != null
    }

    fun isTrustedForExecution(): Boolean = state.let {
        it.lastDecision.accepted && it.attachedIdentity?.targetProcessName == General.PACKAGE_NAME
    }

    fun isMainProcess(): Boolean =
        targetSnapshot?.processName == General.PACKAGE_NAME

    fun isCaptureOnlyProcess(): Boolean =
        targetSnapshot?.processName?.let(::isCaptureOnlyProcessName) == true

    fun lastReasonCode(): String? = lastDecision.reasonCode

    fun trustedIdentity(): RuntimeIdentity? = attachedIdentity

    private fun isSupportedTargetProcess(processName: String?): Boolean =
        processName == General.PACKAGE_NAME || isCaptureOnlyProcessName(processName)

    private fun isCaptureOnlyProcessName(processName: String?): Boolean {
        val prefix = "${General.PACKAGE_NAME}:lite"
        if (processName.isNullOrBlank() || !processName.startsWith(prefix)) {
            return false
        }
        val suffix = processName.removePrefix(prefix)
        return suffix.isNotEmpty() && suffix.all { it in '0'..'9' }
    }

    private fun matchesTargetIdentity(info: ApplicationInfo, target: TargetSnapshot): Boolean =
        info.packageName == General.PACKAGE_NAME &&
            info.uid == target.uid &&
            androidUserId(info.uid) == PRIMARY_ANDROID_USER_ID

    private fun matchesTargetSources(
        contextInfo: ApplicationInfo,
        targetInfo: ApplicationInfo,
        target: TargetSnapshot,
    ): Boolean {
        val contextSource = normalizedSourcePath(contextInfo.sourceDir) ?: return false
        val targetSource = normalizedSourcePath(target.sourceDir) ?: return false
        val packageManagerSource = normalizedSourcePath(targetInfo.sourceDir) ?: return false
        return contextSource == targetSource || contextSource == packageManagerSource
    }

    private fun normalizedSourcePath(sourceDir: String?): String? =
        sourceDir
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrNull() }

    /** UserHandle.getUserId is hidden from this module's compile SDK; Android reserves 100000 UIDs per user. */
    private fun androidUserId(uid: Int): Int =
        if (uid >= 0) uid / ANDROID_PER_USER_RANGE else -1

    private fun accept(): RuntimeIdentityDecision = RuntimeIdentityDecision(true)

    private fun reject(reasonCode: String): RuntimeIdentityDecision {
        android.util.Log.println(
            if (reasonCode == "target_unsupported_process") android.util.Log.INFO else android.util.Log.WARN,
            "ApplicationHook", "module=${General.MODULE_PACKAGE_NAME} instance_rejected reason=$reasonCode process=${Application.getProcessName()} " +
            "runtimeUid=${android.os.Process.myUid()} moduleUid=${moduleSnapshot?.uid} targetUid=${targetSnapshot?.uid} " +
            "moduleSource=${moduleSnapshot?.sourceDir} targetSource=${targetSnapshot?.sourceDir}")
        return RuntimeIdentityDecision(false, reasonCode)
    }

    private fun rejectAndStore(reasonCode: String): RuntimeIdentityDecision =
        reject(reasonCode).also { state = state.copy(attachedIdentity = null, lastDecision = it) }
}
