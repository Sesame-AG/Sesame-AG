package io.github.aoguai.sesameag.task.antSports

import io.github.aoguai.sesameag.util.Log

internal fun AntSports.runNeverlandWorkflow() {
    if (neverlandTask.value != true && neverlandGrid.value != true && sportsEnergyExchange.value != true) {
        return
    }
    Log.sports("开始执行健康岛")
    NeverlandTaskHandler().runNeverland()
    Log.sports("健康岛结束")
}
