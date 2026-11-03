package io.github.aoguai.sesameag.hook.server.handlers

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper

@JsonIgnoreProperties(ignoreUnknown = true)
data class RpcRequest(
    val methodName: String = "",
    // 允许接收 String 或 JSON Object
    val requestData: Any? = null
) {
    /**
     * 将 requestData 安全转换为字符串
     */
    fun getRequestDataString(mapper: ObjectMapper): String {
        return when (requestData) {
            is String -> requestData // 如果传的是字符串，直接用
            null -> ""
            else -> mapper.writeValueAsString(requestData) // 如果是对象/数组，转回 JSON 串
        }
    }
}
