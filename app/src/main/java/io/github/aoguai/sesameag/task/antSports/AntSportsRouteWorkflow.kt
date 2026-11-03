package io.github.aoguai.sesameag.task.antSports

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.util.maps.UserMap

internal fun AntSports.runRouteWorkflow() {
    runNewRouteWorkflow()
    runStepDonationWorkflow()
}

internal fun AntSports.runNewRouteWorkflow() {
    if (walk.value != true) {
        return
    }
    getWalkPathThemeIdOnConfig()
    walk()
}

internal fun AntSports.runStepDonationWorkflow() {
    if (donateCharityCoin.value == true && Status.canDonateCharityCoin()) {
        queryProjectList()
    }

    val currentUid = UserMap.currentUid
    if ((minExchangeCount.value ?: 0) > 0 &&
        currentUid != null &&
        Status.canExchangeToday(currentUid)
    ) {
        queryWalkStep()
    }
}
