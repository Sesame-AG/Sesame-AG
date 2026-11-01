package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.TodayFlagClearResult
import io.github.aoguai.sesameag.data.TodayFlagKey
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.entity.friend.FriendSelectionCountSpec
import io.github.aoguai.sesameag.entity.friend.FriendSelectionSpec
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.EmptyModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeFieldMeta
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.task.customTasks.ManualTaskModel
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.ui.dto.ModelFieldShowDto
import io.github.aoguai.sesameag.ui.dto.ModelFieldTodayStateResolver
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.LocaleSettingsApplier
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.PortUtil
import io.github.aoguai.sesameag.util.SettingsFieldAudit
import io.github.aoguai.sesameag.util.SettingsFieldAuditRegistry
import io.github.aoguai.sesameag.util.TimeTriggerParseOptions
import io.github.aoguai.sesameag.util.TimeTriggerParser
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.BeachMap
import io.github.aoguai.sesameag.util.maps.BeanExchangeRightMap
import io.github.aoguai.sesameag.util.maps.CooperateMap
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.MemberBenefitsMap
import io.github.aoguai.sesameag.util.maps.ParadiseCoinBenefitIdMap
import io.github.aoguai.sesameag.util.maps.ReserveaMap
import io.github.aoguai.sesameag.util.maps.SesameGiftMap
import io.github.aoguai.sesameag.util.maps.SportsEnergyExchangeMap
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.VitalityRewardsMap
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferImporter
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FieldKey(val modelCode: String, val fieldCode: String)

data class FieldOptionUiModel(val id: String, val name: String)

sealed interface FieldOptionsState {
    data object NotRequested : FieldOptionsState
    data object Loading : FieldOptionsState
    data class Ready(
        val options: List<FieldOptionUiModel>,
        val isRefreshing: Boolean = false,
        val refreshError: String? = null,
    ) : FieldOptionsState
    data class Error(val message: String) : FieldOptionsState
}

data class FieldEditorUiModel(
    val key: FieldKey,
    val modelName: String,
    val name: String,
    val type: String,
    val desc: String,
    val expandKey: List<String> = emptyList(),
    val editorMeta: Any? = null,
    val todayInactive: Boolean = false,
    val todayInactiveReason: String = "",
    val todayClearableFlagKeys: List<TodayFlagKey> = emptyList(),
    val hasAction: Boolean = false,
    val audit: SettingsFieldAudit? = null,
)

data class AccountModelUiModel(
    val code: String,
    val name: String,
    val groupCode: String,
    val fields: List<FieldEditorUiModel>,
)

data class AccountGroupUiModel(
    val code: String,
    val name: String,
    val models: List<AccountModelUiModel>,
)

data class AccountSettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loadError: String? = null,
    val operationMessage: String? = null,
    val groups: List<AccountGroupUiModel> = emptyList(),
    val drafts: Map<FieldKey, String> = emptyMap(),
    val savedValues: Map<FieldKey, String> = emptyMap(),
    val fieldOptions: Map<FieldKey, FieldOptionsState> = emptyMap(),
    val pendingAuditClearKeys: Set<FieldKey> = emptySet(),
) {
    val isDirty: Boolean
        get() = pendingAuditClearKeys.isNotEmpty() || drafts.any { (key, value) -> savedValues[key] != value }

    val unsupportedFields: List<FieldEditorUiModel>
        get() = groups.flatMap { it.models }.flatMap { it.fields }.filter { it.type !in SUPPORTED_FIELD_TYPES }
}

class AccountSettingsViewModel(
    application: Application,
    val userId: String,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    private val fields = linkedMapOf<FieldKey, ModelField<*>>()
    private val modelFieldsByCode = linkedMapOf<String, ModelFields>()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AccountSettingsUiState(isLoading = true)
            runCatching { loadAccount() }
                .onSuccess { loaded -> _uiState.value = loaded }
                .onFailure { throwable ->
                    Log.printStackTrace(TAG, "Account settings load failed", throwable)
                    _uiState.value = AccountSettingsUiState(
                        isLoading = false,
                        loadError = throwable.message ?: throwable.javaClass.simpleName,
                    )
                }
        }
    }

    fun updateDraft(key: FieldKey, value: String) {
        if (_uiState.value.isSaving) return
        _uiState.update { state ->
            val resolvesAudit = state.findField(key)?.audit != null
            state.copy(
                drafts = state.drafts + (key to value),
                pendingAuditClearKeys = if (resolvesAudit) {
                    state.pendingAuditClearKeys + key
                } else {
                    state.pendingAuditClearKeys
                },
                operationMessage = null,
            )
        }
        persistDraft(key, value, _uiState.value)
    }

    fun clearFieldAudit(key: FieldKey) {
        val audit = _uiState.value.findField(key)?.audit ?: return
        updateDraft(key, audit.clearValue)
    }

    fun discardDrafts() {
        _uiState.update { state ->
            state.copy(
                drafts = state.savedValues,
                pendingAuditClearKeys = emptySet(),
                operationMessage = null,
            )
        }
        clearSavedDrafts()
    }

    fun clearMessage() {
        _uiState.update { it.copy(operationMessage = null) }
    }

    fun exchangeTargetFor(key: FieldKey): String? = when (key) {
        FieldKey("AntMember", "memberPointExchangeBenefitList") -> ExchangeOptionsRefreshBridge.TARGET_MEMBER_POINT
        FieldKey("AntMember", "billCoinExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_BILL_COIN
        FieldKey("AntMember", "beanExchangeRightList") -> ExchangeOptionsRefreshBridge.TARGET_BEAN_RIGHT
        FieldKey("MyBankWelfare", "myBankWelfareExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE
        FieldKey("AntFarm", "paradiseCoinExchangeBenefitList") -> ExchangeOptionsRefreshBridge.TARGET_FARM_PARADISE
        FieldKey("AntFarm", "autoExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE
        FieldKey("AntSports", "sportsEnergyExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_SPORTS_ENERGY
        FieldKey("AntForest", "vitalityExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY
        FieldKey("AntSesameCredit", "sesameGrainExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN
        FieldKey("GoldenBeanTreasure", "goldenBeanMallExchangeList") -> ExchangeOptionsRefreshBridge.TARGET_GOLDEN_BEAN_MALL
        FieldKey("Reserve", "reserveList"),
        FieldKey("Reserve", "certificateTargets") -> ExchangeOptionsRefreshBridge.TARGET_RESERVE
        else -> null
    }

    fun loadFieldOptions(key: FieldKey, forceRefresh: Boolean = false) {
        val target = exchangeTargetFor(key)
        val current = _uiState.value.fieldOptions[key]
        if (current is FieldOptionsState.Loading ||
            current is FieldOptionsState.Ready && (current.isRefreshing || target == null)
        ) return
        if (forceRefresh && target == null) return
        _uiState.update { state ->
            state.copy(fieldOptions = state.fieldOptions + (key to (
                (current as? FieldOptionsState.Ready)?.copy(isRefreshing = true, refreshError = null)
                    ?: FieldOptionsState.Loading
            )))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (target != null) {
                    val cached = if (forceRefresh) null else ExchangeOptionsCache.loadTodaySnapshot(userId, target)
                    val rows = cached?.rows ?: run {
                        check(HookReadyChecker.isTargetAppReadyForRpc(userId)) { "请打开目标应用并登录当前配置账号后再刷新" }
                        val refreshed = ExchangeOptionsRefreshBridge.requestRefreshOptions(target, userId, forceRefresh = forceRefresh)
                        check(refreshed.success) { refreshed.message.ifBlank { "兑换列表刷新失败" } }
                        check(refreshed.userId == userId) { "刷新结果账号与当前配置账号不一致" }
                        if (forceRefresh) {
                            val validIds = refreshed.options.mapTo(HashSet()) { it.id }
                            val keys = fields.keys.filter { exchangeTargetFor(it) == target }
                            val savedValues = Config.withLoadedAccount(userId) {
                                val originals = keys.associateWith { fields.getValue(it).getConfigValue().orEmpty() }
                                try {
                                    var changed = false
                                    for ((fieldKey, value) in originals) {
                                        val node = JsonUtil.toNode(value.ifBlank { "{}" }) as? ObjectNode
                                            ?: error("兑换选择格式无效：${fieldKey.fieldCode}")
                                        val removed = node.fieldNames().asSequence().filterNot { it in validIds }.toList()
                                        if (removed.isNotEmpty()) {
                                            fields.getValue(fieldKey).setConfigValue(node.remove(removed).toString())
                                            changed = true
                                        }
                                    }
                                    if (changed) check(Config.save(userId, true)) { "清理失效兑换选择后保存失败" }
                                    keys.associateWith { fields.getValue(it).getConfigValue().orEmpty() }
                                } catch (error: Throwable) {
                                    originals.forEach { (fieldKey, value) -> fields.getValue(fieldKey).setConfigValue(value) }
                                    throw error
                                }
                            }
                            _uiState.update { state ->
                                val drafts = state.drafts.mapValues { (fieldKey, value) ->
                                    if (fieldKey !in keys) return@mapValues value
                                    val node = JsonUtil.toNode(value.ifBlank { "{}" }) as? ObjectNode
                                        ?: return@mapValues value
                                    val removed = node.fieldNames().asSequence().filterNot { it in validIds }.toList()
                                    if (removed.isEmpty()) value else node.remove(removed).toString()
                                }
                                state.copy(savedValues = state.savedValues + savedValues, drafts = drafts)
                            }
                            for (fieldKey in keys) {
                                val state = _uiState.value
                                state.drafts[fieldKey]?.let { persistDraft(fieldKey, it, state) }
                            }
                        }
                        refreshed.options
                    }
                    rows.map { FieldOptionUiModel(it.id, it.name.ifBlank { it.id }) }
                } else {
                    loadOptionMaps()
                    val field = fields[key] ?: error("字段不存在：${key.fieldCode}")
                    when (field.getType()) {
                        "CHOICE" -> field.getExpandKey()
                            .asStringList()
                            .mapIndexed { index, name -> FieldOptionUiModel(index.toString(), name) }
                        else -> field.getExpandValue()
                            .asMapperList()
                            .map { FieldOptionUiModel(it.id, it.name.ifBlank { it.id }) }
                    }
                }
            }
            val optionsState = result.fold(
                onSuccess = { FieldOptionsState.Ready(it) },
                onFailure = {
                    val message = it.message ?: "选项加载失败"
                    val previous = (current as? FieldOptionsState.Ready)?.options
                        ?: target?.let { exchangeTarget ->
                            ExchangeOptionsCache.loadForSettingsCache(userId, exchangeTarget)
                                .map { row -> FieldOptionUiModel(row.id, row.name.ifBlank { row.id }) }
                        }
                    if (previous != null && (previous.isNotEmpty() || current is FieldOptionsState.Ready)) {
                        FieldOptionsState.Ready(previous, refreshError = message)
                    } else {
                        FieldOptionsState.Error(message)
                    }
                },
            )
            _uiState.update { state ->
                state.copy(fieldOptions = state.fieldOptions + (key to optionsState))
            }
        }
    }

    suspend fun save(context: Context): Result<Unit> {
        val snapshot = _uiState.value
        if (snapshot.unsupportedFields.isNotEmpty()) {
            return Result.failure(IllegalStateException("存在当前版本缺少编辑器的字段，无法保存"))
        }
        _uiState.update { it.copy(isSaving = true, operationMessage = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                Config.withLoadedAccount(userId) {
                    validateDrafts(snapshot.drafts)
                    applyDrafts(snapshot.drafts)
                    Config.sanitizeFriendSelectionFieldsForUser(userId)
                    check(Config.save(userId, true)) { "配置文件保存失败" }
                }
            }
            LocaleSettingsApplier.apply(context)
            runCatching {
                context.sendBroadcast(
                    Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                        putExtra("userId", userId)
                        putExtra("configReload", true)
                    }
                )
            }.onFailure { throwable ->
                Log.printStackTrace(TAG, "Failed to send config reload broadcast", throwable)
            }
            withContext(Dispatchers.IO) {
                IdMapManager.getInstance(CooperateMap::class.java).save(userId)
                currentConfigValues()
            }
        }
        _uiState.update { state ->
            if (result.isSuccess) {
                val normalizedValues = result.getOrThrow()
                state.copy(
                    isSaving = false,
                    groups = state.groups.clearAudits(state.pendingAuditClearKeys),
                    savedValues = normalizedValues,
                    drafts = normalizedValues,
                    pendingAuditClearKeys = emptySet(),
                    operationMessage = "保存成功",
                )
            } else {
                state.copy(
                    isSaving = false,
                    operationMessage = result.exceptionOrNull()?.message ?: "保存失败",
                )
            }
        }
        if (result.isSuccess) clearSavedDrafts()
        return result.map { Unit }
    }

    suspend fun export(context: Context, uri: Uri?, mode: SettingsTransferExportMode): Result<Unit> {
        if (_uiState.value.isDirty) {
            return Result.failure(IllegalStateException("请先保存当前草稿再导出"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                check(PortUtil.handleExport(context, uri, userId, mode)) { "导出失败" }
            }
        }
    }

    suspend fun import(context: Context, uri: Uri?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = PortUtil.readImportText(context, uri) ?: error("无法读取导入文件")
            val resolved = Config.withLoadedAccount(userId) { SettingsTransferImporter.resolve(json, userId) }
            val applied = PortUtil.applyImport(context, userId, resolved)
            check(applied.success) { applied.message }
            clearSavedDrafts()
            val loaded = runCatching { loadAccount() }.getOrElse {
                throw IllegalStateException("设置已保存，但页面加载失败：${it.message}", it)
            }
            loaded.copy(operationMessage = applied.message)
        }.onSuccess { loaded ->
            _uiState.value = loaded
        }
    }.map { Unit }

    suspend fun deleteConfig(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(Files.delFile(Files.getUserConfigDir(userId))) { "配置删除失败" }
            clearSavedDrafts()
        }
    }

    suspend fun runFieldAction(key: FieldKey): Result<String> {
        if (_uiState.value.isDirty) {
            return Result.failure(IllegalStateException("请先保存当前草稿再执行字段操作"))
        }
        if (isAnswerAiTestAction(key)) {
            return withContext(Dispatchers.IO) {
                runCatching {
                    val result = Model.getModel(AnswerAI::class.java)?.testAnswerService()
                        ?: error("AI答题模块未初始化")
                    check(result.success) { result.message }
                    result.message
                }
            }
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                val field = fields[key] as? EmptyModelField ?: error("字段不支持执行操作")
                check(field.hasAction()) { "该字段没有可执行操作" }
                field.runAction()
                "操作已执行"
            }
        }
    }

    fun currentField(key: FieldKey): ModelField<*>? = fields[key]

    suspend fun clearModuleTodayFlags(context: Context, modelCode: String): Result<String> =
        clearTodayFlags(context) {
            Status.clearModuleTodayFlagsForUser(userId, modelCode)
        }

    suspend fun clearFieldTodayFlags(context: Context, key: FieldKey): Result<String> =
        clearTodayFlags(context) {
            Status.clearFieldTodayFlagsForUser(userId, key.modelCode, key.fieldCode)
        }

    private suspend fun clearTodayFlags(
        context: Context,
        clear: () -> TodayFlagClearResult,
    ): Result<String> = withContext(Dispatchers.IO) {
        val result = clear()
        if (!result.isSuccess) {
            return@withContext Result.failure(IllegalStateException(result.errorMessage ?: "每日标识清除失败"))
        }
        if (!result.written) {
            return@withContext Result.success("没有可删除的每日标识")
        }
        runCatching {
            context.sendBroadcast(
                Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                    putExtra("userId", result.userId)
                },
            )
        }.onFailure { throwable ->
            Log.printStackTrace(TAG, "Failed to send status reload broadcast", throwable)
        }
        refreshTodayStates()
        Result.success("已删除 ${result.removedCount} 个每日标识")
    }

    private fun refreshTodayStates() {
        Status.load(userId, false)
        _uiState.update { state ->
            state.copy(
                groups = state.groups.map { group ->
                    group.copy(
                        models = group.models.map { model ->
                            val modelFields = modelFieldsByCode[model.code] ?: return@map model
                            model.copy(
                                fields = model.fields.map { field ->
                                    val sourceField = fields[field.key] ?: return@map field
                                    val todayState = ModelFieldTodayStateResolver.resolve(
                                        modelCode = model.code,
                                        modelFields = modelFields,
                                        modelField = sourceField,
                                    )
                                    field.copy(
                                        todayInactive = todayState.inactive,
                                        todayInactiveReason = todayState.reason,
                                        todayClearableFlagKeys = todayState.clearableFlagKeys.toList(),
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun loadAccount(): AccountSettingsUiState = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        loadAccountLocked()
    }

    private fun loadAccountLocked(): AccountSettingsUiState {
        val normalizedUserId = AccountSlotRegistry.normalizeUserId(userId)
        require(normalizedUserId != null && normalizedUserId in Files.listExistingUserConfigIds()) {
            "账号配置不存在或账号标识无效"
        }
        Model.initAllModel()
        UserMap.setCurrentUserId(normalizedUserId)
        val friends = UserMap.load(normalizedUserId)
        FriendRepository.mergeFromUserMap(normalizedUserId, currentUsers = friends)
        Status.load(normalizedUserId)
        Config.load(normalizedUserId)

        fields.clear()
        modelFieldsByCode.clear()
        val drafts = linkedMapOf<FieldKey, String>()
        val groups = ModelGroup.entries.mapNotNull { group ->
            val models = Model.getGroupModelConfig(group).values.mapNotNull { modelConfig ->
                if (modelConfig.code == ManualTaskModel::class.java.simpleName) return@mapNotNull null
                modelFieldsByCode[modelConfig.code] = modelConfig.fields
                val editorFields = modelConfig.fields.values.map { modelField ->
                    val key = FieldKey(modelConfig.code, modelField.code)
                    val dto = ModelFieldShowDto.toShowDto(modelConfig.code, modelConfig.fields, modelField)
                    fields[key] = modelField
                    drafts[key] = dto.configValue
                    FieldEditorUiModel(
                        key = key,
                        modelName = modelConfig.name,
                        name = dto.name,
                        type = dto.type,
                        desc = dto.desc,
                        expandKey = dto.expandKey.asStringList(),
                        editorMeta = dto.editorMeta,
                        todayInactive = dto.todayInactive,
                        todayInactiveReason = dto.todayInactiveReason,
                        todayClearableFlagKeys = dto.todayClearableFlagKeys,
                        hasAction = modelField is EmptyModelField && (
                            modelField.hasAction() || isAnswerAiTestAction(key)
                        ),
                        audit = SettingsFieldAuditRegistry.get(normalizedUserId, modelField.code),
                    )
                }
                AccountModelUiModel(
                    code = modelConfig.code,
                    name = modelConfig.name,
                    groupCode = group.code,
                    fields = editorFields,
                )
            }
            if (models.isEmpty()) null else AccountGroupUiModel(group.code, group.groupName, models)
        }
        val savedValues = drafts.toMap()
        val restoredDrafts = savedStateHandle.get<Map<String, String>>(SAVED_DRAFTS_KEY).orEmpty()
        val restoredAuditClears = savedStateHandle.get<List<String>>(SAVED_AUDIT_CLEARS_KEY).orEmpty().toSet()
        val mergedDrafts = drafts.mapValues { (key, value) -> restoredDrafts[key.savedStateKey()] ?: value }
        val pendingAuditClearKeys = fields.keys.filterTo(linkedSetOf()) {
            it.savedStateKey() in restoredAuditClears
        }
        return AccountSettingsUiState(
            isLoading = false,
            groups = groups,
            drafts = mergedDrafts,
            savedValues = savedValues,
            pendingAuditClearKeys = pendingAuditClearKeys,
        )
    }

    private fun applyDrafts(drafts: Map<FieldKey, String>) {
        Model.getModelConfigMap().values.forEach { model ->
            model.fields.values.forEach { field ->
                val key = FieldKey(model.code, field.code)
                val value = drafts[key] ?: return@forEach
                when (field.getType()) {
                    "TEXT", "READ_TEXT", "URL_TEXT", "EMPTY" -> Unit
                    else -> field.setConfigValue(value)
                }
            }
        }
    }

    private fun currentConfigValues(): Map<FieldKey, String> = fields.mapValues { (_, field) ->
        field.getConfigValue().orEmpty()
    }

    private fun validateDrafts(drafts: Map<FieldKey, String>) {
        for ((key, value) in drafts) {
            val field = fields[key] ?: continue
            when (field.getType()) {
                "BOOLEAN" -> require(value.equals("true", true) || value.equals("false", true)) {
                    "${field.name} 必须为开或关"
                }
                "CHOICE", "INTEGER", "MULTIPLY_INTEGER" -> require(value.trim().toIntOrNull() != null) {
                    "${field.name} 需要有效整数"
                }
                "SELECT" -> require(value.isBlank() || io.github.aoguai.sesameag.util.JsonUtil.toNode(value)?.isArray == true) {
                    "${field.name} 需要有效数组"
                }
                "SELECT_AND_COUNT" ->
                    require(value.isBlank() || io.github.aoguai.sesameag.util.JsonUtil.toNode(value)?.isObject == true) {
                        "${field.name} 需要有效对象"
                    }
                "FRIEND_SELECTION" -> require(
                    value.isBlank() || runCatching {
                        JsonUtil.parseObject(value, FriendSelectionSpec::class.java)
                    }.isSuccess
                ) {
                    "${field.name} 需要有效好友选择规则"
                }
                "FRIEND_SELECTION_COUNT" -> require(
                    value.isBlank() || runCatching {
                        JsonUtil.parseObject(value, FriendSelectionCountSpec::class.java)
                    }.isSuccess
                ) {
                    "${field.name} 需要有效好友计数规则"
                }
                "TIME_POINT", "TIME_POINT_LIST", "TIME_WINDOW_LIST", "TIME_TRIGGER", "HOUR_OF_DAY" ->
                    require(isValidTimeDraft(field.getType(), value, field.getEditorMeta() as? TimeFieldMeta)) {
                        "${field.name} 的时间规则无效"
                    }
                else -> Unit
            }
        }
    }

    private fun loadOptionMaps() {
        IdMapManager.getInstance(CooperateMap::class.java).load(userId)
        IdMapManager.getInstance(VitalityRewardsMap::class.java).load(userId)
        IdMapManager.getInstance(MemberBenefitsMap::class.java).load(userId)
        IdMapManager.getInstance(BeanExchangeRightMap::class.java).load(userId)
        IdMapManager.getInstance(SesameGiftMap::class.java).load(userId)
        IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java).load(userId)
        IdMapManager.getInstance(SportsEnergyExchangeMap::class.java).load(userId)
        IdMapManager.getInstance(ReserveaMap::class.java).load()
        IdMapManager.getInstance(BeachMap::class.java).load()
    }

    private fun persistDraft(key: FieldKey, value: String, state: AccountSettingsUiState) {
        val overrides = savedStateHandle.get<Map<String, String>>(SAVED_DRAFTS_KEY)
            .orEmpty()
            .toMutableMap()
        val savedStateKey = key.savedStateKey()
        if (state.savedValues[key] == value && key !in state.pendingAuditClearKeys) {
            overrides.remove(savedStateKey)
        } else {
            overrides[savedStateKey] = value
        }
        if (overrides.isEmpty()) {
            savedStateHandle.remove<Map<String, String>>(SAVED_DRAFTS_KEY)
        } else {
            savedStateHandle[SAVED_DRAFTS_KEY] = overrides
        }
        if (state.pendingAuditClearKeys.isEmpty()) {
            savedStateHandle.remove<List<String>>(SAVED_AUDIT_CLEARS_KEY)
        } else {
            savedStateHandle[SAVED_AUDIT_CLEARS_KEY] = state.pendingAuditClearKeys.map { it.savedStateKey() }
        }
    }

    private fun clearSavedDrafts() {
        savedStateHandle.remove<Map<String, String>>(SAVED_DRAFTS_KEY)
        savedStateHandle.remove<List<String>>(SAVED_AUDIT_CLEARS_KEY)
    }

    companion object {
        private const val TAG = "AccountSettings"
        private const val SAVED_DRAFTS_KEY = "account_settings_drafts"
        private const val SAVED_AUDIT_CLEARS_KEY = "account_settings_audit_clears"

        fun factory(application: Application, userId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AccountSettingsViewModel(
                    application = application,
                    userId = userId,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

private fun AccountSettingsUiState.findField(key: FieldKey): FieldEditorUiModel? =
    groups.asSequence()
        .flatMap { it.models.asSequence() }
        .flatMap { it.fields.asSequence() }
        .firstOrNull { it.key == key }

private fun List<AccountGroupUiModel>.clearAudits(keys: Set<FieldKey>): List<AccountGroupUiModel> {
    if (keys.isEmpty()) return this
    return map { group ->
        group.copy(
            models = group.models.map { model ->
                model.copy(
                    fields = model.fields.map { field ->
                        if (field.key in keys) field.copy(audit = null) else field
                    }
                )
            }
        )
    }
}

private fun isAnswerAiTestAction(key: FieldKey): Boolean =
    key.modelCode == AnswerAI::class.java.simpleName && key.fieldCode == AnswerAI.FIELD_AI_TEST

private fun FieldKey.savedStateKey(): String = "$modelCode\u0000$fieldCode"

private fun isValidTimeDraft(type: String, rawValue: String, meta: TimeFieldMeta?): Boolean {
    val value = rawValue.trim()
    if (value.isEmpty()) return false
    if (value == "-1") return meta?.allowDisable == true
    if (type == "HOUR_OF_DAY") {
        val digits = value.filter(Char::isDigit)
        val normalized = when (digits.length) {
            1, 2 -> digits.padStart(2, '0') + "00"
            3 -> "0$digits"
            4 -> digits
            else -> return false
        }
        val hour = normalized.take(2).toIntOrNull() ?: return false
        val minute = normalized.takeLast(2).toIntOrNull() ?: return false
        return (meta?.allowDayEnd == true && hour == 24 && minute == 0) ||
            (hour in 0..23 && minute == 0)
    }
    val options = TimeTriggerParseOptions(
        allowCheckpoints = meta?.allowCheckpoints == true,
        allowWindows = meta?.allowWindows == true,
        allowBlockedWindows = meta?.allowBlockedWindows == true,
    )
    return TimeTriggerParser.normalize(value, options, null) != "-1"
}

private fun Any?.asStringList(): List<String> = when (this) {
    is Array<*> -> mapNotNull { it?.toString() }
    is Iterable<*> -> mapNotNull { it?.toString() }
    else -> emptyList()
}

private fun Any?.asMapperList(): List<MapperEntity> = when (this) {
    is Iterable<*> -> filterIsInstance<MapperEntity>()
    is Array<*> -> filterIsInstance<MapperEntity>()
    else -> emptyList()
}

internal val SUPPORTED_FIELD_TYPES = setOf(
    "BOOLEAN",
    "CHOICE",
    "INTEGER",
    "MULTIPLY_INTEGER",
    "STRING",
    "TEXT",
    "READ_TEXT",
    "URL_TEXT",
    "SELECT",
    "SELECT_AND_COUNT",
    "FRIEND_SELECTION",
    "FRIEND_SELECTION_COUNT",
    "TIME_POINT",
    "TIME_POINT_LIST",
    "TIME_WINDOW_LIST",
    "TIME_TRIGGER",
    "HOUR_OF_DAY",
    "EMPTY",
)
