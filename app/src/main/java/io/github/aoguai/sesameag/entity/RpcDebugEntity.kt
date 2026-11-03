package io.github.aoguai.sesameag.entity

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * RPC 调试项数据模型
 */
/**
 * RPC 调试项数据模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RpcDebugEntity(
    // ✅ 使用 @field:JsonAlias 消除警告，同时支持多个别名

    @field:JsonAlias("Name")
    var name: String = "",

    // 🔥 关键：同时支持 "method" (默认), "methodName", "Method"
    @field:JsonAlias("methodName", "Method")
    var method: String = "",

    @field:JsonAlias("RequestData")
    var requestData: Any? = null,

    var id: String = "",

    @field:JsonAlias("Description", "desc", "Desc")
    var description: String = "",

    @field:JsonAlias("scheduleEnabled", "scheduleEnable", "enableSchedule", "EnableSchedule")
    var scheduleEnabled: Boolean = false,

    @field:JsonAlias("dailyCount", "dayCount", "DailyCount")
    var dailyCount: Int = 0
) {
    companion object {
        private val objectMapper = ObjectMapper()
    }

    init {
        if (id.isBlank()) {
            id = stableId(method, requestData)
        }
        if (dailyCount < 0) dailyCount = 0
        if (!scheduleEnabled) dailyCount = 0
    }

    @JsonIgnore
    fun getDisplayName(): String {
        return name.ifEmpty { method }
    }

    private fun stableId(method: String, requestData: Any?): String {
        val requestDataStr = try {
            when (requestData) {
                null -> ""
                is String -> requestData
                is List<*> -> objectMapper.writeValueAsString(requestData)
                is Map<*, *> -> objectMapper.writeValueAsString(listOf(requestData))
                else -> objectMapper.writeValueAsString(requestData)
            }
        } catch (_: Exception) {
            requestData?.toString() ?: ""
        }

        val md = MessageDigest.getInstance("SHA-256")
        val bytes = (method.trim() + "\n" + requestDataStr.trim()).toByteArray(Charsets.UTF_8)
        val digest = md.digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    @JsonIgnore
    fun getRequestDataString(): String {
        return when (requestData) {
            is String -> requestData as String
            is List<*> -> objectMapper.writeValueAsString(requestData)
            is Map<*, *> -> objectMapper.writeValueAsString(listOf(requestData))
            else -> "[{}]"
        }
    }
}

