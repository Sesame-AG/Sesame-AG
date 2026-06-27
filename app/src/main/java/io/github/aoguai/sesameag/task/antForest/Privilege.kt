package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

object Privilege {
    private const val TAG = "Privilege"

    private const val PREFIX_PRIVILEGE = "青春特权🌸"
    private const val PREFIX_SIGN = "青春特权🧧"

    // 任务状态
    private const val TASK_RECEIVED = "RECEIVED"
    private const val TASK_FINISHED = "FINISHED"
    private const val RPC_SUCCESS = "SUCCESS"

    // 时间范围
    private const val SIGN_START_HOUR = 5
    private const val SIGN_END_HOUR = 10

    // 青春特权任务配置
    private val YOUTH_TASKS = listOf(
        YouthTask("DNHZ_SL_college", "DAXUESHENG_SJK", "双击卡"),
        YouthTask("DXS_BHZ", "NENGLIANGZHAO_20230807", "保护罩"),
        YouthTask("DXS_JSQ", "JIASUQI_20230808", "加速器")
    )

    fun youthPrivilege(): Boolean {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_PRIVILEGE_RECEIVED)) return false

        var allCompleted = true
        for (task in YOUTH_TASKS) {
            val result = processYouthTask(task)
            if (!result.isCompleted()) {
                allCompleted = false
            }
        }

        if (allCompleted) Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_PRIVILEGE_RECEIVED)
        return allCompleted
    }

    private fun processYouthTask(task: YouthTask): YouthTaskResult {
        val forestTasksNew = getForestTasks(task.queryParam)
            ?: return YouthTaskResult(YouthTaskState.QUERY_FAILED, "查询失败")
        return handleForestTasks(forestTasksNew, task.receiveParam, task.name)
    }

    private fun getForestTasks(queryParam: String): JSONArray? {
        val response = AntForestRpcCall.queryTaskListV2(queryParam)
        return try {
            JSONObject(response).getJSONArray("forestTasksNew")
        } catch (e: JSONException) {
            Log.error(TAG, "获取任务列表失败$e")
            null
        }
    }

    private fun handleForestTasks(forestTasks: JSONArray, taskType: String, taskName: String): YouthTaskResult {
        try {
            for (i in 0 until forestTasks.length()) {
                val taskGroup = forestTasks.optJSONObject(i) ?: continue
                val taskInfoList = taskGroup.getJSONArray("taskInfoList") ?: continue

                for (j in 0 until taskInfoList.length()) {
                    val task = taskInfoList.optJSONObject(j) ?: continue
                    val baseInfo = task.getJSONObject("taskBaseInfo") ?: continue

                    if (baseInfo.optString("taskType") != taskType) continue

                    return processSingleYouthTask(baseInfo, taskType, taskName)
                }
            }
        } catch (e: JSONException) {
            Log.error(TAG, "任务列表解析失败$e")
            return YouthTaskResult(YouthTaskState.EXCEPTION, "处理异常")
        }

        Log.forest("$PREFIX_PRIVILEGE[$taskName]未命中目标任务")
        return YouthTaskResult(YouthTaskState.TARGET_NOT_FOUND, "未命中目标任务")
    }

    private fun processSingleYouthTask(baseInfo: JSONObject, taskType: String, taskName: String): YouthTaskResult {
        val status = baseInfo.optString("taskStatus")

        return when (status) {
            TASK_RECEIVED -> {
                Log.forest("$PREFIX_PRIVILEGE[$taskName]已领取")
                YouthTaskResult(YouthTaskState.RECEIVED, "已领取")
            }
            TASK_FINISHED -> handleYouthTaskAward(taskType, taskName)
            else -> {
                Log.forest("$PREFIX_PRIVILEGE[$taskName]任务状态：$status")
                YouthTaskResult(YouthTaskState.BUSINESS_FAILED, status.ifEmpty { "任务状态未知" })
            }
        }
    }

    private fun handleYouthTaskAward(taskType: String, taskName: String): YouthTaskResult {
        try {
            val response = JSONObject(AntForestRpcCall.receiveTaskAwardV2(taskType))
            val resultDesc = response.optString("desc")
            val logMessage = if (resultDesc == "处理成功") "领取成功" else "领取结果：$resultDesc"
            Log.forest("$PREFIX_PRIVILEGE[$taskName]$logMessage")
            return if (resultDesc == "处理成功") {
                YouthTaskResult(YouthTaskState.AWARD_SUCCESS, resultDesc)
            } else {
                YouthTaskResult(YouthTaskState.BUSINESS_FAILED, resultDesc.ifEmpty { "未知领奖结果" })
            }
        } catch (e: JSONException) {
            Log.error(TAG, "奖励领取结果解析失败$e")
            return YouthTaskResult(YouthTaskState.EXCEPTION, "处理异常")
        }
    }

    fun studentSignInRedEnvelope() {
        if (!isSignInTimeValid()) {
            Log.forest("$PREFIX_SIGN 5点前不执行签到")
            return
        }

        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_PRIVILEGE_STUDENT_TASK)) {
            Log.forest("$PREFIX_SIGN 今日已完成签到")
            return
        }

        try {
            processStudentSignIn()
        } catch (e: Exception) {
            Log.error(TAG, "学生签到异常$e")
        }
    }

    private fun isSignInTimeValid(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= SIGN_START_HOUR
    }

    private fun processStudentSignIn() {
        val response = AntForestRpcCall.studentQqueryCheckInModel()
        val result = try {
            JSONObject(response)
        } catch (e: JSONException) {
            Log.error(TAG, "学生签到模型解析失败$e")
            return
        }

        if (result.optString("resultCode") != RPC_SUCCESS) {
            Log.forest("$PREFIX_SIGN 查询失败：${result.optString("resultDesc")}")
            return
        }

        val checkInInfo = result.optJSONObject("studentCheckInInfo")
        if (checkInInfo == null || checkInInfo.optString("action") == "DO_TASK") {
            Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_PRIVILEGE_STUDENT_TASK)
            return
        }

        executeStudentSignIn()
    }

    private fun executeStudentSignIn() {
        try {
            val tag = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < SIGN_END_HOUR) "double" else "single"
            val response = AntForestRpcCall.studentCheckin()
            val result = JSONObject(response)
            handleSignInResult(result, tag)
        } catch (e: JSONException) {
            Log.error(TAG, "学生签到失败：${e.message}")
        }
    }

    private fun handleSignInResult(result: JSONObject, tag: String) {
        val code = result.optString("resultCode")
        val desc = result.optString("resultDesc")

        if (code == RPC_SUCCESS) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_PRIVILEGE_STUDENT_TASK)
            Log.forest("$PREFIX_SIGN$tag$desc")
        } else {
            var errorMsg = desc
            if (desc.contains("不匹配")) {
                errorMsg += "可能账户不符合条件"
            }
            Log.error(TAG, "$PREFIX_SIGN$tag 失败：$errorMsg")
        }
    }

    data class YouthTask(val queryParam: String, val receiveParam: String, val name: String)

    private enum class YouthTaskState {
        QUERY_FAILED,
        TARGET_NOT_FOUND,
        RECEIVED,
        AWARD_SUCCESS,
        BUSINESS_FAILED,
        EXCEPTION
    }

    private data class YouthTaskResult(val state: YouthTaskState, val message: String) {
        fun isCompleted(): Boolean = state == YouthTaskState.RECEIVED || state == YouthTaskState.AWARD_SUCCESS
    }
}
