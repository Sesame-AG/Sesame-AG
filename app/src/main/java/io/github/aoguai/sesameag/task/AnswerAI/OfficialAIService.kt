package io.github.aoguai.sesameag.task.AnswerAI

import io.github.aoguai.sesameag.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AiProvider(val displayName: String) {
    OPENAI("OpenAI"),
    GEMINI("Gemini"),
    CLAUDE("Claude");

    companion object {
        val nickNames: Array<String> = entries.map { it.displayName }.toTypedArray()

        fun fromIndex(index: Int?): AiProvider {
            return entries.getOrElse(index ?: OPENAI.ordinal) { OPENAI }
        }
    }
}

enum class AiFormatMode(val displayName: String) {
    OFFICIAL("官方原生/Responses"),
    OPENAI_COMPAT_CHAT("OpenAI兼容 Chat Completions");

    companion object {
        val nickNames: Array<String> = entries.map { it.displayName }.toTypedArray()

        fun fromIndex(index: Int?): AiFormatMode {
            return entries.getOrElse(index ?: OFFICIAL.ordinal) { OFFICIAL }
        }
    }
}

enum class AiApiFormat(
    val provider: AiProvider,
    val mode: AiFormatMode,
    val displayName: String,
    val defaultBaseUrl: String
) {
    OPENAI_RESPONSES(
        AiProvider.OPENAI,
        AiFormatMode.OFFICIAL,
        "OpenAI / Responses",
        "https://api.openai.com/v1"
    ),
    OPENAI_CHAT_COMPLETIONS(
        AiProvider.OPENAI,
        AiFormatMode.OPENAI_COMPAT_CHAT,
        "OpenAI / Chat Completions",
        "https://api.openai.com/v1"
    ),
    GEMINI_GENERATE_CONTENT(
        AiProvider.GEMINI,
        AiFormatMode.OFFICIAL,
        "Gemini / Native GenerateContent",
        "https://generativelanguage.googleapis.com/v1beta"
    ),
    GEMINI_OPENAI_CHAT(
        AiProvider.GEMINI,
        AiFormatMode.OPENAI_COMPAT_CHAT,
        "Gemini / OpenAI Compatible Chat",
        "https://generativelanguage.googleapis.com/v1beta/openai"
    ),
    CLAUDE_MESSAGES(
        AiProvider.CLAUDE,
        AiFormatMode.OFFICIAL,
        "Claude / Native Messages",
        "https://api.anthropic.com/v1"
    ),
    CLAUDE_OPENAI_CHAT(
        AiProvider.CLAUDE,
        AiFormatMode.OPENAI_COMPAT_CHAT,
        "Claude / OpenAI Compatible Chat",
        "https://api.anthropic.com/v1"
    );

    companion object {
        fun resolve(provider: AiProvider, mode: AiFormatMode): AiApiFormat {
            return entries.firstOrNull { it.provider == provider && it.mode == mode } ?: OPENAI_RESPONSES
        }
    }
}

class OfficialAIService(
    provider: AiProvider,
    formatMode: AiFormatMode,
    apiKey: String?,
    baseUrl: String?,
    modelName: String?,
    maxTokens: Int?
) : AnswerAIInterface {

    private val apiKey: String = apiKey.orEmpty().trim()
    private val apiFormat: AiApiFormat = AiApiFormat.resolve(provider, formatMode)
    private val baseUrl: String = baseUrl.orEmpty().trim().removeSuffix("/").ifEmpty {
        apiFormat.defaultBaseUrl
    }
    private val maxTokens: Int = (maxTokens ?: DEFAULT_MAX_TOKENS).coerceAtLeast(1)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIME_OUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .writeTimeout(TIME_OUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(TIME_OUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .build()

    private var modelNameInternal: String = modelName.orEmpty().trim()

    override fun setModelName(modelName: String) {
        modelNameInternal = modelName.trim()
    }

    override fun getModelName(): String = modelNameInternal

    override fun getAnswerStr(text: String, model: String): String {
        setModelName(model)
        return getAnswerStr(text)
    }

    override fun getAnswerStr(text: String): String {
        if (!validateConfig()) {
            return ""
        }
        val cleanText = removeControlCharacters(text)
        return try {
            val result = when (apiFormat) {
                AiApiFormat.OPENAI_RESPONSES -> requestOpenAIResponses(cleanText)
                AiApiFormat.OPENAI_CHAT_COMPLETIONS,
                AiApiFormat.GEMINI_OPENAI_CHAT,
                AiApiFormat.CLAUDE_OPENAI_CHAT -> requestChatCompletions(cleanText)
                AiApiFormat.GEMINI_GENERATE_CONTENT -> requestGeminiGenerateContent(cleanText)
                AiApiFormat.CLAUDE_MESSAGES -> requestClaudeMessages(cleanText)
            }
            if (result.isBlank()) {
                Log.error(TAG, "AI请求未解析到有效响应：${apiFormat.displayName} / model=$modelNameInternal")
            }
            result
        } catch (e: IOException) {
            Log.error(TAG, "AI请求网络异常：${apiFormat.displayName} / model=$modelNameInternal / ${e.message}")
            Log.printStackTrace(TAG, e)
            ""
        } catch (e: JSONException) {
            Log.error(TAG, "AI响应解析异常：${apiFormat.displayName} / model=$modelNameInternal / ${e.message}")
            Log.printStackTrace(TAG, e)
            ""
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "AI请求参数异常：${apiFormat.displayName} / model=$modelNameInternal / ${e.message}")
            Log.printStackTrace(TAG, e)
            ""
        }
    }

    override fun getAnswer(title: String, answerList: List<String>): Int {
        val answerResult = getAnswerStr(buildAnswerPrompt(title, answerList))
        if (answerResult.isBlank()) {
            return -1
        }
        parseAnswerIndex(answerResult, answerList)?.let { return it }
        Log.error(TAG, "AI回答无法匹配候选项：${sanitizeResponse(answerResult)}")
        return -1
    }

    override fun release() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun validateConfig(): Boolean {
        if (apiKey.isBlank()) {
            Log.error(TAG, "AI服务配置错误：API Key为空")
            return false
        }
        if (modelNameInternal.isBlank()) {
            Log.error(TAG, "AI服务配置错误：模型名称为空")
            return false
        }
        return true
    }

    @Throws(IOException::class, JSONException::class)
    private fun requestOpenAIResponses(text: String): String {
        val requestJson = JSONObject()
            .put("model", modelNameInternal)
            .put("instructions", SYSTEM_MESSAGE)
            .put("input", text)
            .put("max_output_tokens", maxTokens)
        val response = sendJson(
            url = "$baseUrl/responses",
            requestJson = requestJson,
            headers = bearerHeaders()
        )
        return parseOpenAIResponses(response)
    }

    @Throws(IOException::class, JSONException::class)
    private fun requestChatCompletions(text: String): String {
        val requestJson = JSONObject()
            .put("model", modelNameInternal)
            .put("messages", buildOpenAIStyleMessages(text))
            .put("stream", false)
            .put("max_tokens", maxTokens)
        val response = sendJson(
            url = "$baseUrl/chat/completions",
            requestJson = requestJson,
            headers = bearerHeaders()
        )
        return parseChatCompletions(response)
    }

    @Throws(IOException::class, JSONException::class)
    private fun requestGeminiGenerateContent(text: String): String {
        val parts = JSONArray().put(JSONObject().put("text", text))
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val generationConfig = JSONObject().put("maxOutputTokens", maxTokens)
        val requestJson = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        val response = sendJson(
            url = "$baseUrl/models/${normalizeGeminiModelName(modelNameInternal)}:generateContent",
            requestJson = requestJson,
            headers = mapOf("x-goog-api-key" to apiKey)
        )
        return parseGeminiGenerateContent(response)
    }

    @Throws(IOException::class, JSONException::class)
    private fun requestClaudeMessages(text: String): String {
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", text)
        )
        val requestJson = JSONObject()
            .put("model", modelNameInternal)
            .put("max_tokens", maxTokens)
            .put("system", SYSTEM_MESSAGE)
            .put("messages", messages)
        val response = sendJson(
            url = "$baseUrl/messages",
            requestJson = requestJson,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION
            )
        )
        return parseClaudeMessages(response)
    }

    @Throws(IOException::class)
    private fun sendJson(
        url: String,
        requestJson: JSONObject,
        headers: Map<String, String>
    ): String {
        val requestText = requestJson.toString()
        val sanitizedUrl = sanitizeUrl(url)
        var lastException: IOException? = null

        for (attempt in 1..AI_HTTP_MAX_ATTEMPTS) {
            Log.common(
                TAG,
                "AI请求开始：${apiFormat.displayName} / model=$modelNameInternal / url=$sanitizedUrl / attempt=$attempt"
            )
            try {
                client.newCall(buildJsonRequest(url, requestText, headers)).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Log.common(
                            TAG,
                            "AI请求成功：${apiFormat.displayName} / HTTP ${response.code} / responseLength=${responseText.length}"
                        )
                        return responseText
                    }

                    Log.error(
                        TAG,
                        "AI请求失败：${apiFormat.displayName} / HTTP ${response.code} / ${sanitizeResponse(responseText)}"
                    )
                    if (!shouldRetryAiHttp(response.code, attempt)) {
                        return ""
                    }
                }
            } catch (e: IOException) {
                lastException = e
                if (!shouldRetryAiIOException(attempt)) {
                    throw e
                }
                Log.error(TAG, "AI请求网络异常，将重试一次：${apiFormat.displayName} / ${e.message}")
            }
            Thread.sleep(AI_HTTP_RETRY_DELAY_MS)
        }

        lastException?.let { throw it }
        return ""
    }

    private fun buildJsonRequest(
        url: String,
        requestText: String,
        headers: Map<String, String>
    ): Request {
        val body = requestText.toRequestBody(CONTENT_TYPE.toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", CONTENT_TYPE)

        for ((name, value) in headers) {
            requestBuilder.addHeader(name, value)
        }

        return requestBuilder.build()
    }

    private fun shouldRetryAiHttp(code: Int, attempt: Int): Boolean {
        return attempt < AI_HTTP_MAX_ATTEMPTS && code in AI_HTTP_RETRY_CODES
    }

    private fun shouldRetryAiIOException(attempt: Int): Boolean {
        return attempt < AI_HTTP_MAX_ATTEMPTS
    }

    private fun buildOpenAIStyleMessages(text: String): JSONArray {
        return JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", SYSTEM_MESSAGE)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", text)
            )
    }

    private fun bearerHeaders(): Map<String, String> {
        return mapOf("Authorization" to "Bearer $apiKey")
    }

    private fun parseOpenAIResponses(response: String): String {
        if (response.isBlank()) {
            return ""
        }
        val jsonObject = JSONObject(response)
        val directOutput = jsonObject.optString("output_text").trim()
        if (directOutput.isNotEmpty()) {
            return directOutput
        }

        val output = jsonObject.optJSONArray("output") ?: return ""
        val result = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    result.append(text)
                }
            }
        }
        return result.toString().trim()
    }

    private fun parseChatCompletions(response: String): String {
        if (response.isBlank()) {
            return ""
        }
        val jsonObject = JSONObject(response)
        val choices = jsonObject.optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return message.optString("content").trim()
    }

    private fun parseGeminiGenerateContent(response: String): String {
        if (response.isBlank()) {
            return ""
        }
        val jsonObject = JSONObject(response)
        val candidates = jsonObject.optJSONArray("candidates") ?: return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val result = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                result.append(text)
            }
        }
        return result.toString().trim()
    }

    private fun parseClaudeMessages(response: String): String {
        if (response.isBlank()) {
            return ""
        }
        val jsonObject = JSONObject(response)
        val content = jsonObject.optJSONArray("content") ?: return ""
        val result = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                result.append(block.optString("text"))
            }
        }
        return result.toString().trim()
    }

    private fun buildAnswerPrompt(title: String, answerList: List<String>): String {
        val answerText = StringBuilder()
        for (i in answerList.indices) {
            answerText.append(i + 1).append(".[").append(answerList[i]).append("]\n")
        }
        return """
            你正在做一道单选题，请根据你所知作答,选出其中正确的选项。
            输出要求：
            - 必须只输出一行 JSON，不要 Markdown，不要代码块，不要解释。
            - JSON 格式固定为：{"answer_index":1}
            - answer_index 是答案列表中的序号，必须是 1 到 ${answerList.size} 的整数。

            问题：$title

            选项列表：
            $answerText
        """.trimIndent()
    }

    private fun parseAnswerIndex(answerResult: String, answerList: List<String>): Int? {
        parseStructuredAnswerIndex(answerResult, answerList)?.let { return it }

        val numberMatch = NUMBER_REGEX.find(answerResult)
        val numberIndex = numberMatch?.value?.toIntOrNull()?.minus(1)
        if (numberIndex != null && numberIndex in answerList.indices) {
            return numberIndex
        }

        for (i in answerList.indices) {
            if (answerResult.contains(answerList[i])) {
                return i
            }
        }
        return null
    }

    private fun parseStructuredAnswerIndex(answerResult: String, answerList: List<String>): Int? {
        val jsonObject = extractJsonObject(answerResult) ?: return null

        for (key in ANSWER_INDEX_KEYS) {
            val index = parseJsonOneBasedIndex(jsonObject.opt(key), answerList.size)
            if (index != null) {
                return index
            }
        }

        for (key in ANSWER_TEXT_KEYS) {
            val answerText = jsonObject.optString(key).trim()
            if (answerText.isBlank()) {
                continue
            }
            for (i in answerList.indices) {
                if (answerText == answerList[i] || answerText.contains(answerList[i]) || answerList[i].contains(answerText)) {
                    return i
                }
            }
        }

        return null
    }

    private fun parseJsonOneBasedIndex(value: Any?, answerSize: Int): Int? {
        val oneBased = when (value) {
            null -> null
            is Number -> value.toInt()
            is String -> NUMBER_REGEX.find(value)?.value?.toIntOrNull()
            else -> NUMBER_REGEX.find(value.toString())?.value?.toIntOrNull()
        } ?: return null
        val index = oneBased - 1
        return if (index in 0 until answerSize) index else null
    }

    private fun extractJsonObject(answerResult: String): JSONObject? {
        val direct = cleanJsonFence(answerResult)
        parseJsonObjectOrNull(direct)?.let { return it }

        val start = answerResult.indexOf('{')
        val end = answerResult.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return null
        }
        return parseJsonObjectOrNull(answerResult.substring(start, end + 1))
    }

    private fun cleanJsonFence(answerResult: String): String {
        var text = answerResult.trim()
        if (!text.startsWith("```")) {
            return text
        }
        text = text.removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .trim()
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }
        return text
    }

    private fun parseJsonObjectOrNull(text: String): JSONObject? {
        return try {
            JSONObject(text)
        } catch (_: JSONException) {
            null
        }
    }

    private fun removeControlCharacters(text: String): String {
        return text.replace(CONTROL_CHARACTER_REGEX, "")
    }

    private fun normalizeGeminiModelName(modelName: String): String {
        return modelName.removePrefix("models/")
    }

    private fun sanitizeResponse(responseText: String): String {
        val sanitized = if (apiKey.isNotBlank()) {
            responseText.replace(apiKey, "***")
        } else {
            responseText
        }
        return sanitized.take(MAX_LOG_RESPONSE_LENGTH)
    }

    private fun sanitizeUrl(url: String): String {
        return url.substringBefore("?").take(MAX_LOG_URL_LENGTH)
    }

    companion object {
        private val TAG = OfficialAIService::class.java.simpleName
        private val NUMBER_REGEX = Regex("\\d+")
        private val CONTROL_CHARACTER_REGEX = Regex("[\\p{Cntrl}&&[^\n\t]]")
        private val ANSWER_INDEX_KEYS = arrayOf("answer_index", "answerIndex", "index", "answer")
        private val ANSWER_TEXT_KEYS = arrayOf("answer_text", "answerText", "answer", "text", "option")
        private const val CONTENT_TYPE = "application/json"
        private const val SYSTEM_MESSAGE = "你是一个拥有丰富知识，并且能根据知识回答问题的专家。需要结构化输出时，必须只输出有效 JSON，不输出 Markdown、代码块或解释。"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 1024
        private const val TIME_OUT_SECONDS = 180
        private const val MAX_LOG_RESPONSE_LENGTH = 500
        private const val MAX_LOG_URL_LENGTH = 200
        private const val AI_HTTP_MAX_ATTEMPTS = 2
        private const val AI_HTTP_RETRY_DELAY_MS = 800L
        private val AI_HTTP_RETRY_CODES = setOf(429, 502, 503)
    }
}
