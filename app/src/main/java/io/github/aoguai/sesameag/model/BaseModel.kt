package io.github.aoguai.sesameag.model

import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField.MultiplyIntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimePointListModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeWindowListModelField
import io.github.aoguai.sesameag.model.modelFieldExt.StringModelField
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.BeachMap
import io.github.aoguai.sesameag.util.maps.IdMapManager

/**
 * 基础配置模块
 */
class BaseModel : Model() {
    override fun getName(): String {
        return "基础"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.BASE
    }

    override fun getIcon(): String {
        return "BaseModel.png"
    }

    override val enableFieldName: String
        get() = "启用模块"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(stayAwake) //是否保持唤醒状态
        modelFields.addField(manualTriggerAutoSchedule) //手动触发是否自动安排下次执行
        modelFields.addField(checkInterval) //执行间隔时间
        modelFields.addField(offlineCooldown) //离线冷却时间
        modelFields.addField(taskExecutionRounds) //轮数
        modelFields.addField(taskTimeout) //单任务超时时间
        modelFields.addField(modelSleepTime) //模块休眠时间范围
        modelFields.addField(execAtTimeList) //定时执行的时间点列表
        modelFields.addField(wakenAtTimeList) //定时唤醒的时间点列表
        modelFields.addField(energyTime) //能量收集的时间范围
        modelFields.addField(timedTaskModel) //定时任务模式选择
        modelFields.addField(timeoutRestart) //超时是否重启
        modelFields.addField(waitWhenException) //异常发生时的等待时间
        modelFields.addField(errNotify) //异常通知开关
        modelFields.addField(setMaxErrorCount) //异常次数阈值
        modelFields.addField(newRpc) //是否启用新接口
        modelFields.addField(customRpcScheduleEnable) //自定义RPC(配置文件+定时执行)
        modelFields.addField(debugMode) //是否开启抓包调试模式
        modelFields.addField(sendHookData) //启用Hook数据转发
        modelFields.addField(sendHookDataUrl) //Hook数据转发地址

        modelFields.addField(batteryPerm) //是否申请模块电池优化豁免
        modelFields.addField(recordLog) //是否记录record日志
        modelFields.addField(runtimeLog) //是否记录runtime日志
        modelFields.addField(showToast) //是否显示气泡提示
        modelFields.addField(enableOnGoing) //是否开启状态栏禁删
        modelFields.addField(languageSimplifiedChinese) //是否只显示中文并设置时区
        modelFields.addField(toastPerfix)//气泡提示的前缀
        return modelFields
    }

    interface TimedTaskModel {
        companion object {
            const val SYSTEM: Int = 0
            const val PROGRAM: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("🤖系统计时", "📦程序计时")
        }
    }

    companion object {
        private const val TAG = "BaseModel"

        /**
         * 是否保持唤醒状态
         */
        val stayAwake: BooleanModelField = BooleanModelField("stayAwake", "保持唤醒", false).withDesc(
            "开启后，模块只在任务到期执行窗口短时保持 CPU 唤醒；长时间等待仍依赖系统闹钟或进程内计时。"
        )

        /**
         * //手动触发是否自动安排下次执行
         */
        val manualTriggerAutoSchedule: BooleanModelField = BooleanModelField("manualTriggerAutoSchedule", "手动触发目标应用运行", false).withDesc(
            "开启后，手动回到目标应用时会额外补触发一次任务执行；关闭时只响应定时、广播等自动触发。"
        ) //一般人不开这个

        /**
         * 执行间隔时间（分钟）
         */
        val checkInterval: MultiplyIntegerModelField = MultiplyIntegerModelField("checkInterval", "执行间隔(分钟)", 50, 1, 12 * 60, 60000).withDesc(
            "自动轮询的基础间隔，单位分钟；开启定时执行后也会以此作为相邻调度窗口的基础跨度。"
        ) //此处调整至30分钟执行一次，可能会比平常耗电一点。。

        /**
         * 离线冷却时间（分钟）
         * 0 表示跟随执行间隔（checkInterval）
         */
        val offlineCooldown: MultiplyIntegerModelField = MultiplyIntegerModelField(
            "offlineCooldown",
            "离线冷却(分钟,0=随执行间隔)",
            0,
            0,
            24 * 60,
            60000
        ).withDesc("触发网络异常或离线熔断后的冷却时长；填 0 时跟随执行间隔，并受最小保护时间限制。")

        /**
         * 任务执行轮数配置
         */
        val taskExecutionRounds: IntegerModelField = IntegerModelField("taskExecutionRounds", "任务执行轮数", 1, 1, 99).withDesc(
            "每次总调度内重复执行任务的轮数，通常 1 轮即可；调高会增加耗时和风控概率。"
        ) //1轮就好，没必要2轮

        /**
         * 单任务超时时间（分钟）
         */
        val taskTimeout: MultiplyIntegerModelField = MultiplyIntegerModelField("taskTimeout", "单任务超时(分钟)", 10, 1, 24 * 60, 60000).withDesc(
            "普通任务单轮执行超过该时间会被判定超时；森林、庄园、运动等白名单长任务不受限制。"
        )

        /**
         * 定时执行的时间点列表
         */
        val execAtTimeList: TimePointListModelField = TimePointListModelField(
            "execAtTimeList",
            "定时执行",
            "-1",
            allowDisable = true
        ).withDesc("自动执行的时间点列表。关闭后仅保留轮询间隔调度。")

        /**
         * 定时唤醒的时间点列表
         */
        val wakenAtTimeList: TimePointListModelField = TimePointListModelField(
            "wakenAtTimeList",
            "定时唤醒",
            "-1",
            allowDisable = true
        ).withDesc("自动唤醒目标应用的时间点列表，适合凌晨或关键时段提前拉起进程。")

        /**
         * 能量收集的时间范围
         */
        val energyTime: TimeWindowListModelField = TimeWindowListModelField(
            "energyTime",
            "只收能量时间",
            "-1",
            allowDisable = true
        ).withDesc("命中该时间段时，只保留蚂蚁森林等能量相关任务。")

        /**
         * 模块休眠时间范围
         */
        val modelSleepTime: TimeWindowListModelField =
            TimeWindowListModelField(
                "modelSleepTime",
                "模块休眠时间",
                "-1",
                allowDisable = true
            ).withDesc("命中该时间段时暂停常规任务执行。")

        /**
         * 定时任务模式选择
         */
        val timedTaskModel: ChoiceModelField = ChoiceModelField(
            "timedTaskModel",
            "定时任务模式",
            TimedTaskModel.Companion.SYSTEM,
            TimedTaskModel.Companion.nickNames
        ).withDesc("控制进程存活时的等待策略：系统计时使用普通协程等待；程序计时使用进程内等待并在执行窗口短时唤醒，不保证进程被杀后仍执行。")

        /**
         * 超时是否重启
         */
        val timeoutRestart: BooleanModelField = BooleanModelField("timeoutRestart", "超时重启", false).withDesc(
            "RPC 或关键流程超时后，是否尝试重新拉起目标应用并恢复执行链路。"
        )

        /**
         * 异常发生时的等待时间（分钟）
         */
        val waitWhenException: MultiplyIntegerModelField = MultiplyIntegerModelField(
            "waitWhenException",
            "异常等待时间(分钟)",
            60,
            0,
            24 * 60,
            60000
        ).withDesc("任务运行异常后的额外等待时间；填 0 表示异常后不额外挂起。")

        /**
         * 异常通知开关
         */
        val errNotify: BooleanModelField = BooleanModelField("errNotify", "开启异常通知", false).withDesc(
            "开启后，在连续网络异常、离线熔断等场景发送状态栏异常通知。"
        )

        val setMaxErrorCount: IntegerModelField = IntegerModelField("setMaxErrorCount", "异常次数阈值", 8).withDesc(
            "网络或 RPC 连续异常达到该次数后进入离线冷却，并可结合异常通知提醒。"
        )

        /**
         * 是否启用新接口（最低支持版本 v10.3.96.8100）
         */
        val newRpc: BooleanModelField = BooleanModelField("newRpc", "使用新接口(最低支持v10.3.96.8100)", false).withDesc(
            "优先使用新版 RPC 桥接接口；低版本目标应用不兼容时再考虑关闭。"
        )

        /**
         * 是否开启抓包调试模式
         */
        val debugMode: BooleanModelField = BooleanModelField("debugMode", "开启抓包(基于新接口)", false).withDesc(
            "开启后启动抓包调试链路与本地调试服务；Hook/RPC 原始请求响应主要写入抓包日志，调试工具结果写入调试日志。"
        )

        /**
         * 是否申请模块自身的电池优化豁免
         */
        val batteryPerm: BooleanModelField = BooleanModelField("batteryPerm", "申请模块电池优化豁免", false).withDesc(
            "打开模块界面时检查并按标准 Android 流程申请模块自身的忽略电池优化权限；自动调度链路只读取状态并在缺失时降级，不会主动跳转授权页。"
        )


        /**
         * 是否记录record日志
         */
        val recordLog: BooleanModelField = BooleanModelField("recordLog", "总览 | 记录 record 日志", false).withDesc(
            "记录聚合后的总览日志与执行摘要，用于查看调度流程、配置状态和全局生命周期；关闭可减少日志体积。"
        )

        /**
         * 是否记录runtime日志
         */
        val runtimeLog: BooleanModelField = BooleanModelField("runtimeLog", "技术 | 记录 runtime 日志", false).withDesc(
            "记录底层桥接、缓存命中、合流与内部诊断日志；默认建议关闭，仅排障时开启。"
        )

        /**
         * 是否显示气泡提示
         */
        val showToast: BooleanModelField = BooleanModelField("showToast", "气泡提示", false).withDesc(
            "控制模块弹出的普通气泡提示开关。关闭后不再显示常规提示气泡。"
        )

        val toastPerfix: StringModelField = StringModelField("toastPerfix", "气泡前缀", "").withDesc(
            "气泡提示前置文本，非空时会拼接在每条提示前。"
        )

        /**
         * 只显示中文并设置时区
         */
        val languageSimplifiedChinese: BooleanModelField = BooleanModelField("languageSimplifiedChinese", "只显示中文并设置时区", false).withDesc(
            "启动时优先设置简体中文与对应时区，减少页面文案差异导致的识别偏差。"
        )

        /**
         * 是否开启状态栏禁删
         */
        val enableOnGoing: BooleanModelField = BooleanModelField("enableOnGoing", "运行通知不可左滑删除", false).withDesc(
            "开启后运行状态通知会标记为不可左滑删除，避免执行过程中被误清除。"
        )

        val sendHookData: BooleanModelField = BooleanModelField("sendHookData", "启用Hook数据转发", false).withDesc(
            "仅在“开启抓包(基于新接口)”和新 RPC 同时启用时生效，把 Hook 到的数据转发到指定地址。"
        )

        val sendHookDataUrl: StringModelField = StringModelField("sendHookDataUrl", "Hook数据转发地址", "http://127.0.0.1:9527/hook").withDesc(
            "Hook 数据转发目标地址，仅在启用抓包与数据转发时使用。"
        )

        /**
         * 自定义 RPC（配置文件 + 定时执行）
         *
         * - 配置文件路径：`Android/media/.../sesame-AG/rpcRequest.json`（与「RPC 调试」同一份 JSON）
         * - 定时执行开关与每日次数在「RPC 调试」条目内设置
         * - 执行日志输出到“抓包日志(capture)”
         */
        val customRpcScheduleEnable: BooleanModelField =
            BooleanModelField("customRpcScheduleEnable", "自定义RPC | 配置文件定时执行(慎用)", false).withDesc(
                "按 rpcRequest.json 中的定时配置执行自定义 RPC 调试项，适合研究接口，开启前先确认配置内容。"
            )

        /**
         * 清理数据，在模块销毁时调用，清空 Reserve 和 Beach 数据。
         */
        @JvmStatic
        fun destroyData() {
            try {
                Log.record(TAG, "🧹清理所有数据")
                IdMapManager.getInstance(BeachMap::class.java).clear()
                //            IdMapManager.getInstance(ReserveaMap.class).clear();
//            IdMapManager.getInstance(CooperateMap.class).clear();
//            IdMapManager.getInstance(MemberBenefitsMap.class).clear();
//            IdMapManager.getInstance(ParadiseCoinBenefitIdMap.class).clear();
//            IdMapManager.getInstance(VitalityRewardsMap.class).clear();
                //其他也可以清理清理
            } catch (e: Exception) {
                Log.printStackTrace(e)
            }
        }
    }
}

