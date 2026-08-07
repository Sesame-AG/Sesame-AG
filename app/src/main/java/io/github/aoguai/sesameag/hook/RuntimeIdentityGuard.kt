package io.github.aoguai.sesameag.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.aoguai.sesameag.data.General

/**
 * Verifies that the module is running against the one supported Android user and target process.
 * This is a compatibility boundary, not an anti-tamper guarantee for rooted or virtualized hosts.
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

    @Volatile
    private var moduleSnapshot: ModuleSnapshot? = null

    @Volatile
    private var targetSnapshot: TargetSnapshot? = null

    @Volatile
    private var attachedIdentity: RuntimeIdentity? = null

    @Volatile
    private var lastDecision = RuntimeIdentityDecision(false, "identity_not_verified")

    @Synchronized
    fun verifyModuleLoaded(applicationInfo: ApplicationInfo): RuntimeIdentityDecision {
        val packageName = applicationInfo.packageName.orEmpty()
        val sourceDir = applicationInfo.sourceDir.orEmpty()
        val userId = androidUserId(applicationInfo.uid)
        val decision = when {
            packageName != General.MODULE_PACKAGE_NAME -> reject("module_package_mismatch")
            userId != PRIMARY_ANDROID_USER_ID -> reject("module_non_primary_user")
            sourceDir.isBlank() -> reject("module_source_missing")
            else -> {
                moduleSnapshot = ModuleSnapshot(applicationInfo.uid, sourceDir)
                accept()
            }
        }
        lastDecision = decision
        return decision
    }

    @Synchronized
    fun verifyPackageReady(
        applicationInfo: ApplicationInfo,
        packageName: String?,
        processName: String?,
    ): RuntimeIdentityDecision {
        val module = moduleSnapshot ?: return rejectAndStore("module_identity_missing")
        val appPackageName = applicationInfo.packageName.orEmpty()
        val appProcessName = applicationInfo.processName.orEmpty()
        val sourceDir = applicationInfo.sourceDir.orEmpty()
        val userId = androidUserId(applicationInfo.uid)
        val decision = when {
            packageName != General.PACKAGE_NAME -> reject("target_package_mismatch")
            appPackageName != General.PACKAGE_NAME -> reject("target_application_package_mismatch")
            processName != General.PACKAGE_NAME -> reject("target_non_main_process")
            appProcessName != General.PACKAGE_NAME -> reject("target_application_process_mismatch")
            userId != PRIMARY_ANDROID_USER_ID -> reject("target_non_primary_user")
            sourceDir.isBlank() -> reject("target_source_missing")
            androidUserId(module.uid) != PRIMARY_ANDROID_USER_ID -> reject("module_non_primary_user")
            else -> {
                targetSnapshot = TargetSnapshot(applicationInfo.uid, sourceDir, processName)
                attachedIdentity = null
                accept()
            }
        }
        lastDecision = decision
        return decision
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun verifyApplicationAttach(context: Context): RuntimeIdentityDecision {
        val module = moduleSnapshot ?: return rejectAndStore("module_identity_missing")
        val target = targetSnapshot ?: return rejectAndStore("target_identity_missing")
        val contextInfo = context.applicationInfo ?: return rejectAndStore("target_context_missing")
        val decision = runCatching {
            val targetInfo = context.packageManager.getApplicationInfo(General.PACKAGE_NAME, 0)
            val moduleInfo = context.packageManager.getApplicationInfo(General.MODULE_PACKAGE_NAME, 0)
            when {
                context.packageName != General.PACKAGE_NAME -> reject("target_context_package_mismatch")
                !matchesTarget(contextInfo, target) -> reject("target_context_mismatch")
                !matchesTarget(targetInfo, target) -> reject("target_package_manager_mismatch")
                moduleInfo.packageName != General.MODULE_PACKAGE_NAME -> reject("module_package_manager_mismatch")
                moduleInfo.uid != module.uid -> reject("module_uid_mismatch")
                androidUserId(moduleInfo.uid) != PRIMARY_ANDROID_USER_ID -> reject("module_non_primary_user")
                moduleInfo.sourceDir.orEmpty() != module.sourceDir -> reject("module_source_mismatch")
                else -> {
                    attachedIdentity = RuntimeIdentity(
                        moduleUid = module.uid,
                        targetUid = target.uid,
                        targetSourceDir = target.sourceDir,
                        targetProcessName = target.processName,
                    )
                    accept()
                }
            }
        }.getOrElse { reject("package_metadata_unavailable") }
        lastDecision = decision
        return decision
    }

    fun isPackageReady(): Boolean =
        lastDecision.accepted && targetSnapshot != null

    fun isTrustedForExecution(): Boolean =
        lastDecision.accepted && attachedIdentity != null

    fun lastReasonCode(): String? = lastDecision.reasonCode

    fun trustedIdentity(): RuntimeIdentity? = attachedIdentity

    private fun matchesTarget(info: ApplicationInfo, target: TargetSnapshot): Boolean =
        info.packageName == General.PACKAGE_NAME &&
            info.uid == target.uid &&
            androidUserId(info.uid) == PRIMARY_ANDROID_USER_ID &&
            info.sourceDir.orEmpty() == target.sourceDir &&
            info.processName.orEmpty() == General.PACKAGE_NAME

    /** UserHandle.getUserId is hidden from this module's compile SDK; Android reserves 100000 UIDs per user. */
    private fun androidUserId(uid: Int): Int =
        if (uid >= 0) uid / ANDROID_PER_USER_RANGE else -1

    private fun accept(): RuntimeIdentityDecision = RuntimeIdentityDecision(true)

    private fun reject(reasonCode: String): RuntimeIdentityDecision {
        attachedIdentity = null
        return RuntimeIdentityDecision(false, reasonCode)
    }

    private fun rejectAndStore(reasonCode: String): RuntimeIdentityDecision =
        reject(reasonCode).also { lastDecision = it }
}
