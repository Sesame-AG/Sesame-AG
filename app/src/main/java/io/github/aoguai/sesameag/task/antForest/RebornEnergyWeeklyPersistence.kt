package io.github.aoguai.sesameag.task.antForest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.UserDataStore
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.Calendar

@JsonIgnoreProperties(ignoreUnknown = true)
data class RebornWeeklyState(
    val weekStart: Long = 0L,
    val configSignature: String = "",
    val completed: Boolean = false,
    val completedAt: Long = 0L,
    val lastScanAt: Long = 0L,
    val lastScanFoundProtectable: Boolean = false,
    val lastScanLimitReached: Boolean = false
)

object RebornEnergyWeeklyPersistence {
    private const val TAG = "RebornEnergyWeekly"
    private const val STORE_KEY = "reborn_energy_weekly_state"

    private fun currentUserStore(): UserDataStore? {
        val currentUid = AccountSessionCoordinator.currentUserId() ?: UserMap.currentUid
        return UserDataStoreManager.getInstance(currentUid)
    }

    private fun newState(configSignature: String, weekStart: Long): RebornWeeklyState {
        return RebornWeeklyState(
            weekStart = weekStart,
            configSignature = configSignature,
            completed = false,
            completedAt = 0L,
            lastScanAt = 0L,
            lastScanFoundProtectable = false,
            lastScanLimitReached = false
        )
    }

    fun getWeekStartTimestamp(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun..7=Sat
        val daysSinceMonday = (dayOfWeek + 5) % 7 // Mon->0, Tue->1, ..., Sun->6
        cal.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        return cal.timeInMillis
    }

    fun loadOrInit(configSignature: String, now: Long = System.currentTimeMillis()): RebornWeeklyState {
        val store = currentUserStore()
        val typeRef = object : TypeReference<RebornWeeklyState>() {}
        val weekStart = getWeekStartTimestamp(now)
        if (store == null) {
            return newState(configSignature, weekStart)
        }

        val state = store.getOrCreate(STORE_KEY, typeRef)

        if (state.weekStart != weekStart || state.configSignature != configSignature) {
            val newState = newState(configSignature, weekStart)
            store.put(STORE_KEY, newState)
            Log.forest("🔄 复活能量周轮状态已重置(weekStart=$weekStart)")
            return newState
        }
        return state
    }

    fun updateAfterScan(
        configSignature: String,
        scanAt: Long,
        foundProtectable: Boolean,
        limitReached: Boolean,
        completed: Boolean
    ): RebornWeeklyState {
        val store = currentUserStore()
        val state = loadOrInit(configSignature, scanAt)

        val newState = state.copy(
            completed = completed,
            completedAt = if (completed) (state.completedAt.takeIf { it > 0 } ?: scanAt) else 0L,
            lastScanAt = scanAt,
            lastScanFoundProtectable = foundProtectable,
            lastScanLimitReached = limitReached
        )
        store?.put(STORE_KEY, newState)
        return newState
    }
}


