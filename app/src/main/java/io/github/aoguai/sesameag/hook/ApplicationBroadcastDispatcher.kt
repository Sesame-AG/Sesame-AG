package io.github.aoguai.sesameag.hook

import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleDefaults
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.ScheduledTaskRouter
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.antMember.AntMember
import io.github.aoguai.sesameag.task.antSesameCredit.AntSesameCredit
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.task.customTasks.CustomTask
import io.github.aoguai.sesameag.task.customTasks.ManualTask
import io.github.aoguai.sesameag.task.customTasks.ManualTaskModel
import io.github.aoguai.sesameag.util.GlobalThreadPools.execute
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import io.github.aoguai.sesameag.util.maps.UserMap

internal object ApplicationBroadcastDispatcher {
    private const val TAG = ApplicationHook.TAG

    fun handleReceive(context: Context?, intent: Intent?) {
        val safeIntent = intent ?: return
        val action = safeIntent.action ?: return

        val finalProcessName = ApplicationHook.finalProcessName
        if (finalProcessName != null && finalProcessName.endsWith(":widgetProvider")) {
            return
        }

        when (action) {
            ApplicationHookConstants.BroadcastActions.RESTART -> handleRestartBroadcast(safeIntent)
            ApplicationHookConstants.BroadcastActions.EXECUTE -> handleExecuteBroadcast(context, safeIntent)
            ApplicationHookConstants.BroadcastActions.PRE_WAKEUP -> handlePreWakeupBroadcast(context, safeIntent)
            ApplicationHookConstants.BroadcastActions.RE_LOGIN -> ApplicationHook.reOpenApp()
            ApplicationHookConstants.BroadcastActions.RPC_TEST -> handleRpcTest(safeIntent)
            ApplicationHookConstants.BroadcastActions.MANUAL_TASK -> handleManualTaskBroadcast(safeIntent)
            ApplicationHookConstants.BroadcastActions.HOOK_READY -> handleHookReadyBroadcast(context, safeIntent)
            ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT -> handlePermissionSnapshotBroadcast(context, safeIntent)
            ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS -> handleRefreshFriendsBroadcast(context, safeIntent)
            ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS -> handleRefreshExchangeOptionsBroadcast(context, safeIntent)
        }
    }

    private fun handleRestartBroadcast(intent: Intent) {
        val safeIntent = Intent(intent)
        ApplicationHookConstants.submitEntry("broadcast_restart") {
            val targetUserId = safeIntent.getStringExtra("userId")
            val currentUserId = HookUtil.getUserId(ApplicationHook.classLoader!!)
            if (targetUserId != null && targetUserId != currentUserId) {
                record(TAG, "忽略非当前用户的重启广播: target=$targetUserId, current=$currentUserId")
                return@submitEntry
            }
            val restartReason =
                if (safeIntent.getBooleanExtra("configReload", false)) {
                    "config_reload"
                } else {
                    "broadcast_restart"
                }
            ApplicationHook.restartWorkflow(restartReason)
        }
    }

    private fun handleExecuteBroadcast(context: Context?, intent: Intent) {
        val safeIntent = Intent(intent)
        val appContext = context?.applicationContext ?: context ?: ApplicationHook.appContext
        val isAlarmTriggered = safeIntent.getBooleanExtra("alarm_triggered", false)
        val wakenAtTime = safeIntent.getBooleanExtra("waken_at_time", false)
        val wakenTime = safeIntent.getStringExtra("waken_time")?.trim().takeIf { !it.isNullOrBlank() }
        val normalizedWakenTime = if (wakenAtTime && wakenTime.isNullOrBlank()) "0000" else wakenTime
        val persistentScheduleId = safeIntent.getStringExtra(ScheduledTaskRouter.EXTRA_PERSISTENT_ID)
            ?.trim()
            .orEmpty()
        val persistentSchedule = persistentScheduleId.takeIf { it.isNotBlank() }
            ?.let { PersistentScheduleRegistry.get(it) }
        if (persistentScheduleId.isNotBlank() && persistentSchedule == null) {
            record(TAG, "忽略不存在的持久执行广播: $persistentScheduleId")
            return
        }
        val currentSession = AccountSessionCoordinator.currentSession()
        if (persistentSchedule != null && currentSession == null) {
            record(TAG, "持久执行广播收到但当前会话未就绪，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && !AccountSessionCoordinator.isScheduleRoutable(persistentSchedule)) {
            PersistentScheduleRegistry.markFired(appContext, persistentSchedule.id)
            record(TAG, "持久执行广播会话不匹配，已丢弃调度: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && persistentSchedule.state != PersistentScheduleState.SCHEDULED) {
            record(TAG, "忽略已处理的持久执行广播: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && !ApplicationHook.isReadyForExec()) {
            record(TAG, "持久执行广播收到但工作流未就绪，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && ApplicationHookConstants.isOffline()) {
            record(TAG, "持久执行广播收到但当前离线，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule?.kind == PersistentScheduleKind.MODULE_CHILD) {
            val ctx = context?.applicationContext ?: context ?: ApplicationHook.appContext
            if (ctx == null || !ScheduledTaskRouter.fire(ctx, persistentSchedule, "target_broadcast_execute")) {
                record(TAG, "模块持久子任务路由失败: ${persistentSchedule.name}")
            }
            return
        }
        val persistentKind = persistentSchedule?.kind
            ?: safeIntent.getStringExtra(ScheduledTaskRouter.EXTRA_PERSISTENT_KIND).orEmpty()
        val triggerType = when (persistentKind) {
            PersistentScheduleKind.GLOBAL_POLL -> ApplicationHookConstants.TriggerType.ALARM_POLL
            PersistentScheduleKind.GLOBAL_WAKEUP -> ApplicationHookConstants.TriggerType.ALARM_WAKEUP
            else -> ApplicationHookConstants.TriggerType.BROADCAST_EXECUTE
        }
        val triggerPriority = when (triggerType) {
            ApplicationHookConstants.TriggerType.ALARM_POLL -> ApplicationHookConstants.TriggerPriority.LOW
            ApplicationHookConstants.TriggerType.BROADCAST_EXECUTE -> if (isAlarmTriggered || wakenAtTime) {
                ApplicationHookConstants.TriggerPriority.HIGH
            } else {
                ApplicationHookConstants.TriggerPriority.NORMAL
            }
            else -> ApplicationHookConstants.TriggerPriority.HIGH
        }
        val triggerDedupeKey = persistentSchedule?.dedupeKey?.takeIf { it.isNotBlank() } ?: when {
            wakenAtTime && !normalizedWakenTime.isNullOrBlank() -> "wakeup_$normalizedWakenTime"
            persistentKind == PersistentScheduleKind.GLOBAL_POLL -> "alarm_poll"
            isAlarmTriggered -> "alarm_execute"
            else -> "broadcast_execute"
        }

        val trigger = ApplicationHookConstants.TriggerInfo(
            type = triggerType,
            priority = triggerPriority,
            alarmTriggered = isAlarmTriggered,
            wakenAtTime = wakenAtTime,
            wakenTime = normalizedWakenTime,
            reason = "broadcast_execute",
            dedupeKey = triggerDedupeKey,
            persistentScheduleId = persistentScheduleId.takeIf { it.isNotBlank() },
            ownerUserId = persistentSchedule?.ownerUserId,
            sessionEpoch = persistentSchedule?.sessionEpoch ?: 0L
        )

        ApplicationHookConstants.submitEntry("broadcast_execute") {
            if (!ApplicationHook.isReadyForExec()) {
                record(TAG, "execute broadcast received but not ready: ${ApplicationHook.readinessSummary()}")
            }
            if (wakenAtTime) {
                if (normalizedWakenTime == "0000") {
                    ApplicationHook.updateDay()
                }
                ApplicationHook.setWakenAtTimeAlarm()
            }
            ApplicationHookCore.requestExecution(trigger)
        }
    }

    private fun handlePreWakeupBroadcast(context: Context?, intent: Intent) {
        val ctx = context?.applicationContext ?: context ?: return
        val safeIntent = Intent(intent)
        val executionTimeMillis = safeIntent.getLongExtra("execution_time", 0L)
        val forceExecute = safeIntent.getBooleanExtra("force_execute", false)
        val persistentScheduleId = safeIntent.getStringExtra(ScheduledTaskRouter.EXTRA_PERSISTENT_ID)
            ?.trim()
            .orEmpty()
        val persistentSchedule = persistentScheduleId.takeIf { it.isNotBlank() }
            ?.let { PersistentScheduleRegistry.get(it) }
        if (persistentScheduleId.isNotBlank() && persistentSchedule == null) {
            record(TAG, "忽略不存在的持久预唤醒广播: $persistentScheduleId")
            return
        }
        val currentSession = AccountSessionCoordinator.currentSession()
        if (persistentSchedule != null && currentSession == null) {
            record(TAG, "持久预唤醒广播收到但当前会话未就绪，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && !AccountSessionCoordinator.isScheduleRoutable(persistentSchedule)) {
            PersistentScheduleRegistry.markFired(ctx, persistentSchedule.id)
            record(TAG, "持久预唤醒广播会话不匹配，已丢弃调度: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && persistentSchedule.state != PersistentScheduleState.SCHEDULED) {
            record(TAG, "忽略已处理的持久预唤醒广播: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && !ApplicationHook.isReadyForExec()) {
            record(TAG, "持久预唤醒广播收到但工作流未就绪，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        if (persistentSchedule != null && ApplicationHookConstants.isOffline()) {
            record(TAG, "持久预唤醒广播收到但当前离线，保留调度等待恢复: ${persistentSchedule.name}")
            return
        }
        val now = System.currentTimeMillis()
        val execTime = if (executionTimeMillis > 0) executionTimeMillis else now
        val triggerAtExecTime = ApplicationHookConstants.TriggerInfo(
            type = ApplicationHookConstants.TriggerType.BROADCAST_PREWAKEUP,
            priority = ApplicationHookConstants.TriggerPriority.HIGH,
            alarmTriggered = true,
            reason = if (executionTimeMillis > 0) "prewakeup_to_${TimeUtil.getCommonDate(executionTimeMillis)}" else "prewakeup",
            dedupeKey = if (executionTimeMillis > 0) "prewakeup_$executionTimeMillis" else "prewakeup",
            persistentScheduleId = persistentScheduleId.takeIf { it.isNotBlank() },
            ownerUserId = persistentSchedule?.ownerUserId,
            sessionEpoch = persistentSchedule?.sessionEpoch ?: 0L
        )

        UnifiedScheduler.initialize(ctx)
        if (execTime > now && !forceExecute) {
            val schedule = UnifiedScheduler.schedulePersistentTrigger(
                context = ctx,
                name = "prewakeup_execute",
                kind = PersistentScheduleKind.GLOBAL_PREWAKEUP,
                triggerAtMs = execTime,
                dedupeKey = "prewakeup_$execTime",
                payloadJson = """{"execution_time":$execTime,"launch_target":true}""",
                toleranceMs = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                ownerUserId = persistentSchedule?.ownerUserId ?: AccountSessionCoordinator.currentUserId(),
                sessionEpoch = persistentSchedule?.sessionEpoch ?: AccountSessionCoordinator.currentSessionEpoch()
            )
            if (schedule.lastError != null) {
                val delayMillis = execTime - now
                record(TAG, "持久预唤醒注册失败，回退进程内等待 ${TimeUtil.formatDuration(delayMillis)}")
                UnifiedScheduler.scheduleLongDelay(delayMillis, "prewakeup_execute") {
                    ApplicationHookCore.requestExecution(triggerAtExecTime)
                }
                return
            }
            if (persistentScheduleId.isNotBlank()) {
                PersistentScheduleRegistry.markFired(context ?: ApplicationHook.appContext, persistentScheduleId)
            }
            UnifiedScheduler.cancelNamedTask("prewakeup_execute")
            record(TAG, "收到 prewakeup，已注册持久预唤醒任务 ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
            return
        }

        record(TAG, "收到 prewakeup，但 execution_time 无效/已过期，立即触发一次执行链路")
        ApplicationHookCore.requestExecution(triggerAtExecTime)
    }

    private fun handleHookReadyBroadcast(context: Context?, intent: Intent) {
        val ctx = context?.applicationContext ?: context ?: ApplicationHook.appContext
        val targetUserId = intent.getStringExtra("userId")?.trim().orEmpty()
        val loader = ApplicationHook.classLoader
        if (loader == null) {
            sendHookReadyResult(
                ctx,
                targetUserId,
                ready = false,
                message = "目标应用 Hook 尚未就绪"
            )
            return
        }

        val currentUserId = HookUtil.getUserId(loader)?.trim().orEmpty()
        when {
            currentUserId.isEmpty() -> sendHookReadyResult(
                ctx,
                targetUserId,
                ready = false,
                message = "当前支付宝账号未登录"
            )

            targetUserId.isNotEmpty() && targetUserId != currentUserId -> sendHookReadyResult(
                ctx,
                targetUserId,
                ready = false,
                message = "当前支付宝账号与好友中心账号不一致: target=$targetUserId, current=$currentUserId",
                currentUserId = currentUserId
            )

            ApplicationHookConstants.shouldBlockRpc() -> sendHookReadyResult(
                ctx,
                targetUserId.ifBlank { currentUserId },
                ready = false,
                message = "目标应用 RPC 当前处于离线拦截状态",
                currentUserId = currentUserId
            )

            ApplicationHook.rpcBridge == null -> sendHookReadyResult(
                ctx,
                targetUserId.ifBlank { currentUserId },
                ready = false,
                message = "目标应用 RpcBridge 尚未就绪",
                currentUserId = currentUserId
            )

            else -> sendHookReadyResult(
                ctx,
                targetUserId.ifBlank { currentUserId },
                ready = true,
                message = "目标应用已就绪",
                currentUserId = currentUserId
            )
        }
    }

    private fun sendHookReadyResult(
        context: Context?,
        userId: String,
        ready: Boolean,
        message: String,
        currentUserId: String = ""
    ) {
        val ctx = context ?: ApplicationHook.appContext ?: return
        ctx.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.HOOK_READY_RESULT).apply {
            putExtra("userId", userId)
            putExtra("ready", ready)
            putExtra("message", message)
            putExtra("currentUserId", currentUserId)
            putExtra("timestamp", System.currentTimeMillis())
        })
    }

    private fun handlePermissionSnapshotBroadcast(context: Context?, intent: Intent) {
        val ctx = context?.applicationContext ?: context ?: ApplicationHook.appContext ?: return
        val requestToken = intent.getLongExtra("requestToken", 0L)
        val permissions = ModuleStatusReporter
            .getStatusSnapshot(refresh = true, reason = "permission_snapshot")
            .get("permissions") as? Map<*, *>

        ctx.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT_RESULT).apply {
            setPackage(General.MODULE_PACKAGE_NAME)
            if (requestToken != 0L) {
                putExtra("requestToken", requestToken)
            }
            putExtra("available", permissions?.get("available") as? Boolean ?: false)
            putExtra("contextPackage", permissions?.get("contextPackage") as? String ?: "")
            putExtra("targetBatteryIgnored", permissions?.get("targetBatteryIgnored") as? Boolean ?: false)
            (permissions?.get("targetExactAlarmAllowed") as? Boolean)?.let { putExtra("targetExactAlarmAllowed", it) }
            putExtra("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleRefreshFriendsBroadcast(context: Context?, intent: Intent) {
        val ctx = context?.applicationContext ?: context ?: ApplicationHook.appContext
        val safeIntent = Intent(intent)
        ApplicationHookConstants.submitEntry("refresh_friends") {
            val targetUserId = safeIntent.getStringExtra("userId")?.trim().orEmpty()
            val force = safeIntent.getBooleanExtra("manual", true)
            val loader = ApplicationHook.classLoader
            if (loader == null) {
                sendRefreshFriendsResult(
                    ctx,
                    targetUserId,
                    success = false,
                    message = "刷新好友失败：Hook classLoader 不可用"
                )
                return@submitEntry
            }

            val currentUserId = HookUtil.getUserId(loader)?.trim().orEmpty()
            if (currentUserId.isEmpty()) {
                sendRefreshFriendsResult(
                    ctx,
                    targetUserId,
                    success = false,
                    message = "刷新好友失败：当前支付宝账号未登录"
                )
                return@submitEntry
            }
            if (targetUserId.isNotEmpty() && targetUserId != currentUserId) {
                sendRefreshFriendsResult(
                    ctx,
                    targetUserId,
                    success = false,
                    message = "忽略非当前账号的好友刷新请求: target=$targetUserId, current=$currentUserId"
                )
                return@submitEntry
            }

            val result = ApplicationHook.refreshFriendsFromAlipayIfNeeded(
                userId = currentUserId,
                force = force,
                source = if (force) "manual_refresh" else "broadcast_refresh"
            )
            sendRefreshFriendsResult(
                ctx,
                result.userId.ifBlank { currentUserId },
                success = result.success,
                message = result.message,
                profiles = result.profiles,
                groups = result.groups
            )
        }
    }

    private fun sendRefreshFriendsResult(
        context: Context?,
        userId: String,
        success: Boolean,
        message: String,
        profiles: Int = 0,
        groups: Int = 0
    ) {
        val ctx = context ?: ApplicationHook.appContext ?: return
        ctx.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS_RESULT).apply {
            putExtra("userId", userId)
            putExtra("success", success)
            putExtra("message", message)
            putExtra("profiles", profiles)
            putExtra("groups", groups)
            putExtra("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleRefreshExchangeOptionsBroadcast(context: Context?, intent: Intent) {
        val ctx = context?.applicationContext ?: context ?: ApplicationHook.appContext
        val safeIntent = Intent(intent)
        execute {
            val requestId = safeIntent.getStringExtra("requestId").orEmpty()
            val target = safeIntent.getStringExtra("target").orEmpty()
            val targetUserId = safeIntent.getStringExtra("userId")?.trim().orEmpty()
            val result = refreshExchangeOptionsInTarget(target, targetUserId)
            sendRefreshExchangeOptionsResult(
                ctx,
                requestId = requestId,
                target = target,
                userId = result.userId.ifBlank { targetUserId },
                success = result.success,
                message = result.message,
                options = result.options
            )
        }
    }

    private data class ExchangeOptionsRefreshResult(
        val success: Boolean,
        val message: String,
        val userId: String = "",
        val options: List<MapperEntity> = emptyList()
    )

    private fun refreshExchangeOptionsInTarget(target: String, targetUserId: String): ExchangeOptionsRefreshResult {
        val loader = ApplicationHook.classLoader
            ?: return ExchangeOptionsRefreshResult(false, "目标应用 Hook 尚未就绪")
        val currentUserId = HookUtil.getUserId(loader)?.trim().orEmpty()
        if (currentUserId.isEmpty()) {
            return ExchangeOptionsRefreshResult(false, "当前支付宝账号未登录")
        }
        if (targetUserId.isNotEmpty() && targetUserId != currentUserId) {
            return ExchangeOptionsRefreshResult(
                false,
                "当前支付宝账号与配置账号不一致: target=$targetUserId, current=$currentUserId",
                currentUserId
            )
        }
        if (ApplicationHookConstants.shouldBlockRpc()) {
            return ExchangeOptionsRefreshResult(false, "目标应用 RPC 当前处于离线拦截状态", currentUserId)
        }
        if (ApplicationHook.rpcBridge == null) {
            return ExchangeOptionsRefreshResult(false, "目标应用 RpcBridge 尚未就绪", currentUserId)
        }

        return try {
            UserMap.setCurrentUserId(currentUserId)
            val options = when (target) {
                ExchangeOptionsRefreshBridge.TARGET_MEMBER_POINT -> {
                    Model.getModel(AntMember::class.java)?.refreshMemberPointExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "会员模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_BEAN_RIGHT -> {
                    Model.getModel(AntMember::class.java)?.refreshBeanExchangeRightOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "会员模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_FARM_PARADISE -> {
                    Model.getModel(AntFarm::class.java)?.refreshParadiseCoinExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "庄园模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE -> {
                    Model.getModel(AntFarm::class.java)?.refreshIpChouChouLeExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "庄园模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_SPORTS_ENERGY -> {
                    Model.getModel(AntSports::class.java)?.refreshSportsEnergyExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "运动模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY -> {
                    Model.getModel(AntForest::class.java)?.refreshVitalityExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "森林模块未初始化", currentUserId)
                }

                ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN -> {
                    Model.getModel(AntSesameCredit::class.java)?.refreshSesameGrainExchangeOptionsForRemote()
                        ?: return ExchangeOptionsRefreshResult(false, "芝麻信用模块未初始化", currentUserId)
                }

                else -> return ExchangeOptionsRefreshResult(false, "未知兑换列表刷新目标: $target", currentUserId)
            }
            ExchangeOptionsRefreshResult(true, "刷新完成: $target#${options.size}", currentUserId, options)
        } catch (t: Throwable) {
            io.github.aoguai.sesameag.util.Log.printStackTrace(TAG, "refreshExchangeOptionsInTarget err:", t)
            ExchangeOptionsRefreshResult(false, "刷新失败: ${t.message ?: t.javaClass.simpleName}", currentUserId)
        }
    }

    private fun sendRefreshExchangeOptionsResult(
        context: Context?,
        requestId: String,
        target: String,
        userId: String,
        success: Boolean,
        message: String,
        options: List<MapperEntity>
    ) {
        val ctx = context ?: ApplicationHook.appContext ?: return
        ctx.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS_RESULT).apply {
            setPackage(General.MODULE_PACKAGE_NAME)
            putExtra("requestId", requestId)
            putExtra("target", target)
            putExtra("userId", userId)
            putExtra("success", success)
            putExtra("message", message)
            putExtra("optionCount", options.size)
            putExtra("optionsJson", JsonUtil.formatJson(options, false))
            putExtra("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleManualTaskBroadcast(intent: Intent) {
        record(TAG, "🚀 收到手动庄园任务指令")
        execute {
            val taskName = intent.getStringExtra("task")
            if (taskName != null) {
                val normalizedTaskName = taskName.replace("+", "_")
                try {
                    val task = CustomTask.valueOf(normalizedTaskName)
                    val extraParams = HashMap<String, Any>()
                    when (task) {
                        CustomTask.FOREST_WHACK_MOLE -> Unit

                        CustomTask.FOREST_ENERGY_RAIN -> {
                            extraParams["exchangeEnergyRainCard"] = intent.getBooleanExtra("exchangeEnergyRainCard", false)
                        }

                        CustomTask.FARM_SPECIAL_FOOD -> {
                            extraParams["specialFoodCount"] = intent.getIntExtra("specialFoodCount", 0)
                        }

                        CustomTask.FARM_USE_TOOL -> {
                            extraParams["toolType"] = intent.getStringExtra("toolType") ?: ""
                            extraParams["toolCount"] = intent.getIntExtra("toolCount", 1)
                        }

                        else -> {
                            record(TAG, "❌ 无效的任务指令: $taskName")
                        }
                    }
                    if (!WorkflowRootGuard.hasRoot(forceRefresh = true, reason = "manual_task")) {
                        record(TAG, "⛔ 未检测到可用执行权限，忽略手动任务指令: $taskName")
                        return@execute
                    }
                    if (!ApplicationHook.ensureLegalAcceptedForWorkflow()) {
                        record(TAG, "⛔ 未勾选 LEGAL 说明确认，忽略手动任务指令: $taskName")
                        return@execute
                    }
                    ManualTask.runSingle(task, extraParams)
                } catch (e: Exception) {
                    record(TAG, "❌ 无效的任务指令: $taskName -> ${e.message}")
                }
            } else {
                if (!WorkflowRootGuard.hasRoot(forceRefresh = true, reason = "manual_task_model")) {
                    record(TAG, "⛔ 未检测到可用执行权限，忽略手动模型任务指令")
                    return@execute
                }
                if (!ApplicationHook.ensureLegalAcceptedForWorkflow()) {
                    record(TAG, "⛔ 未勾选 LEGAL 说明确认，忽略手动模型任务指令")
                    return@execute
                }
                for (model in Model.modelArray) {
                    if (model is ManualTaskModel) {
                        model.startTask(true, 1)
                        break
                    }
                }
            }
        }
    }

    private fun handleRpcTest(intent: Intent) {
        execute {
            record(TAG, "RPC测试: $intent")
            try {
                val rpc = io.github.aoguai.sesameag.hook.rpc.debug.DebugRpc()
                rpc.start(
                    intent.getStringExtra("method") ?: "",
                    intent.getStringExtra("data") ?: "",
                    intent.getStringExtra("type") ?: ""
                )
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
