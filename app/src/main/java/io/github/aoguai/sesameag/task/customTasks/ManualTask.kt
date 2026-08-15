package io.github.aoguai.sesameag.task.customTasks

import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antCooperate.AntCooperate
import io.github.aoguai.sesameag.task.antDodo.AntDodo
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antFishPond.AntFishPond
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.antMember.AntMember
import io.github.aoguai.sesameag.task.antOcean.AntOcean
import io.github.aoguai.sesameag.task.antOrchard.AntOrchard
import io.github.aoguai.sesameag.task.antSesameCredit.AntSesameCredit
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.task.antStall.AntStall
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手动任务执行器
 */
object ManualTask {

    /**
     * 手动任务流总开关
     */
    @Volatile
    var isManualEnabled = true

    /**
     * 标记手动任务是否正在运行，用于与自动任务互斥
     */
    @Volatile
    var isManualRunning = false
        private set

    /**
     * 为 Java 提供的非 suspend 启动接口
     */
    @JvmStatic
    @JvmOverloads
    fun runSingle(task: CustomTask, extraParams: Map<String, Any> = emptyMap()) {
        GlobalThreadPools.execute {
            run(listOf(task), extraParams)
        }
    }

    /**
     * 顺序执行选中的庄园子任务
     */
    suspend fun run(tasks: List<CustomTask>, extraParams: Map<String, Any> = emptyMap()) {
        if (!isManualEnabled) {
            Log.record("ManualTask", "⚠️ 手动任务流总开关已关闭，无法执行")
            return
        }

        if (tasks.isEmpty()) {
            Log.record("ManualTask", "⚠️ 未选中任何子任务")
            return
        }

        if (!WorkflowRootGuard.hasRoot(forceRefresh = true, reason = "manual_task_run")) {
            Log.record("ManualTask", "⛔ 未检测到可用执行权限，手动任务不会执行")
            return
        }
        if (!Config.isLegalAcceptedForCurrentVersion()) {
            Log.record("ManualTask", "⛔ 未勾选已阅读 LICENSE 与 LEGAL 说明，手动任务不会执行")
            return
        }

        if (isManualRunning) {
            Log.record("ManualTask", "⚠️ 手动任务已在运行中，请勿重复启动")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                isManualRunning = true
                Log.record("ManualTask", "🚀 开始执行手动任务序列...")

                for (task in tasks) {
                    try {
                        Log.record("ManualTask", "⏳ 正在执行: ${task.displayName}...")
                        when (task) {
                            // 森林类任务
                            CustomTask.FOREST_WHACK_MOLE -> {
                                val instance = getForestInstance()
                                if (instance != null) {
                                    instance.manualWhackMole()
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载森林模块")
                                }
                            }

                            CustomTask.FOREST_ENERGY_RAIN -> {
                                val instance = getForestInstance()
                                if (instance != null) {
                                    val exchange = extraParams["exchangeEnergyRainCard"] as? Boolean ?: false
                                    instance.manualUseEnergyRain(exchange)
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载森林模块")
                                }
                            }

                            // 庄园类任务
                            CustomTask.FARM_SEND_BACK_ANIMAL -> getFarmInstance()?.manualSendBackAnimal()
                            CustomTask.FARM_GAME_LOGIC -> getFarmInstance()?.manualFarmGameLogic()
                            CustomTask.FARM_CHOUCHOULE -> getFarmInstance()?.manualChouChouLeLogic()
                            CustomTask.FARM_SPECIAL_FOOD -> {
                                val count = extraParams["specialFoodCount"] as? Int ?: 0
                                getFarmInstance()?.manualUseSpecialFood(count)
                            }
                            CustomTask.FARM_USE_TOOL -> {
                                val toolType = extraParams["toolType"] as? String ?: ""
                                val toolCount = extraParams["toolCount"] as? Int ?: 1
                                getFarmInstance()?.manualUseFarmTool(toolType, toolCount)
                            }

                            // 任务模块整体手动触发：直接运行该模块的 runSuspend()，跳过 check() 调度门控
                            CustomTask.ANT_FOREST -> runModuleTask(AntForest::class.java)
                            CustomTask.ANT_FARM -> runModuleTask(AntFarm::class.java)
                            CustomTask.ANT_OCEAN -> runModuleTask(AntOcean::class.java)
                            CustomTask.ANT_STALL -> runModuleTask(AntStall::class.java)
                            CustomTask.ANT_DODO -> runModuleTask(AntDodo::class.java)
                            CustomTask.ANT_COOPERATE -> runModuleTask(AntCooperate::class.java)
                            CustomTask.ANT_MEMBER -> runModuleTask(AntMember::class.java)
                            CustomTask.ANT_SESAME_CREDIT -> runModuleTask(AntSesameCredit::class.java)
                            CustomTask.ANT_ORCHARD -> runModuleTask(AntOrchard::class.java)
                            CustomTask.ANT_FISH_POND -> runModuleTask(AntFishPond::class.java)
                            CustomTask.ANT_SPORTS -> runModuleTask(AntSports::class.java)
                        }
                    } catch (t: Throwable) {
                        Log.record("ManualTask", "❌ 执行 ${task.displayName} 出错: ${t.message}")
                        Log.printStackTrace(t)
                    }
                }
                Log.record("ManualTask", "✅ 手动任务执行完毕")
            } finally {
                isManualRunning = false
            }
        }
    }

    /**
     * 按需获取并确保蚂蚁森林实例已加载
     */
    private fun getForestInstance(): AntForest? {
        if (AntForest.instance == null) {
            val loader = ApplicationHook.classLoader ?: return null
            Model.getModel(AntForest::class.java)?.let {
                Log.record("ManualTask", "⚙️ 正在按需加载森林模块...")
                it.prepare()
                it.boot(loader)
            }
        }
        return AntForest.instance
    }

    /**
     * 按需获取并确保蚂蚁庄园实例已加载
     */
    private fun getFarmInstance(): AntFarm? {
        if (AntFarm.instance == null) {
            val loader = ApplicationHook.classLoader ?: return null
            Model.getModel(AntFarm::class.java)?.let {
                Log.record("ManualTask", "⚙️ 正在按需加载庄园模块...")
                it.prepare()
                it.boot(loader)
            }
        }
        return AntFarm.instance
    }

    /**
     * 手动触发任务模块整体执行：直接调用该模块的 runSuspend()，绕过 check() 调度门控。
     * 适用于风控后手动重跑单个模块，无需等待自动调度。
     */
    private suspend fun <T : ModelTask> runModuleTask(clazz: Class<T>) {
        val instance = Model.getModel(clazz)
        if (instance == null) {
            Log.record("ManualTask", "❌ 无法加载 ${clazz.simpleName} 模块")
            return
        }
        instance.run()
    }
}

