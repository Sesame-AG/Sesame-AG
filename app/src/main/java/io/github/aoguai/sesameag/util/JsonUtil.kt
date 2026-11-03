package io.github.aoguai.sesameag.util

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.type.TypeFactory
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.*

/**
 * JSON工具类，提供JSON序列化和反序列化功能
 *
 * **迁移说明**:
 * - 使用Kotlin的对象单例和扩展函数
 * - 保持所有方法的Java兼容性 (@JvmStatic/@JvmField)
 * - 使用函数式接口简化异常处理
 */
object JsonUtil {

    private const val TAG = "JsonUtil"

    private val MAPPER = ObjectMapper()

    @JvmField
    val TYPE_FACTORY: TypeFactory = TypeFactory.defaultInstance()

    @JvmField
    val JSON_FACTORY: JsonFactory = JsonFactory()

    init {
        // 配置 ObjectMapper
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        MAPPER.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        MAPPER.setTimeZone(TimeZone.getDefault())
        MAPPER.dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    @JvmStatic
    fun copyMapper(): ObjectMapper = MAPPER.copy()

    // ==================== JSON格式化 ====================

    @JvmStatic
    fun formatJson(`object`: Any): String {
        return try {
            when (`object`) {
                is JSONObject -> `object`.toString(4)
                else -> execute { MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(`object`) }
            }
        } catch (e: Exception) {
            Log.runtime(TAG, "formatJson err:")
            Log.printStackTrace(e)
            execute { MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(`object`) }
        }
    }

    @JvmStatic
    fun formatJson(`object`: Any, pretty: Boolean): String {
        return try {
            when (`object`) {
                is JSONObject -> {
                    if (pretty) `object`.toString(4) else `object`.toString()
                }
                else -> {
                    if (pretty) {
                        execute { MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(`object`) }
                    } else {
                        execute { MAPPER.writeValueAsString(`object`) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.runtime(TAG, "formatJson err:")
            Log.printStackTrace(e)
            execute { MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(`object`) }
        }
    }

    // ==================== JSON解析器 ====================

    @JvmStatic
    fun getJsonParser(body: String): JsonParser {
        return execute { JSON_FACTORY.createParser(body) }
    }

    // ==================== parseObject - String ====================

    @JvmStatic
    fun <T> parseObject(body: String, type: Type): T {
        return parseObjectInternal { MAPPER.readValue(body, TYPE_FACTORY.constructType(type)) }
    }

    @JvmStatic
    fun <T> parseObject(body: String, javaType: JavaType): T {
        return parseObjectInternal { MAPPER.readValue(body, javaType) }
    }

    @JvmStatic
    fun <T> parseObject(body: String, valueTypeRef: TypeReference<T>): T {
        return parseObjectInternal { MAPPER.readValue(body, valueTypeRef) }
    }

    @JvmStatic
    fun <T> parseObject(body: String, clazz: Class<T>): T {
        return parseObjectInternal { MAPPER.readValue(body, clazz) }
    }

    // ==================== parseObject - JsonParser ====================

    @JvmStatic
    fun <T> parseObject(jsonParser: JsonParser, type: Type): T {
        return parseObjectInternal { MAPPER.readValue(jsonParser, TYPE_FACTORY.constructType(type)) }
    }

    @JvmStatic
    fun <T> parseObject(jsonParser: JsonParser, javaType: JavaType): T {
        return parseObjectInternal { MAPPER.readValue(jsonParser, javaType) }
    }

    @JvmStatic
    fun <T> parseObject(jsonParser: JsonParser, valueTypeRef: TypeReference<T>): T {
        return parseObjectInternal { MAPPER.readValue(jsonParser, valueTypeRef) }
    }

    @JvmStatic
    fun <T> parseObject(jsonParser: JsonParser, clazz: Class<T>): T {
        return parseObjectInternal { MAPPER.readValue(jsonParser, clazz) }
    }

    // ==================== parseObject - Object ====================

    @JvmStatic
    fun <T> parseObject(bean: Any, type: Type): T {
        return parseObjectInternal { MAPPER.convertValue(bean, TYPE_FACTORY.constructType(type)) }
    }

    @JvmStatic
    fun <T> parseObject(bean: Any, javaType: JavaType): T {
        return parseObjectInternal { MAPPER.convertValue(bean, javaType) }
    }

    @JvmStatic
    fun <T> parseObject(bean: Any, valueTypeRef: TypeReference<T>): T {
        return parseObjectInternal { MAPPER.convertValue(bean, valueTypeRef) }
    }

    @JvmStatic
    fun <T> parseObject(bean: Any, clazz: Class<T>): T {
        return parseObjectInternal { MAPPER.convertValue(bean, clazz) }
    }

    // ==================== 字段解析 ====================

    @JvmStatic
    fun parseString(body: String, field: String): String? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.asText()
        }
    }

    @JvmStatic
    fun parseInteger(body: String, field: String): Int? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.asInt()
        }
    }

    @JvmStatic
    fun parseIntegerList(body: String, field: String): List<Int>? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.let { MAPPER.convertValue(it, object : TypeReference<List<Int>>() {}) }
        }
    }

    @JvmStatic
    fun parseBoolean(body: String, field: String): Boolean? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.asBoolean()
        }
    }

    @JvmStatic
    fun parseShort(body: String, field: String): Short? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.asInt()?.toShort()
        }
    }

    @JvmStatic
    fun parseByte(body: String, field: String): Byte? {
        return execute {
            val node = MAPPER.readTree(body).get(field)
            node?.asInt()?.toByte()
        }
    }

    // ==================== 列表解析 ====================

    @JvmStatic
    fun <T> parseList(body: String, clazz: Class<T>): List<T> {
        return parseObjectInternal {
            MAPPER.readValue(body, TYPE_FACTORY.constructCollectionType(ArrayList::class.java, clazz))
        }
    }

    // ==================== JsonNode ====================

    @JvmStatic
    fun toNode(json: String?): JsonNode? {
        return if (json == null) null else execute { MAPPER.readTree(json) }
    }

    // ==================== 路径获取 ====================

    @JvmStatic
    fun getValueByPath(jsonObject: JSONObject, path: String): String {
        val value = getValueByPathObject(jsonObject, path)
        return value?.toString() ?: ""
    }

    @JvmStatic
    fun getValueByPathObject(jsonObject: JSONObject, path: String): Any? {
        val parts = path.split(".")
        return try {
            var current: Any = jsonObject
            for (part in parts) {
                current = when (current) {
                    is JSONObject -> current.get(part)
                    is JSONArray -> {
                        val index = part.replace("\\D".toRegex(), "").toInt()
                        current.get(index)
                    }
                    else -> JSONObject(current.toString()).get(part)
                }
            }
            current
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 安全解析 ====================

    @JvmStatic
    fun parseJSONObject(jsonStr: String?): JSONObject {
        return try {
            if (jsonStr.isNullOrBlank()) {
                Log.runtime(TAG, "收到空响应，可能是网络异常或服务端错误")
                JSONObject()
            } else {
                JSONObject(jsonStr)
            }
        } catch (e: Exception) {
            Log.runtime(TAG, "JSON解析失败: ${e.message}")
            Log.runtime(TAG, "原始响应: ${if (jsonStr != null && jsonStr.length > 200) jsonStr.substring(0, 200) + "..." else jsonStr}")
            JSONObject()
        }
    }

    @JvmStatic
    fun parseJSONObjectOrNull(jsonStr: String?): JSONObject? {
        return try {
            if (jsonStr.isNullOrBlank()) {
                null
            } else {
                JSONObject(jsonStr)
            }
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun parseJSONArray(jsonStr: String?): JSONArray {
        return try {
            if (jsonStr.isNullOrBlank()) {
                Log.runtime(TAG, "收到空响应，可能是网络异常或服务端错误")
                JSONArray()
            } else {
                JSONArray(jsonStr)
            }
        } catch (e: Exception) {
            Log.runtime(TAG, "JSON数组解析失败: ${e.message}")
            JSONArray()
        }
    }

    @JvmStatic
    fun parseJSONArrayOrNull(jsonStr: String?): JSONArray? {
        return try {
            if (jsonStr.isNullOrBlank()) {
                null
            } else {
                JSONArray(jsonStr)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== JSONArray 转换 ====================

    @JvmStatic
    fun jsonArrayToList(jsonArray: JSONArray): List<String> {
        val list = ArrayList<String>()
        for (i in 0 until jsonArray.length()) {
            try {
                list.add(jsonArray.getString(i))
            } catch (e: Exception) {
                Log.printStackTrace(e)
                list.add("")
            }
        }
        return list
    }

    // ==================== 内部方法 ====================

    private fun <T> parseObjectInternal(action: JsonAction<T>): T {
        return execute(action)
    }

    private fun <T> execute(action: JsonAction<T>): T {
        return try {
            action.execute()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * 函数式接口，用于执行 JSON 操作
     */
    @FunctionalInterface
    private fun interface JsonAction<T> {
        fun execute(): T
    }
}

