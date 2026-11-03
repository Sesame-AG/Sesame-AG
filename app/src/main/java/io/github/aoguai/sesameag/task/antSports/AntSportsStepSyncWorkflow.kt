package io.github.aoguai.sesameag.task.antSports

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Log

internal fun AntSports.runStepSyncWorkflow() {
    if (isSyncStepEnabled() &&
        !Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE) &&
        isEarliestSyncStepTimeAllowed()
    ) {
        if (isEnergyOnlyModeNow()) {
            Log.sports("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，跳过同步步数")
            return
        }
        syncStepTask()
    }
}
