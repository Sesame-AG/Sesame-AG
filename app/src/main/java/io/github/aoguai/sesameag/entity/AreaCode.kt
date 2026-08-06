package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 区域代码实体类
 *
 * **迁移说明**:
 * - 简化为Kotlin类，移除冗余代码
 * - 优化异常处理和空安全
 * - 使用lazy延迟加载列表
 *
 * @property id 区域代码
 * @property name 区域名称
 */
class AreaCode(id: String, name: String) : MapperEntity() {

    init {
        this.id = id
        this.name = name
    }

    companion object {
        private const val TAG = "AreaCode"

        @Volatile
        private var list: List<AreaCode>? = null

        /**
         * 获取区域代码列表
         * 如果列表尚未初始化，则从文件中读取城市代码
         * 如果读取失败，则使用默认城市代码
         */
        @JvmStatic
        @Throws(JSONException::class)
        fun getList(): List<AreaCode> {
            if (list == null) {
                synchronized(this) {
                    if (list == null) {
                        val cityCode = Files.readFromFile(Files.getCityCodeFile())
                        val ja = parseCityCode(cityCode)
                        val newList = ArrayList<AreaCode>()
                        for (i in 0 until ja.length()) {
                            try {
                                val jo = ja.getJSONObject(i)
                                newList.add(AreaCode(jo.getString("cityCode"), jo.getString("cityName")))
                            } catch (e: JSONException) {
                                Log.printStackTrace(TAG, e)
                            }
                        }
                        list = newList
                    }
                }
            }
            return list ?: emptyList()
        }

        /**
         * 解析城市代码字符串为JSONArray
         * 如果解析失败，则返回默认的城市代码JSONArray
         */
        @Throws(JSONException::class)
        private fun parseCityCode(cityCode: String): JSONArray {
            return try {
                JSONArray(cityCode)
            } catch (e: JSONException) {
                // 解析失败，使用默认城市代码
                Log.runtime(TAG, "parseCityCode failed with error message: ${e.message}\nNow use default cities.")
                JSONArray().apply {
                    put(JSONObject().put("cityCode", "350100").put("cityName", "福州市"))
                    put(JSONObject().put("cityCode", "440100").put("cityName", "广州市"))
                    put(JSONObject().put("cityCode", "330100").put("cityName", "杭州市"))
                    put(JSONObject().put("cityCode", "370100").put("cityName", "济南市"))
                    put(JSONObject().put("cityCode", "320100").put("cityName", "南京市"))
                    put(JSONObject().put("cityCode", "430100").put("cityName", "长沙市"))
                }
            }
        }
    }
}

