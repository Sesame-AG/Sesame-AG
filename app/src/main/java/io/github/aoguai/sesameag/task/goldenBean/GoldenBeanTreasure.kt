package io.github.aoguai.sesameag.task.goldenBean

import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.util.Log
import kotlin.math.max

class GoldenBeanTreasure : ModelTask() {
    internal var executeIntervalInt: Int = 0

    private lateinit var executeInterval: IntegerModelField
    internal lateinit var goldenBeanTreasure: BooleanModelField
    internal lateinit var goldenBeanManureExchangeDailyReserveAmount: IntegerModelField
    internal lateinit var goldenBeanSesameExchangeDailyBeanAmount: IntegerModelField

    override fun getName(): String = "金豆夺宝"

    override fun getGroup(): ModelGroup = ModelGroup.GOLDEN_BEAN

    override fun getIcon(): String = "GoldenBeanTreasure.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            IntegerModelField("executeInterval", "操作间隔(毫秒)", 500, 500, null)
                .withDesc("单次金豆操作之间的等待时间，过小可能增加风控。")
                .also { executeInterval = it },
        )
        modelFields.addField(
            BooleanModelField("goldenBeanTreasure", "签到、任务、矿工与乐园奖励", false)
                .withDesc("自动处理金豆夺宝签到、任务、金猫矿工和金豆乐园抽奖。")
                .also { goldenBeanTreasure = it },
        )
        modelFields.addField(
            IntegerModelField(
                "goldenBeanManureExchangeDailyReserveAmount",
                "肥料换豆每日额度",
                0,
                -1,
                null,
            )
                .withDesc(
                    "0 不自动换豆；正数为当天换豆的肥料额度；-1 按服务端可兑换额度处理。余额、资格、最低兑换量或每日额度不足时，本轮不换豆。",
                )
                .also { goldenBeanManureExchangeDailyReserveAmount = it },
        )
        modelFields.addField(
            IntegerModelField(
                "goldenBeanSesameExchangeDailyBeanAmount",
                "芝麻粒换豆每日金豆额度",
                0,
                -1,
                10000,
            )
                .withDesc(
                    "0 不自动兑换；正数为当天目标金豆量；-1 按芝麻炼金入口服务端可兑换额度处理。不会自动消耗芝麻粒。",
                )
                .also { goldenBeanSesameExchangeDailyBeanAmount = it },
        )
        return modelFields
    }

    override suspend fun runSuspend() {
        try {
            Log.goldenBean("执行开始-${getName()}")
            executeIntervalInt = max(executeInterval.value ?: 0, 500)
            if (goldenBeanTreasure.value == true) {
                runGoldenBeanTreasure()
            } else {
                Log.goldenBean("${getName()}主流程未开启，本轮跳过签到、任务、矿工与乐园奖励")
            }
            if (goldenBeanTreasure.value != true) {
                runGoldenBeanManureExchangeIfNeeded()
                runGoldenBeanSesameExchangeIfNeeded()
            }
        } catch (t: Throwable) {
            Log.printStackTrace("GoldenBeanTreasure", "start.run err:", t)
        } finally {
            Log.goldenBean("执行结束-${getName()}")
        }
    }

    internal fun runGoldenBeanSesameExchangeIfNeeded(): Boolean {
        val configuredBeanAmount = goldenBeanSesameExchangeDailyBeanAmount.value ?: 0
        if (configuredBeanAmount == 0) {
            return false
        }
        val indexResponse =
            GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index(GoldenBeanRpcCall.ZHIMA_ENTRY))
        if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
            Log.error("GoldenBeanTreasure", "金豆夺宝芝麻粒换豆资格查询失败 raw=${indexResponse ?: "EMPTY"}")
            return false
        }
        val plan = GoldenBeanTreasureSupport.planSesameExchange(indexResponse, configuredBeanAmount) ?: return false
        return GoldenBeanTreasureSupport.exchangePlannedSesame(indexResponse, plan)
    }

    internal fun runGoldenBeanManureExchangeIfNeeded(): Boolean {
        val configuredReserveAmount = goldenBeanManureExchangeDailyReserveAmount.value ?: 0
        if (configuredReserveAmount == 0) {
            return false
        }
        val indexResponse =
            try {
                GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
            } catch (error: Exception) {
                Log.printStackTrace("GoldenBeanTreasure", "肥料换豆资格查询异常:", error)
                return false
            }
        if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
            Log.error("金豆夺宝", "金豆夺宝肥料换豆资格查询失败 raw=${indexResponse ?: "EMPTY"}")
            return false
        }
        val plan = GoldenBeanTreasureSupport.planManureExchange(
            indexResponse,
            configuredReserveAmount,
        ) ?: return false
        val finalIndexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
        if (finalIndexResponse == null || !GoldenBeanTreasureSupport.isSuccess(finalIndexResponse)) {
            Log.error("金豆夺宝", "金豆夺宝肥料兑换最终查询失败 raw=${finalIndexResponse ?: "EMPTY"}")
            return false
        }
        return GoldenBeanTreasureSupport.exchangePlannedManure(finalIndexResponse, plan)
    }
}
