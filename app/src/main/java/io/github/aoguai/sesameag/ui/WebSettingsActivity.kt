package io.github.aoguai.sesameag.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.core.type.TypeReference
import com.google.android.material.appbar.MaterialToolbar
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelConfig
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.ui.dto.ModelDto
import io.github.aoguai.sesameag.ui.dto.ModelFieldInfoDto
import io.github.aoguai.sesameag.ui.dto.ModelFieldShowDto
import io.github.aoguai.sesameag.ui.dto.ModelGroupDto
import io.github.aoguai.sesameag.task.customTasks.ManualTaskModel
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.PortUtil
import io.github.aoguai.sesameag.util.ToastUtil
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.friend.FriendSelectionResolver
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
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

class WebSettingsActivity : AppCompatActivity() {

    private lateinit var exportLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Intent>
    private lateinit var toolbar: MaterialToolbar
    private lateinit var webView: WebView
    private lateinit var context: Context
    private var progressBar: ProgressBar? = null
    private var userId: String? = null
    private var userName: String? = null
    private val tabList = ArrayList<ModelDto>()
    private val groupList = ArrayList<ModelGroupDto>()
    @Volatile
    private var cachedFriendMapModifiedAt: Long = Long.MIN_VALUE
    @Volatile
    private var cachedFriendMapLength: Long = Long.MIN_VALUE
    @Volatile
    private var loadedOptionMapsForUser: String? = null

    @SuppressLint("MissingInflatedId", "SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        context = this
        userId = intent?.getStringExtra("userId")
        userName = intent?.getStringExtra("userName")

        setContentView(R.layout.activity_web_settings)
        setupToolbar()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progress_bar)

        // 返回键：优先 WebView 后退，否则确认保存意图。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    Log.record(TAG, "WebSettingsActivity.handleOnBackPressed: go back")
                    webView.goBack()
                } else {
                    Log.record(TAG, "WebSettingsActivity.handleOnBackPressed: confirm exit")
                    confirmExit()
                }
            }
        })

        // 初始化导出逻辑（必须在 onCreate 中注册）
        exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                PortUtil.handleExport(this, result.data?.data, userId)
            }
        }

        // 初始化导入逻辑（必须在 onCreate 中注册）
        importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                PortUtil.handleImport(this, result.data?.data, userId)
            }
        }

        // 先展示 loading，后台加载配置与映射，避免阻塞 UI
        progressBar?.visibility = View.VISIBLE
        webView.visibility = View.GONE

        GlobalThreadPools.execute(Dispatchers.IO) {
            try {
                Model.initAllModel()
                UserMap.setCurrentUserId(userId)
                syncFriendCenterFromUserMapIfNeeded(force = true)

                userId?.let { Status.load(it) }
                Config.load(userId)

                runOnUiThread {
                    try {
                        webView.visibility = View.VISIBLE
                        initializeWebView()
                    } catch (e: Exception) {
                        Log.printStackTrace(TAG, "WebSettingsActivity.initializeWebView failed", e)
                        Toast.makeText(this@WebSettingsActivity, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                loadOptionMapsForUserIfNeeded()
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "WebSettingsActivity load failed", e)
                runOnUiThread {
                    progressBar?.visibility = View.GONE
                    Toast.makeText(this@WebSettingsActivity, "加载配置失败: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.x_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { confirmExit() }
        toolbar.setContentInsetsAbsolute(0, 0)
        toolbar.title = null
        toolbar.subtitle = userName?.let { "${getString(R.string.settings)}: $it" } ?: getString(R.string.settings)
    }

    private fun confirmExit() {
        val dialog = AlertDialog.Builder(context)
            .setTitle("离开设置")
            .setMessage("是否保存当前配置修改？")
            .setPositiveButton("保存并退出") { _, _ ->
                if (save()) {
                    finish()
                }
            }
            .setNegativeButton("不保存退出") { _, _ -> finish() }
            .setNeutralButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()
        dialog.setOnShowListener {
            val buttonTextColor = ContextCompat.getColor(context, R.color.selection_color)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(buttonTextColor)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(buttonTextColor)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setTextColor(buttonTextColor)
        }
        dialog.show()
    }

    private fun ensureSettingsUserContext() {
        val currentUserId = userId?.trim().orEmpty()
        if (currentUserId.isEmpty()) return
        if (UserMap.currentUid != currentUserId) {
            UserMap.setCurrentUserId(currentUserId)
            cachedFriendMapModifiedAt = Long.MIN_VALUE
            cachedFriendMapLength = Long.MIN_VALUE
        }
        syncFriendCenterFromUserMapIfNeeded()
    }

    private fun syncFriendCenterFromUserMapIfNeeded(force: Boolean = false) {
        val currentUserId = userId?.trim().orEmpty()
        if (currentUserId.isEmpty()) {
            return
        }
        val friendMapFile = Files.getFriendIdMapFile(currentUserId)
        val modifiedAt = friendMapFile?.lastModified() ?: Long.MIN_VALUE
        val length = friendMapFile?.length() ?: Long.MIN_VALUE
        if (!force && modifiedAt == cachedFriendMapModifiedAt && length == cachedFriendMapLength) {
            return
        }
        UserMap.load(currentUserId)
        // friend.json 由 Hook 写入完整好友快照；这里允许把快照中缺失的历史好友标记为失效。
        FriendRepository.mergeFromUserMap(currentUserId, allowPruneMissing = true)
        cachedFriendMapModifiedAt = modifiedAt
        cachedFriendMapLength = length
    }

    @Synchronized
    private fun loadOptionMapsForUserIfNeeded() {
        val currentUserId = userId?.trim().orEmpty()
        val loadedKey = currentUserId.ifBlank { "<default>" }
        if (loadedOptionMapsForUser == loadedKey) {
            return
        }
        IdMapManager.getInstance(CooperateMap::class.java).load(userId)
        IdMapManager.getInstance(VitalityRewardsMap::class.java).load(userId)
        IdMapManager.getInstance(MemberBenefitsMap::class.java).load(userId)
        IdMapManager.getInstance(BeanExchangeRightMap::class.java).load(userId)
        IdMapManager.getInstance(SesameGiftMap::class.java).load(userId)
        IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java).load(userId)
        IdMapManager.getInstance(SportsEnergyExchangeMap::class.java).load(userId)
        IdMapManager.getInstance(ReserveaMap::class.java).load()
        IdMapManager.getInstance(BeachMap::class.java).load()
        loadedOptionMapsForUser = loadedKey
    }

    private fun initializeWebView() {
        webView.settings.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            defaultTextEncodingName = StandardCharsets.UTF_8.name()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                Log.error(TAG, "WebView加载错误: code=${error.errorCode}, desc=${error.description}, url=${request.url}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val requestUrl = request.url
                val scheme = requestUrl.scheme
                return when {
                    scheme.equals("http", ignoreCase = true) ||
                        scheme.equals("https", ignoreCase = true) ||
                        scheme.equals("ws", ignoreCase = true) ||
                        scheme.equals("wss", ignoreCase = true) -> {
                        view.loadUrl(requestUrl.toString())
                        true
                    }
                    else -> {
                        view.stopLoading()
                        Toast.makeText(context, "Forbidden Scheme:\"$scheme\"", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.runtime(
                        TAG,
                        "WebView Console [${it.messageLevel()}]: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}"
                    )
                }
                return true
            }
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // 先构建数据，避免页面启动过快时拿到空列表
        tabList.clear()
        groupList.clear()

        val modelConfigMap: Map<String, ModelConfig> = Model.getModelConfigMap()
        for ((code, modelConfig) in modelConfigMap.entries) {
            if (!shouldShowInWebSettings(modelConfig)) continue
            tabList.add(
                ModelDto(
                    modelCode = code,
                    modelName = modelConfig.name,
                    modelIcon = modelConfig.icon,
                    groupCode = modelConfig.group?.code ?: "",
                    modelFields = emptyList()
                )
            )
        }
        for (modelGroup in ModelGroup.values()) {
            groupList.add(ModelGroupDto(modelGroup.code, modelGroup.groupName, modelGroup.icon))
        }

        webView.addJavascriptInterface(WebViewCallback(), "HOOK")
        webView.loadUrl("file:///android_asset/web/semi_index.html")
        webView.requestFocus()
    }

    private data class FieldOptionsResult(
        val requestId: String,
        val success: Boolean,
        val expandValue: Any? = null,
        val message: String = ""
    )

    private fun deliverFieldOptionsResult(result: FieldOptionsResult) {
        val payload = JsonUtil.formatJson(result, false)
        runOnUiThread {
            if (isFinishing || isDestroyed || !::webView.isInitialized) {
                return@runOnUiThread
            }
            webView.evaluateJavascript(
                "window.__onFieldOptionsResult && window.__onFieldOptionsResult($payload);",
                null
            )
        }
    }

    inner class WebViewCallback {
        @JavascriptInterface
        fun getTabs(): String {
            return JsonUtil.formatJson(tabList, false)
        }

        /**
         * 新增：检查当前系统是否为深色模式
         */
        @JavascriptInterface
        fun isNightMode(): Boolean {
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        }

        @JavascriptInterface
        fun getBuildInfo(): String {
            return "${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_NAME}"
        }

        @JavascriptInterface
        fun getGroup(): String {
            return JsonUtil.formatJson(groupList, false)
        }

        @JavascriptInterface
        fun getModelByGroup(groupCode: String): String {
            ensureSettingsUserContext()
            val modelGroup = ModelGroup.getByCode(groupCode) ?: return "[]"
            val modelConfigCollection = Model.getGroupModelConfig(modelGroup).values
            val modelDtoList = ArrayList<ModelDto>()
            for (modelConfig in modelConfigCollection) {
                if (!shouldShowInWebSettings(modelConfig)) continue
                val modelFields = ArrayList<ModelFieldShowDto>()
                for (modelField in modelConfig.fields.values) {
                    modelFields.add(ModelFieldShowDto.toShowDto(modelConfig.code, modelConfig.fields, modelField))
                }
                modelDtoList.add(ModelDto(modelConfig.code, modelConfig.name, modelConfig.icon, groupCode, modelFields))
            }
            return JsonUtil.formatJson(modelDtoList, false)
        }

        @JavascriptInterface
        fun setModelByGroup(groupCode: String, modelsValue: String): String {
            ensureSettingsUserContext()
            val modelDtoList = JsonUtil.parseObject(modelsValue, object : TypeReference<List<ModelDto>>() {})
            val modelGroup = ModelGroup.getByCode(groupCode) ?: return "FAILED"
            val modelConfigSet = Model.getGroupModelConfig(modelGroup)

            for (modelDto in modelDtoList) {
                val modelConfig = modelConfigSet[modelDto.modelCode] ?: continue
                val modelFields = modelDto.modelFields
                for (newModelField in modelFields) {
                    val modelField = modelConfig.getModelField(newModelField.code) ?: continue
                    modelField.setConfigValue(newModelField.configValue as String?)
                }
            }
            Config.sanitizeFriendSelectionFieldsForUser(userId)
            return "SUCCESS"
        }

        @JavascriptInterface
        fun getModel(modelCode: String): String {
            ensureSettingsUserContext()
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return "[]"

            val modelFields: ModelFields = modelConfig.fields
            val list = ArrayList<ModelFieldShowDto>()
            for (modelField: ModelField<*> in modelFields.values) {
                list.add(ModelFieldShowDto.toShowDto(modelConfig.code, modelFields, modelField))
            }
            return JsonUtil.formatJson(list, false)
        }

        @JavascriptInterface
        fun setModel(modelCode: String, fieldsValue: String): String {
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return "FAILED"
            try {
                ensureSettingsUserContext()
                val modelFields: ModelFields = modelConfig.fields
                val map: Map<String, ModelFieldShowDto> = JsonUtil.parseObject(
                    fieldsValue,
                    object : TypeReference<Map<String, ModelFieldShowDto>>() {}
                )
                for ((fieldCode, newModelField) in map.entries) {
                    val modelField = modelFields[fieldCode] ?: continue
                    modelField.setConfigValue(newModelField.configValue)
                }
                Config.sanitizeFriendSelectionFieldsForUser(userId)
                return "SUCCESS"
            } catch (e: Exception) {
                Log.printStackTrace("WebSettingsActivity", e)
            }
            return "FAILED"
        }

        @JavascriptInterface
        fun getField(modelCode: String, fieldCode: String): String? {
            ensureSettingsUserContext()
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return null
            val modelField = modelConfig.getModelField(fieldCode) ?: return null
            return JsonUtil.formatJson(ModelFieldInfoDto.toInfoDto(modelField), false)
        }

        @JavascriptInterface
        fun requestFieldOptions(modelCode: String, fieldCode: String, requestId: String): Boolean {
            val safeRequestId = requestId.trim()
            if (safeRequestId.isEmpty()) {
                return false
            }
            GlobalThreadPools.execute(Dispatchers.IO) {
                val result = try {
                    ensureSettingsUserContext()
                    loadOptionMapsForUserIfNeeded()
                    val modelConfig = Model.getModelConfigMap()[modelCode]
                    val modelField = modelConfig?.getModelField(fieldCode)
                    when {
                        modelConfig == null -> FieldOptionsResult(
                            safeRequestId,
                            false,
                            message = "模型不存在：$modelCode"
                        )

                        modelField == null -> FieldOptionsResult(
                            safeRequestId,
                            false,
                            message = "字段不存在：$fieldCode"
                        )

                        else -> FieldOptionsResult(
                            requestId = safeRequestId,
                            success = true,
                            expandValue = modelField.getExpandValue()
                        )
                    }
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "requestFieldOptions failed", t)
                    FieldOptionsResult(
                        requestId = safeRequestId,
                        success = false,
                        message = t.message ?: t.javaClass.simpleName
                    )
                }
                deliverFieldOptionsResult(result)
            }
            return true
        }

        @JavascriptInterface
        fun getFriendSelectionData(modelCode: String, fieldCode: String): String {
            ensureSettingsUserContext()
            Config.sanitizeFriendSelectionFieldsForUser(userId)
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return "{}"
            val modelField = modelConfig.getModelField(fieldCode) ?: return "{}"
            val friendConfig = FriendRepository.current(userId)
            val friends = friendConfig.profiles.values.map { profile ->
                if (profile.userId.isBlank() || profile.relation == FriendRelation.SELF) {
                    null
                } else {
                    linkedMapOf(
                        "id" to profile.userId,
                        "name" to profile.displayName.ifBlank { profile.userId },
                        "relation" to profile.relation.name,
                        "globalBlocked" to profile.globalBlocked,
                        "removed" to profile.removed,
                        "capabilities" to profile.capabilities
                    )
                }
            }.filterNotNull()
            val preview = when (modelField) {
                is FriendSelectionCountModelField -> FriendSelectionResolver.previewCount(modelField.value, userId)
                is FriendSelectionModelField -> FriendSelectionResolver.preview(modelField.value, userId)
                else -> FriendSelectionResolver.preview(null, userId)
            }
            val payload = linkedMapOf(
                "friends" to friends,
                "groups" to friendConfig.groups,
                "field" to ModelFieldInfoDto.toInfoDto(modelField),
                "preview" to preview.items,
                "summary" to preview.summary
            )
            return JsonUtil.formatJson(payload, false)
        }

        @JavascriptInterface
        fun getFriendSelectionSummary(modelCode: String, fieldCode: String): String {
            ensureSettingsUserContext()
            Config.sanitizeFriendSelectionFieldsForUser(userId)
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return "{}"
            val modelField = modelConfig.getModelField(fieldCode) ?: return "{}"
            val preview = when (modelField) {
                is FriendSelectionCountModelField -> FriendSelectionResolver.previewCount(modelField.value, userId)
                is FriendSelectionModelField -> FriendSelectionResolver.preview(modelField.value, userId)
                else -> FriendSelectionResolver.preview(null, userId)
            }
            return JsonUtil.formatJson(preview.summary, false)
        }

        @JavascriptInterface
        fun setField(modelCode: String, fieldCode: String, fieldValue: String): String {
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return "FAILED"
            return try {
                ensureSettingsUserContext()
                val modelField = modelConfig.getModelField(fieldCode) ?: return "FAILED"
                modelField.setConfigValue(fieldValue)
                Config.sanitizeFriendSelectionFieldsForUser(userId)
                "SUCCESS"
            } catch (e: Exception) {
                Log.printStackTrace(e)
                "FAILED"
            }
        }

        @JavascriptInterface
        fun runFieldAction(modelCode: String, fieldCode: String): String {
            return try {
                ensureSettingsUserContext()
                when {
                    modelCode == AnswerAI::class.java.simpleName && fieldCode == AnswerAI.FIELD_AI_TEST -> {
                        val result = Model.getModel(AnswerAI::class.java)?.testAnswerService()
                            ?: return actionResult(false, "AI答题模块未初始化")
                        actionResult(result.success, result.message)
                    }
                    else -> actionResult(false, "不支持的配置操作：$modelCode.$fieldCode")
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "runFieldAction failed", t)
                actionResult(false, "执行失败：${t.message ?: t.javaClass.simpleName}")
            }
        }

        /**
         * 保存并退出：
         * 前端调用 window.HOOK.saveOnExit() 时触发
         */
        @JavascriptInterface
        fun saveOnExit(): Boolean {
            runOnUiThread {
                Log.record(TAG, "WebViewCallback: saveOnExit called")
                if (save()) {
                    finish()
                }
            }
            return true
        }

        @JavascriptInterface
        fun Log(log: String) {
            Log.record(TAG, "设置：$log")
        }

        private fun actionResult(success: Boolean, message: String): String {
            return JSONObject()
                .put("success", success)
                .put("message", message)
                .toString()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 1, "导出配置")
        menu.add(0, 2, 2, "导入配置")
        menu.add(0, 3, 3, "删除配置")
        menu.add(0, 5, 5, "好友中心")
        menu.add(0, 6, 6, "保存")
        menu.add(0, 7, 7, "复制ID")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val exportIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, "[$userName]-config_v2.json")
                }
                exportLauncher.launch(exportIntent)
            }
            2 -> {
                val importIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, "config_v2.json")
                }
                importLauncher.launch(importIntent)
            }
            3 -> {
                AlertDialog.Builder(context)
                    .setTitle("警告")
                    .setMessage("确认删除该配置？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val currentUserId = userId
                        val userConfigDirectoryFile: File = if (currentUserId.isNullOrEmpty()) {
                            Files.getDefaultConfigV2File()
                        } else {
                            Files.getUserConfigDir(currentUserId)
                        }
                        if (Files.delFile(userConfigDirectoryFile)) {
                            Toast.makeText(this, "配置删除成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "配置删除失败", Toast.LENGTH_SHORT).show()
                        }
                        finish()
                    }
                    .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                    .create()
                    .show()
            }
            5 -> {
                startActivity(Intent(this, FriendCenterActivity::class.java).apply {
                    putExtra("userId", userId)
                    putExtra("userName", userName)
                })
            }
            6 -> {
                save()
            }
            7 -> {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = ClipData.newPlainText("userId", userId)
                cm.setPrimaryClip(clipData)
                ToastUtil.showToastWithDelay(this, "复制成功！", 100)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save(): Boolean {
        // 与 TK3 对齐：强制保存，避免 isModify 误判导致用户点击“保存”却未落盘
        if (!Config.save(userId, true)) {
            Toast.makeText(context, "保存失败！", Toast.LENGTH_SHORT).show()
            return false
        }

        Toast.makeText(context, "保存成功！", Toast.LENGTH_SHORT).show()
        Log.record(TAG, "配置保存成功: userId=${userId ?: "默认"}, configReload=${!userId.isNullOrEmpty()}")
        if (!userId.isNullOrEmpty()) {
            try {
                val intent = Intent(ApplicationHookConstants.BroadcastActions.RESTART).apply {
                    putExtra("userId", userId)
                    putExtra("configReload", true)
                }
                sendBroadcast(intent)
                Log.record(TAG, "已发送配置重载广播: action=${ApplicationHookConstants.BroadcastActions.RESTART}, userId=$userId")
            } catch (th: Throwable) {
                Log.printStackTrace(th)
            }
        }

        val currentUserId = userId
        if (!currentUserId.isNullOrEmpty()) {
            UserMap.save(currentUserId)
            IdMapManager.getInstance(CooperateMap::class.java).save(currentUserId)
        }
        return true
    }

    private fun shouldShowInWebSettings(modelConfig: ModelConfig): Boolean {
        // “手动调度任务”已有独立入口，避免在 Web 配置页重复暴露第二个入口。
        return modelConfig.code != ManualTaskModel::class.java.simpleName
    }

    companion object {
        private const val TAG = "WebSettingsActivity"
    }
}

