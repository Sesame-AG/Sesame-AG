package io.github.aoguai.sesameag.ui.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.aoguai.sesameag.entity.RpcDebugEntity
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.security.MessageDigest

internal data class RpcDebugLoadResult(
    val items: List<RpcDebugEntity>,
    val hasConfigSource: Boolean
)

internal class RpcDebugConfigStore {

    private val configFile = Files.child(Files.MAIN_DIR, ROOT_CONFIG_FILE_NAME)
    private val objectMapper = JsonMapper.builder()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .build()

    fun loadItems(): RpcDebugLoadResult {
        return try {
            loadFromRootConfig() ?: RpcDebugLoadResult(emptyList(), false)
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            RpcDebugLoadResult(emptyList(), false)
        }
    }

    fun saveItems(items: List<RpcDebugEntity>) {
        try {
            val normalizedItems = normalizeItems(items)
            val jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedItems)
            Files.write2File(jsonString, configFile)
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        }
    }

    fun normalizeItems(items: List<RpcDebugEntity>): List<RpcDebugEntity> {
        return items
            .filter { it.method.isNotBlank() }
            .map(::normalizeItem)
    }

    fun normalizeItem(item: RpcDebugEntity): RpcDebugEntity {
        val normalizedMethod = item.method.trim()
        val normalizedId = stableId(normalizedMethod, item.requestData)
        val normalizedName = item.name.ifBlank { normalizedMethod }
        val normalizedDailyCount = if (item.scheduleEnabled) item.dailyCount.coerceAtLeast(0) else 0
        return item.copy(
            id = normalizedId,
            name = normalizedName,
            method = normalizedMethod,
            dailyCount = normalizedDailyCount
        )
    }

    fun stableId(method: String, requestData: Any?): String {
        val requestDataStr = try {
            when (requestData) {
                null -> ""
                is String -> requestData
                is Map<*, *> -> objectMapper.writeValueAsString(listOf(requestData))
                is List<*> -> objectMapper.writeValueAsString(requestData)
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

    private fun loadFromRootConfig(): RpcDebugLoadResult? {
        if (!configFile.exists()) {
            return null
        }

        val text = Files.readFromFile(configFile).trim()
        if (text.isBlank()) {
            return RpcDebugLoadResult(emptyList(), true)
        }

        if (!text.startsWith("[")) {
            Log.e(TAG, "$ROOT_CONFIG_FILE_NAME 仅支持数组格式，已忽略旧格式内容")
            return RpcDebugLoadResult(emptyList(), true)
        }

        return try {
            val items = objectMapper.readValue(text, object : TypeReference<List<RpcDebugEntity>>() {})
            RpcDebugLoadResult(normalizeItems(items), true)
        } catch (e: Exception) {
            Log.e(TAG, "Parse $ROOT_CONFIG_FILE_NAME failed", e)
            RpcDebugLoadResult(emptyList(), true)
        }
    }

    private companion object {
        private const val TAG = "RpcDebugConfigStore"
        private const val ROOT_CONFIG_FILE_NAME = "rpcRequest.json"
    }
}
