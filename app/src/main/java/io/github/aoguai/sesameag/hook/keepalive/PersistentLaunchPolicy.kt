package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context
import android.os.Process
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

internal object PersistentLaunchPolicy {
    const val FRONT_LAUNCH_DISABLED_ERROR = "front_launch_disabled"

    private const val BASE_MODEL_CODE = "BaseModel"
    private const val FIELD_CODE = "allowPersistentForegroundLaunch"
    private const val LAUNCH_TARGET_KEY = "launch_target"

    data class PreparationResult(
        val schedule: PersistentSchedule,
        val blockedReason: String? = null
    )

    fun prepareScheduleForRegistration(context: Context, schedule: PersistentSchedule): PreparationResult {
        if (canUseModuleReceiverContext(context)) {
            return PreparationResult(schedule.copy(payloadJson = sanitizeLaunchTarget(schedule.payloadJson, false)))
        }
        if (isForegroundLaunchEnabled(schedule.ownerUserId)) {
            return PreparationResult(schedule.copy(payloadJson = sanitizeLaunchTarget(schedule.payloadJson, true)))
        }
        return PreparationResult(schedule = schedule, blockedReason = FRONT_LAUNCH_DISABLED_ERROR)
    }

    fun shouldLaunchTarget(schedule: PersistentSchedule): Boolean {
        return payloadRequestsTargetLaunch(schedule.payloadJson) && isForegroundLaunchEnabled(schedule.ownerUserId)
    }

    fun isFrontLaunchDisabled(error: String?): Boolean = error == FRONT_LAUNCH_DISABLED_ERROR

    fun payloadRequestsTargetLaunch(payloadJson: String): Boolean {
        return payloadToJson(payloadJson).optBoolean(LAUNCH_TARGET_KEY, false)
    }

    fun sanitizeLaunchTarget(payloadJson: String, enabled: Boolean): String {
        val payload = payloadToJson(payloadJson)
        if (enabled) {
            payload.put(LAUNCH_TARGET_KEY, true)
        } else {
            payload.remove(LAUNCH_TARGET_KEY)
        }
        return payload.toString()
    }

    private fun isForegroundLaunchEnabled(ownerUserId: String?): Boolean {
        val safeOwnerUserId = ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
        val currentUserId = (AccountSessionCoordinator.currentUserId() ?: UserMap.currentUid)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (Config.isLoaded() && (safeOwnerUserId == null || currentUserId == null || safeOwnerUserId == currentUserId)) {
            return BaseModel.allowPersistentForegroundLaunch.value != false
        }
        return readPersistedForegroundLaunchEnabled(safeOwnerUserId) ?: (BaseModel.allowPersistentForegroundLaunch.value != false)
    }

    private fun readPersistedForegroundLaunchEnabled(userId: String?): Boolean? {
        val configFile = if (userId.isNullOrBlank()) {
            Files.getDefaultConfigV2File()
        } else {
            Files.getConfigV2File(userId)
        }
        if (!configFile.exists()) return null
        val content = Files.readFromFile(configFile)?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return runCatching {
            JSONObject(content)
                .optJSONObject("modelFieldsMap")
                ?.optJSONObject(BASE_MODEL_CODE)
                ?.optJSONObject(FIELD_CODE)
                ?.takeIf { it.has("value") }
                ?.optBoolean("value")
        }.getOrNull()
    }

    private fun payloadToJson(payloadJson: String): JSONObject {
        val normalized = payloadJson.trim().ifBlank { "{}" }
        return runCatching { JSONObject(normalized) }.getOrElse { JSONObject() }
    }

    private fun canUseModuleReceiverContext(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == General.MODULE_PACKAGE_NAME) {
            return true
        }
        return runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageUid(General.MODULE_PACKAGE_NAME, 0) == Process.myUid() &&
                appContext.createPackageContext(General.MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY) != null
        }.getOrDefault(false)
    }
}
