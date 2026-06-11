package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class AntMemberPointWorkflowPlan(
    val claimTaskAwards: Boolean,
    val claimMemberPoints: Boolean
)

internal fun AntMember.prepareMemberPointWorkflows(
    scope: CoroutineScope,
    deferredTasks: MutableList<Deferred<Unit>>
): AntMemberPointWorkflowPlan {
    val riskStoppedToday = hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
    val plan = AntMemberPointWorkflowPlan(
        claimTaskAwards = memberTask?.value == true && !riskStoppedToday,
        claimMemberPoints = (memberSign?.value == true || memberTask?.value == true) && !riskStoppedToday
    )

    if (memberSign?.value == true) {
        if (riskStoppedToday) {
            Log.member("⏭️ 今天会员营销链路已因风控止损，跳过会员签到")
        } else if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE)) {
            Log.member("⏭️ 今天已处理过会员签到，跳过执行")
        } else {
            deferredTasks.add(scope.async(Dispatchers.IO) { doMemberSign() })
        }
    }

    if (memberTask?.value == true) {
        if (riskStoppedToday) {
            Log.member("⏭️ 今天会员任务已因风控/离线止损，停止执行")
        } else if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_EMPTY_TODAY)) {
            Log.member("⏭️ 今日会员任务已处理，跳过执行")
            deferredTasks.add(scope.async(Dispatchers.IO) { handleYebExpGoldTasks() })
        } else {
            deferredTasks.add(scope.async(Dispatchers.IO) { doAllMemberAvailableTaskCompat() })
            deferredTasks.add(scope.async(Dispatchers.IO) { handleYebExpGoldTasks() })
        }
    }

    if (memberPointExchangeBenefit?.value == true) {
        deferredTasks.add(scope.async(Dispatchers.IO) { memberPointExchangeBenefit() })
    }

    return plan
}

internal suspend fun AntMember.finishMemberPointWorkflows(plan: AntMemberPointWorkflowPlan) {
    if (plan.claimTaskAwards) {
        if (ApplicationHookConstants.isOffline()) {
            Log.member("⏭️ 当前处于离线模式，跳过会员阶段奖励领取")
        } else {
            val claimedRewardCount = collectMemberTaskProcessAwards()
            if (claimedRewardCount > 0) {
                Log.member("🎯 会员阶段奖励领取完成，共${claimedRewardCount}项")
            }
        }
    }

    if (plan.claimMemberPoints) {
        if (ApplicationHookConstants.isOffline()) {
            Log.member("⏭️ 当前处于离线模式，跳过统一领取会员积分")
        } else {
            Log.member("🎯 会员流程执行完成，开始统一领取会员积分")
            AntMember.queryPointCert(1, 20)
        }
    }
}
