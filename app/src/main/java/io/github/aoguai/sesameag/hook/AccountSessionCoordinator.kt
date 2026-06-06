package io.github.aoguai.sesameag.hook

import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.keepalive.PersistentSchedule
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.concurrent.atomic.AtomicLong

data class ActiveAccountSession(
    val userId: String,
    val activeUserSnapshot: UserEntity?,
    val legalAccepted: Boolean,
    val workflowAllowed: Boolean,
    val sessionEpoch: Long,
    val switchedAtMs: Long
)

data class AccountSessionIdentity(
    val userId: String,
    val sessionEpoch: Long
)

private data class PersistedAccountSession(
    val userId: String = "",
    val sessionEpoch: Long = 0L,
    val switchedAtMs: Long = 0L
)

object AccountSessionCoordinator {
    private const val TAG = "AccountSessionCoordinator"
    private const val SESSION_STORE_KEY = "activeAccountSession"

    private val epochGenerator = AtomicLong(0L)

    @Volatile
    private var currentSession: ActiveAccountSession? = null

    @Volatile
    private var switchInProgress = false

    @Volatile
    private var pendingEpoch: Long = 0L

    fun currentSession(): ActiveAccountSession? = currentSession

    fun currentUserId(): String? = currentSession?.userId

    fun currentOrPersistedSessionIdentity(): AccountSessionIdentity? {
        currentSession?.let { session ->
            return AccountSessionIdentity(
                userId = session.userId,
                sessionEpoch = session.sessionEpoch
            )
        }
        return readPersistedSession()
            ?.let { session ->
                AccountSessionIdentity(
                    userId = session.userId.trim(),
                    sessionEpoch = session.sessionEpoch
                )
            }
    }

    fun currentSessionEpoch(): Long {
        val current = currentSession
        return when {
            current != null -> current.sessionEpoch
            pendingEpoch > 0L -> pendingEpoch
            // Session has been invalidated and no switch is in progress.
            // Returning 0 prevents stale tail callbacks from being rebound to
            // the last live epoch during shutdown/restart windows.
            else -> 0L
        }
    }

    fun isSwitching(): Boolean = switchInProgress

    fun hasActiveSessionFor(userId: String?): Boolean {
        val safeUserId = userId?.trim().orEmpty()
        val current = currentSession ?: return false
        return safeUserId.isNotEmpty() && current.userId == safeUserId
    }

    fun beginSessionSwitch(
        reason: String,
        targetUserId: String?,
        allowPersistedReuse: Boolean = false
    ): Long {
        val safeTargetUserId = targetUserId?.trim().orEmpty()
        val reusedEpoch = if (allowPersistedReuse && safeTargetUserId.isNotEmpty() && currentSession == null) {
            readPersistedSession(safeTargetUserId)?.sessionEpoch ?: 0L
        } else {
            0L
        }
        if (reusedEpoch > 0L) {
            synchronizeEpochGenerator(reusedEpoch)
        }
        val newEpoch = if (reusedEpoch > 0L) reusedEpoch else epochGenerator.incrementAndGet()
        pendingEpoch = newEpoch
        switchInProgress = true
        val now = System.currentTimeMillis()
        currentSession = currentSession?.copy(
            workflowAllowed = false,
            sessionEpoch = newEpoch,
            switchedAtMs = now
        )
        Log.record(
            TAG,
            "begin session switch: reason=$reason target=${targetUserId ?: "unknown"} epoch=$newEpoch current=${currentSession?.userId}"
        )
        return newEpoch
    }

    fun clearRuntimeSession(reason: String) {
        currentSession = null
        switchInProgress = false
        pendingEpoch = 0L
        Log.record(TAG, "clear runtime session: reason=$reason")
    }

    fun cancelSessionSwitch(reason: String) {
        clearRuntimeSession("cancel_session_switch:$reason")
    }

    fun ensureActiveUserSnapshot(userId: String, classLoader: ClassLoader?): UserEntity {
        val safeUserId = userId.trim()
        require(safeUserId.isNotEmpty()) { "userId must not be blank" }
        val currentUserSnapshot = HookUtil.captureCurrentUserEntity(classLoader)
            ?.takeIf { it.userId == safeUserId }
        return UserMap.ensureActiveUserSnapshot(
            safeUserId,
            currentUserSnapshot ?: UserMap.readSelf(safeUserId)
        )
    }

    fun applySession(
        context: Context?,
        userId: String,
        activeUserSnapshot: UserEntity?,
        legalAccepted: Boolean,
        workflowAllowed: Boolean,
        reason: String
    ): ActiveAccountSession {
        val safeUserId = userId.trim()
        require(safeUserId.isNotEmpty()) { "userId must not be blank" }
        val now = System.currentTimeMillis()
        val epoch = pendingEpoch.takeIf { it > 0L } ?: epochGenerator.incrementAndGet()
        val snapshot = UserMap.ensureActiveUserSnapshot(safeUserId, activeUserSnapshot)
        val session = ActiveAccountSession(
            userId = safeUserId,
            activeUserSnapshot = snapshot,
            legalAccepted = legalAccepted,
            workflowAllowed = workflowAllowed,
            sessionEpoch = epoch,
            switchedAtMs = now
        )
        synchronizeEpochGenerator(epoch)
        currentSession = session
        switchInProgress = false
        pendingEpoch = 0L
        persistSession(session)
        if (context != null) {
            PersistentScheduleRegistry.activateSession(context, safeUserId, epoch)
            publishAccountContextChanged(context, session)
        }
        Log.record(
            TAG,
            "apply session: user=$safeUserId legalAccepted=$legalAccepted workflowAllowed=$workflowAllowed epoch=$epoch reason=$reason"
        )
        return session
    }

    fun refreshWorkflowState(
        context: Context?,
        reason: String,
        legalAccepted: Boolean? = null
    ): ActiveAccountSession? {
        val current = currentSession ?: return null
        val accepted = legalAccepted ?: current.legalAccepted
        val sessionIsCurrent = UserMap.currentUid?.trim().orEmpty() == current.userId
        val workflowAllowed =
            WorkflowRootGuard.hasGrantedRoot() &&
                accepted &&
                !ApplicationHookConstants.isOffline() &&
                sessionIsCurrent
        if (current.workflowAllowed == workflowAllowed && current.legalAccepted == accepted) {
            return current
        }
        val updated = current.copy(
            legalAccepted = accepted,
            workflowAllowed = workflowAllowed
        )
        currentSession = updated
        if (context != null) {
            publishAccountContextChanged(context, updated)
        }
        Log.record(
            TAG,
            "refresh workflow gate: user=${updated.userId} legalAccepted=$accepted allowed=$workflowAllowed reason=$reason"
        )
        return updated
    }

    fun bindTrigger(trigger: ApplicationHookConstants.TriggerInfo): ApplicationHookConstants.TriggerInfo {
        val ownerUserId = trigger.ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
            ?: currentSession?.userId
            ?: UserMap.currentUid?.trim()?.takeIf { it.isNotEmpty() }
        val sessionEpoch = if (trigger.sessionEpoch > 0L) {
            trigger.sessionEpoch
        } else {
            currentSession?.sessionEpoch ?: pendingEpoch.takeIf { it > 0L } ?: 0L
        }
        return trigger.copy(ownerUserId = ownerUserId, sessionEpoch = sessionEpoch)
    }

    fun shouldAcceptTrigger(trigger: ApplicationHookConstants.TriggerInfo): Boolean {
        if (switchInProgress) {
            return false
        }
        val current = currentSession ?: return false
        val ownerUserId = trigger.ownerUserId?.trim().orEmpty()
        if (ownerUserId.isEmpty() || ownerUserId != current.userId) {
            return false
        }
        return trigger.sessionEpoch == current.sessionEpoch
    }

    fun isCurrentSession(userId: String?, sessionEpoch: Long): Boolean {
        val current = currentSession ?: return false
        val safeUserId = userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) return false
        return current.userId == safeUserId && current.sessionEpoch == sessionEpoch
    }

    fun isScheduleRoutable(schedule: PersistentSchedule): Boolean {
        val current = currentSession ?: return false
        val ownerUserId = schedule.ownerUserId?.trim().orEmpty()
        if (ownerUserId.isEmpty() || ownerUserId != current.userId) {
            return false
        }
        return schedule.sessionEpoch == current.sessionEpoch
    }

    private fun persistSession(session: ActiveAccountSession) {
        runCatching {
            DataStore.put(
                SESSION_STORE_KEY,
                PersistedAccountSession(
                    userId = session.userId,
                    sessionEpoch = session.sessionEpoch,
                    switchedAtMs = session.switchedAtMs
                )
            )
        }.onFailure {
            Log.printStackTrace(TAG, "persist session failed", it)
        }
    }

    private fun readPersistedSession(userId: String): PersistedAccountSession? {
        return runCatching {
            DataStore.get(SESSION_STORE_KEY, PersistedAccountSession::class.java)
        }.onFailure {
            Log.printStackTrace(TAG, "read persisted session failed", it)
        }.getOrNull()
            ?.takeIf { session ->
                session.userId.trim() == userId && session.sessionEpoch > 0L
            }
    }

    private fun readPersistedSession(): PersistedAccountSession? {
        return runCatching {
            DataStore.get(SESSION_STORE_KEY, PersistedAccountSession::class.java)
        }.onFailure {
            Log.printStackTrace(TAG, "read persisted session failed", it)
        }.getOrNull()
            ?.takeIf { session ->
                session.userId.trim().isNotEmpty() && session.sessionEpoch > 0L
            }
    }

    private fun synchronizeEpochGenerator(epoch: Long) {
        if (epoch <= 0L) return
        while (true) {
            val current = epochGenerator.get()
            if (current >= epoch) {
                return
            }
            if (epochGenerator.compareAndSet(current, epoch)) {
                return
            }
        }
    }

    private fun publishAccountContextChanged(context: Context, session: ActiveAccountSession) {
        val snapshot = session.activeUserSnapshot
        val intent = Intent(ApplicationHookConstants.BroadcastActions.ACCOUNT_CONTEXT_CHANGED).apply {
            setPackage(General.MODULE_PACKAGE_NAME)
            putExtra("userId", session.userId)
            putExtra("legalAccepted", session.legalAccepted)
            putExtra("workflowAllowed", session.workflowAllowed)
            putExtra("sessionEpoch", session.sessionEpoch)
            putExtra("switchedAtMs", session.switchedAtMs)
            putExtra("activeUserId", snapshot?.userId)
            putExtra("activeUserShowName", snapshot?.showName)
            putExtra("activeUserNickName", snapshot?.nickName)
            putExtra("activeUserRemarkName", snapshot?.remarkName)
            putExtra("activeUserRealName", snapshot?.realName)
            putExtra("activeUserAccount", snapshot?.account)
            snapshot?.friendStatus?.let { putExtra("activeUserFriendStatus", it) }
        }
        context.sendBroadcast(intent)
    }
}
