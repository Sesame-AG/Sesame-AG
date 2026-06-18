package io.github.aoguai.sesameag.task.antSesameCredit

import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class AntSesameCreditWorkflowPlan(
    val claimSesame: Boolean,
    val claimProgress: Boolean
)

internal suspend fun AntSesameCredit.prepareSesameWorkflows(
    scope: CoroutineScope,
    deferredTasks: MutableList<Deferred<Unit>>
): AntSesameCreditWorkflowPlan {
    val needSesameWorkflow =
        sesameGrainExchange?.value == true ||
            sesameTask?.value == true ||
            collectSesame?.value == true ||
            sesameAlchemy?.value == true ||
            enableZhimaTree?.value == true
    if (!needSesameWorkflow) {
        return AntSesameCreditWorkflowPlan(false, false)
    }

    if (!AntSesameCredit.checkSesameCanRun()) {
        return AntSesameCreditWorkflowPlan(false, false)
    }

    var claimSesame = false
    var claimProgress = false

    if (sesameGrainExchange?.value == true) {
        deferredTasks.add(scope.async(Dispatchers.IO) { doSesameGrainExchange() })
    }

    if (sesameTask?.value == true || collectSesame?.value == true) {
        claimProgress = true
        if (hasFlagToday(StatusFlags.FLAG_SESAME_ZML_CHECKIN_DONE)) {
            Log.sesame("⏭️ 今天已处理过芝麻粒福利签到，跳过执行")
        } else {
            doSesameZmlCheckIn()
        }

        if (sesameTask?.value == true) {
            if (hasFlagToday(StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK)) {
                Log.sesame("⏭️ 今天已完成过芝麻信用任务，跳过执行")
            } else {
                Log.sesame("🎮 开始执行芝麻信用任务")
                val sesameTaskSummary = doAllAvailableSesameTask()
                if (sesameTaskSummary.interrupted || ApplicationHookConstants.isOffline()) {
                    Log.sesame("芝麻信用任务被离线或验证状态中断，保留后续重试机会")
                } else {
                    handleGrowthGuideTasks()
                    handleNewTaskCenterTasks()
                    Log.sesame("芝麻信用任务已执行，稍后统一领取涨分进度球")
                }
            }
        }

        if (collectSesame?.value == true) {
            if (hasFlagToday(StatusFlags.FLAG_SESAME_COLLECT_DONE)) {
                Log.sesame("⏭️ 今天已处理过芝麻粒领取，跳过执行")
            } else {
                claimSesame = true
                Log.sesame("🎯 芝麻相关任务执行中，稍后统一领取芝麻粒")
            }
        }
    }

    if (sesameAlchemy?.value == true) {
        deferredTasks.add(scope.async(Dispatchers.IO) {
            doSesameAlchemy()
            if (!hasFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)) {
                doSesameAlchemyNextDayAward()
            } else {
                Log.sesame("✅ 芝麻粒次日奖励已领取，今天不再执行")
            }
        })
    }

    if (enableZhimaTree?.value == true) {
        deferredTasks.add(scope.async(Dispatchers.IO) { doZhimaTree() })
    }

    return AntSesameCreditWorkflowPlan(
        claimSesame = claimSesame,
        claimProgress = claimProgress
    )
}

internal suspend fun AntSesameCredit.finishSesameWorkflows(plan: AntSesameCreditWorkflowPlan) {
    if (plan.claimSesame) {
        if (ApplicationHookConstants.isOffline()) {
            Log.sesame("⏭️ 当前处于离线模式，跳过统一领取芝麻粒")
        } else {
            Log.sesame("🎯 芝麻相关流程执行完成，开始统一领取芝麻粒")
            collectSesame(collectSesameWithOneClick?.value == true)
        }
    }

    if (plan.claimProgress) {
        if (ApplicationHookConstants.isOffline()) {
            Log.sesame("⏭️ 当前处于离线模式，跳过统一领取涨分进度球")
        } else {
            Log.sesame("🎯 芝麻信用流程执行完成，开始统一领取涨分进度球")
            AntSesameCredit.queryAndCollect()
        }
    }
}
