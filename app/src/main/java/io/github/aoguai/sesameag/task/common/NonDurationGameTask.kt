package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

/** 固定业务动作子集。游戏及 SDK 参数只由 GameTask 定义，完成与领奖仍由所属模块回查。 */
object NonDurationGameTask {
    suspend fun advance(
        game: GameTask,
        vararg roots: JSONObject?,
        channel: String = "",
        detail: String = "",
        logger: (String) -> Unit,
        confirmProgress: (suspend () -> Boolean?)? = null,
    ): TaskFlowActionResult {
        if (ApplicationHookConstants.isOffline()) {
            return TaskFlowActionResult.failure(
                TaskRpcFailureType.BUSINESS_LIMIT, message = "当前已离线，停止游戏上报",
                rpc = "GameTask.reportDetailed", detail = detail, stopCurrentRound = true,
            )
        }
        val descriptor = GameCenterPlayRpcCall.describeTask(*roots)
        val actionChannel = descriptor.urlParameters["channel"].orEmpty()
            .ifBlank { channel.ifBlank { game.channel } }
        val uid = UserMap.currentUid
        val report = game.reportDetailed(
            1, actionFinishChannel = actionChannel, logger = logger,
            confirmProgress = {
                if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) null
                else if (confirmProgress == null) false
                else {
                    val confirmed = confirmProgress()
                    if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) null else confirmed
                }
            },
        )
        val actionDetail = "$detail appId=${game.appId} channel=$actionChannel reports=${report.successfulReports}/${report.requiredSuccesses}"
        if (!report.accepted) {
            Log.error("NonDurationGameTask", "${report.failureMessage} code=${report.failureCode} raw=${report.failureRaw} $actionDetail")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = report.failureCode,
                message = report.failureMessage,
                raw = report.failureRaw,
                rpc = "GameTask.reportDetailed",
                detail = actionDetail,
                stopCurrentRound = report.stopCurrentRound,
                continueCurrentRoundOnFailure = !report.stopCurrentRound,
            ).copy(refreshAfterAction = report.successfulReports > 0, progressChanged = false)
        }
        return TaskFlowActionResult.defer(
            DeferredReason.STATE_CONFIRMATION,
            message = if (report.businessConfirmed) "游戏业务状态已推进，继续回查并领奖" else "游戏业务上报已接受，回查任务与奖励",
            rpc = "GameTask.reportDetailed",
            detail = actionDetail,
            refreshAfterAction = true,
            progressChanged = report.businessConfirmed,
        )
    }
}
