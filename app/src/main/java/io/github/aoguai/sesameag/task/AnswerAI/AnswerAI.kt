package io.github.aoguai.sesameag.task.AnswerAI

import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.EmptyModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.StringModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogCatalog
import io.github.aoguai.sesameag.util.LogChannel

data class AiTestResult(
    val success: Boolean,
    val message: String
)

class AnswerAI : Model() {

    private val aiApiKey = StringModelField("aiApiKey", "AI服务 | API Key", "").withDesc(
        "填写所选 AI 服务的 API Key。"
    )
    private val aiBaseUrl = StringModelField("aiBaseUrl", "AI服务 | BaseUrl（可选）", "").withDesc(
        "填写接口根地址；留空时使用所选服务与格式的官方默认地址。第三方兼容服务也在这里填写根地址。"
    )
    private val aiModel = StringModelField("aiModel", "AI服务 | 模型名称", "gpt-5").withDesc(
        "填写模型名称；请按各自平台填写对应模型。"
    )
    private val aiMaxTokens = IntegerModelField("aiMaxTokens", "AI服务 | 输出Token上限", 1024, 1, 8192).withDesc(
        "限制单次答题输出长度。Claude原生格式必须使用该值，其他格式也会尽量传递给接口。"
    )
    private val aiTestField = EmptyModelField(FIELD_AI_TEST, "AI服务 | 测试响应").withDesc(
        "使用当前配置发起一次简单请求，确认 API Key、BaseUrl、模型和格式是否可用。"
    )

    override fun getName(): String = "AI答题"

    override fun getGroup(): ModelGroup = ModelGroup.OTHER

    override fun getIcon(): String = "AnswerAI.svg"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(aiProvider)
        modelFields.addField(aiApiFormat)
        modelFields.addField(aiApiKey)
        modelFields.addField(aiBaseUrl)
        modelFields.addField(aiModel)
        modelFields.addField(aiMaxTokens)
        modelFields.addField(aiTestField)
        return modelFields
    }

    override fun prepare() {
        enable = enableField.value == true
        if (!enable) {
            disableAIService()
        }
    }

    override fun boot(classLoader: ClassLoader?) {
        try {
            enable = enableField.value == true
            val provider = getSelectedProvider()
            val formatMode = getSelectedFormatMode()
            val apiFormat = AiApiFormat.resolve(provider, formatMode)
            Log.runtime("初始化AI服务：Provider=[${provider.displayName}], Format=[${apiFormat.displayName}]")
            initializeAIService(provider, formatMode)
        } catch (e: Exception) {
            Log.error(TAG, "初始化AI服务失败: ${e.message}")
            Log.printStackTrace(TAG, e)
            disableAIService()
        }
    }

    override fun destroy() {
        disableAIService()
    }

    private fun initializeAIService(provider: AiProvider, formatMode: AiFormatMode) {
        val nextService = OfficialAIService(
            provider = provider,
            formatMode = formatMode,
            apiKey = aiApiKey.value,
            baseUrl = aiBaseUrl.value,
            modelName = aiModel.value,
            maxTokens = aiMaxTokens.value
        )
        answerAIInterface?.release()
        answerAIInterface = nextService
    }

    private fun disableAIService() {
        enable = false
        answerAIInterface?.release()
        answerAIInterface = null
    }

    fun testAnswerService(): AiTestResult {
        val provider = getSelectedProvider()
        val formatMode = getSelectedFormatMode()
        val apiFormat = AiApiFormat.resolve(provider, formatMode)
        val apiKey = aiApiKey.value.orEmpty().trim()
        val model = aiModel.value.orEmpty().trim()
        if (apiKey.isBlank()) {
            Log.error(TAG, "AI测试失败：API Key为空")
            return AiTestResult(false, "AI测试失败：API Key为空")
        }
        if (model.isBlank()) {
            Log.error(TAG, "AI测试失败：模型名称为空")
            return AiTestResult(false, "AI测试失败：模型名称为空")
        }

        val service = OfficialAIService(
            provider = provider,
            formatMode = formatMode,
            apiKey = apiKey,
            baseUrl = aiBaseUrl.value,
            modelName = model,
            maxTokens = aiMaxTokens.value
        )
        return try {
            Log.common(TAG, "AI测试开始：${apiFormat.displayName} / $model")
            val response = service.getAnswerStr(TEST_PROMPT)
            if (response.isBlank()) {
                Log.error(TAG, "AI测试失败：${apiFormat.displayName} 未返回有效内容")
                AiTestResult(false, "AI测试失败：${apiFormat.displayName} 未返回有效内容，请检查配置或查看日志")
            } else {
                Log.common(TAG, "AI测试成功：${apiFormat.displayName} / $model 响应=${sanitizeTestResponse(response)}")
                AiTestResult(true, "AI测试成功：${apiFormat.displayName} / $model 响应=${sanitizeTestResponse(response)}")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "AI测试异常:", t)
            AiTestResult(false, "AI测试异常：${t.message ?: t.javaClass.simpleName}")
        } finally {
            service.release()
        }
    }

    companion object {
        const val FIELD_AI_TEST = "aiTest"
        private val TAG = AnswerAI::class.java.simpleName
        private const val ANSWER_CACHE_KEY = "answerAIQuestionCache"
        private const val MAX_ANSWER_CACHE_SIZE = 300
        private const val TEST_PROMPT = "这是一次接口连通性测试。请只回复 OK。"
        private const val QUESTION_LOG_FORMAT = "题目📒 [%s] | 选项: %s"
        private const val CACHE_ANSWER_LOG_FORMAT = "AI正确缓存回答📦 [%s]"
        private const val AI_ANSWER_LOG_FORMAT = "AI回答🧠 [%s] | AI类型: [%s] | 模型名称: [%s]"
        private const val FALLBACK_ANSWER_LOG_FORMAT = "兜底回答🤖 [%s] | 原因: %s"
        private const val ERROR_AI_ANSWER = "AI回答异常：无法获取有效答案，请检查AI服务配置是否正确"

        private var enable = false
        private var answerAIInterface: AnswerAIInterface? = null
        private val aiProvider = ChoiceModelField(
            "aiProvider",
            "AI服务 | 服务商",
            AiProvider.OPENAI.ordinal,
            AiProvider.nickNames
        ).withDesc("选择当前用于自动答题的 AI 服务。")
        private val aiApiFormat = ChoiceModelField(
            "aiApiFormat",
            "AI服务 | API格式",
            AiFormatMode.OFFICIAL.ordinal,
            AiFormatMode.nickNames
        ).withDesc("选择官方原生格式或 OpenAI 兼容 Chat Completions 格式；实际请求格式由 AI 服务共同决定。")

        private fun getSelectedProvider(): AiProvider {
            return AiProvider.fromIndex(aiProvider.value)
        }

        private fun getSelectedFormatMode(): AiFormatMode {
            return AiFormatMode.fromIndex(aiApiFormat.value)
        }

        private fun getCurrentAiLabel(): String {
            val provider = getSelectedProvider()
            val format = AiApiFormat.resolve(provider, getSelectedFormatMode())
            return "${provider.displayName} / ${format.displayName}"
        }

        private fun resolveLogChannel(flag: String): LogChannel {
            val channel = LogCatalog.findByLoggerName(flag.trim()) ?: return LogChannel.COMMON
            return when (channel) {
                LogChannel.COMMON,
                LogChannel.FOREST,
                LogChannel.ORCHARD,
                LogChannel.GOLDEN_BEAN,
                LogChannel.FARM,
                LogChannel.STALL,
                LogChannel.OCEAN,
                LogChannel.DODO,
                LogChannel.MEMBER,
                LogChannel.SPORTS,
                LogChannel.GREEN_FINANCE,
                LogChannel.SESAME_CREDIT -> channel
                else -> LogChannel.COMMON
            }
        }

        private fun logAnswerInfo(flag: String, msg: String) {
            val channel = resolveLogChannel(flag).loggerName
            Log.common(TAG, "[$channel] $msg")
        }

        private fun logAnswerError(flag: String, msg: String) {
            val channel = resolveLogChannel(flag).loggerName
            Log.error(TAG, "[$channel] $msg")
        }

        private fun buildAnswerCacheKey(flag: String, text: String): String {
            val channel = resolveLogChannel(flag).loggerName
            val normalizedText = text.trim().replace(Regex("\\s+"), " ")
            return "$channel|$normalizedText"
        }

        private fun matchCachedAnswer(cachedAnswer: String?, answerList: List<String>): String? {
            val answer = cachedAnswer?.trim().orEmpty()
            if (answer.isBlank() || answerList.isEmpty()) {
                return null
            }
            answerList.firstOrNull { it == answer }?.let { return it }
            return answerList.firstOrNull { option ->
                option.contains(answer) || answer.contains(option)
            }
        }

        private fun getVerifiedCachedAnswer(text: String, answerList: List<String>, flag: String): String? {
            return try {
                val cache = DataStore.getOrCreate<MutableMap<String, String>>(ANSWER_CACHE_KEY)
                matchCachedAnswer(cache[buildAnswerCacheKey(flag, text)], answerList)
            } catch (t: Throwable) {
                Log.error(TAG, "读取AI答题正确缓存失败：${t.message}")
                null
            }
        }

        @JvmStatic
        fun rememberAnswer(text: String?, answerList: List<String>?, answer: String?, flag: String) {
            if (text.isNullOrBlank() || answerList.isNullOrEmpty()) {
                return
            }
            val matchedAnswer = matchCachedAnswer(answer, answerList) ?: return
            try {
                val cache = DataStore.getOrCreate<MutableMap<String, String>>(ANSWER_CACHE_KEY)
                cache[buildAnswerCacheKey(flag, text)] = matchedAnswer
                trimAnswerCache(cache)
                DataStore.put(ANSWER_CACHE_KEY, cache)
            } catch (t: Throwable) {
                Log.error(TAG, "写入AI答题正确缓存失败：${t.message}")
            }
        }

        @JvmStatic
        fun removeCachedAnswer(text: String?, flag: String) {
            if (text.isNullOrBlank()) {
                return
            }
            try {
                val cache = DataStore.getOrCreate<MutableMap<String, String>>(ANSWER_CACHE_KEY)
                if (cache.remove(buildAnswerCacheKey(flag, text)) != null) {
                    DataStore.put(ANSWER_CACHE_KEY, cache)
                }
            } catch (t: Throwable) {
                Log.error(TAG, "移除AI答题正确缓存失败：${t.message}")
            }
        }

        private fun trimAnswerCache(cache: MutableMap<String, String>) {
            if (cache.size <= MAX_ANSWER_CACHE_SIZE) {
                return
            }
            val iterator = cache.keys.iterator()
            while (cache.size > MAX_ANSWER_CACHE_SIZE && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }

        private fun sanitizeTestResponse(response: String): String {
            return response.trim().replace(Regex("\\s+"), " ").take(120)
        }

        private fun fallbackAnswer(answerList: List<String>, flag: String, reason: String): String {
            if (answerList.isEmpty()) {
                return ""
            }
            val answer = answerList[0]
            logAnswerInfo(flag, String.format(FALLBACK_ANSWER_LOG_FORMAT, answer, reason))
            return answer
        }

        @JvmStatic
        fun getAnswer(text: String?, answerList: List<String>?, flag: String): String {
            if (answerList.isNullOrEmpty()) {
                logAnswerError(flag, "答案列表为空")
                return ""
            }
            if (text.isNullOrBlank()) {
                logAnswerError(flag, "问题为空")
                return fallbackAnswer(answerList, flag, "问题为空")
            }
            var answerStr = ""
            try {
                val msg = String.format(QUESTION_LOG_FORMAT, text, answerList)
                logAnswerInfo(flag, msg)

                getVerifiedCachedAnswer(text, answerList, flag)?.let { cachedAnswer ->
                    answerStr = cachedAnswer
                    logAnswerInfo(flag, String.format(CACHE_ANSWER_LOG_FORMAT, answerStr))
                    return answerStr
                }

                if (enable && answerAIInterface != null) {
                    val answer = answerAIInterface?.getAnswer(text, answerList)
                    if (answer != null && answer >= 0 && answer < answerList.size) {
                        answerStr = answerList[answer]
                        val logMsg = String.format(
                            AI_ANSWER_LOG_FORMAT,
                            answerStr,
                            getCurrentAiLabel(),
                            answerAIInterface?.getModelName() ?: ""
                        )
                        logAnswerInfo(flag, logMsg)
                    } else {
                        logAnswerError(flag, ERROR_AI_ANSWER)
                        answerStr = fallbackAnswer(answerList, flag, "AI未返回有效答案")
                    }
                } else {
                    answerStr = fallbackAnswer(answerList, flag, "AI服务未启用")
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "AI获取答案异常:", t)
                answerStr = fallbackAnswer(answerList, flag, "AI异常")
            }
            return answerStr
        }
    }
}
