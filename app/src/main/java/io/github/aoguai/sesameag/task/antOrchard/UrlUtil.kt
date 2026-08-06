package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.util.Log
import java.net.URLDecoder

internal object UrlUtil {
    private const val TAG = "UrlUtil"
    private val QUERY_PARAM_BOUNDARY = listOf(
        "canPullDown=",
        "showOptionMenu=",
        "iepTaskType=",
        "iepTaskSceneCode=",
        "canDoTask=",
        "awardCount=",
        "doneTimes=",
        "taskDoneTimes=",
        "xlightFrom="
    )

    /**
     * 从原始URL中提取指定参数的完整值(支持多层嵌套)
     * @param url 原始URL
     * @param key 要提取的参数名
     * @return 完整的参数值(已解码)
     */
    fun getParamValue(url: String, key: String): String? {
        if (url.isEmpty()) return null

        try {
            val carrier = findQueryCarrier(url, key) ?: return null
            val queryStart = carrier.indexOf("?")
            if (queryStart == -1) return null
            val query = carrier.substring(queryStart + 1)
            val pattern = Regex("(?:^|&)" + Regex.escape(key) + "=([^&]*)")
            val match = pattern.find(query)
            return match?.groupValues?.get(1)?.let { decode(it) }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "getParamValue failed", e)
            return null
        }
    }

    /**
     * 提取URL中指定参数后面的完整嵌套URL
     * @param url 原始URL
     * @param key 触发参数名(比如"url")
     * @return 完整的参数值(已解码)
     */
    fun getFullNestedUrl(url: String, key: String): String? {
        if (url.isEmpty()) return null

        try {
            val carrier = findTextContaining(url, "$key=") ?: return null
            val searchKey = "$key="
            val keyIndex = carrier.indexOf(searchKey)
            if (keyIndex == -1) return null
            val startIndex = keyIndex + searchKey.length
            val remaining = carrier.substring(startIndex)
            var endIndex = remaining.length

            if (remaining.startsWith("http://") || remaining.startsWith("https://") || remaining.startsWith("alipays://")) {
                for (i in remaining.indices) {
                    if (remaining[i] == '&') {
                        val afterAmp = remaining.substring(i + 1)
                        if (QUERY_PARAM_BOUNDARY.any { afterAmp.startsWith(it) }) {
                            endIndex = i
                            break
                        }
                    }
                }
            } else {
                val ampIndex = remaining.indexOf("&")
                if (ampIndex != -1) {
                    endIndex = ampIndex
                }
            }

            val value = remaining.substring(0, endIndex)
            var result = value
            repeat(5) {
                if (result.startsWith("http://") || result.startsWith("https://") || result.startsWith("alipays://")) {
                    return result
                }
                val temp = decode(result)
                if (temp == result) {
                    return result
                }
                result = temp
            }
            return result
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "getFullNestedUrl failed", e)
            return null
        }
    }

    /**
     * 从完整URL中提取指定参数
     * @param fullUrl 完整的URL
     * @param key 参数名
     * @return 参数值
     */
    fun extractParamFromUrl(fullUrl: String, key: String): String? {
        if (fullUrl.isEmpty()) return null

        try {
            val carrier = findQueryCarrier(fullUrl, key) ?: return null
            val queryStart = carrier.indexOf("?")
            if (queryStart == -1) return null
            val query = carrier.substring(queryStart + 1)
            val pattern = Regex("(?:^|&)" + Regex.escape(key) + "=([^&]*)")
            val match = pattern.find(query)
            return match?.groupValues?.get(1)?.let { decode(it) }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "extractParamFromUrl failed", e)
            return null
        }
    }

    /** 安全解码URL。 */
    private fun decode(url: String): String =
        try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }

    private fun findQueryCarrier(url: String, key: String): String? {
        var current = url
        repeat(5) {
            if (hasQueryParam(current, key)) {
                return current
            }
            val temp = decode(current)
            if (temp == current) {
                return null
            }
            current = temp
        }
        return if (hasQueryParam(current, key)) current else null
    }

    private fun hasQueryParam(url: String, key: String): Boolean {
        val queryStart = url.indexOf("?")
        if (queryStart == -1) return false
        val query = url.substring(queryStart + 1)
        return Regex("(?:^|&)" + Regex.escape(key) + "=").containsMatchIn(query)
    }

    private fun findTextContaining(text: String, target: String): String? {
        var current = text
        repeat(5) {
            if (current.contains(target)) {
                return current
            }
            val temp = decode(current)
            if (temp == current) {
                return null
            }
            current = temp
        }
        return if (current.contains(target)) current else null
    }
}
