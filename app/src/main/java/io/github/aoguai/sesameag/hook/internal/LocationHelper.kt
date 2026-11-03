package io.github.aoguai.sesameag.hook.internal

import android.location.Geocoder
import io.github.aoguai.sesameag.entity.AreaCode
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.CityCoordinates
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONObject

object LocationHelper {

    private const val TAG = "LocationInfoHelper"
    private var classLoader: ClassLoader? = null
    private const val LOCATION_KEY = "cached_location"
    private const val LAST_KNOWN_CITY_CODE_KEY = "last_known_city_code"
    private const val FALLBACK_CITY_CODE = "330100"

    fun init(loader: ClassLoader) {
        classLoader = loader
    }

    /**
     * 同步获取缓存的位置信息
     */
    fun getLocation(): JSONObject? {
        return try {
            val map = DataStore.get(LOCATION_KEY, Map::class.java)
            map?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.error(TAG, "读取位置缓存失败: ${e.message}")
            null
        }
    }

    /**
     * ? 新增：挂起函数版本 (推荐 Kotlin 使用)
     * 在后台线程获取位置并返回结果，自动切回原线程
     */
    suspend fun requestLocationSuspend(): JSONObject = withContext(Dispatchers.Default) {
        try {
            val loader = classLoader
            if (loader == null) {
                return@withContext createAndSaveError("ClassLoader 未初始化")
            }

            val lnsctrUtilsClass = Class.forName(
                "com.alipay.mobile.common.lnsctr.LnsctrUtils",
                false,
                loader
            )
            val latitude = lnsctrUtilsClass.getDeclaredMethod("getLatitude").apply {
                isAccessible = true
            }.invoke(null) as? Double
            val longitude = lnsctrUtilsClass.getDeclaredMethod("getLongitude").apply {
                isAccessible = true
            }.invoke(null) as? Double

            if (latitude != null && longitude != null) {
                val locationMap = mutableMapOf<String, Any>(
                    "latitude" to latitude,
                    "longitude" to longitude
                )
                val previous = getLocation()
                if (previous?.optDouble("latitude") == latitude && previous.optDouble("longitude") == longitude) {
                    previous.optString("cityCode").takeIf { it.isNotBlank() }?.let { locationMap["cityCode"] = it }
                }
                saveLocationToDataStore(locationMap)
                JSONObject(locationMap)
            } else {
                createAndSaveError("等待目标应用初始化中...")
            }
        } catch (e: Throwable) {
            Log.error(TAG, "获取经纬度异常: ${e.message}")
            createAndSaveError("获取失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun getCityCodeOrNull(): String? = runBlocking(Dispatchers.IO) { getCityCode() }

    /**
     * 城市码：定位降级链（位置缓存 → Geocoder+城市目录 → 用户配置 → 最近成功）全部失败时，
     * 回落硬编码城市码（杭州，与 AntSportsRpcCall.CITY_CODE 一致），不再抛异常
     */
    @Suppress("DEPRECATION")
    fun requireCityCode(): String =
        getCityCodeOrNull() ?: FALLBACK_CITY_CODE.also { Log.error(TAG, "城市定位全部失败，使用兜底城市码 $it") }

    /**
     * 城市码降级链：位置缓存 → 实时定位（Geocoder+城市目录）→ 用户配置默认城市 → 最近成功城市
     * 全部失败返回 null，调用方跳过城市相关子任务
     */
    @Suppress("DEPRECATION")
    private suspend fun getCityCode(): String? {
        val location = requestLocationSuspend()
        location.optString("cityCode").takeIf { it.isNotBlank() }?.let {
            rememberCityCode(it)
            return it
        }
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        try {
            if (latitude.isFinite() && longitude.isFinite()) {
                val context = ApplicationHook.appContext
                if (context != null && Geocoder.isPresent()) {
                    val address = Geocoder(context, Locale.CHINA).getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    val cityName = address?.locality?.takeIf { it.isNotBlank() } ?: address?.adminArea
                    val cityCode = AreaCode.getList().firstOrNull { it.name == cityName }?.id
                    if (!cityCode.isNullOrBlank()) {
                        saveLocationToDataStore(mapOf("latitude" to latitude, "longitude" to longitude, "cityCode" to cityCode))
                        rememberCityCode(cityCode)
                        return cityCode
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        // 国内 ROM 无 GMS 时 Geocoder 后端不可用，用离线坐标表最近邻解析城市码
        CityCoordinates.nearestCityCode(latitude, longitude)?.let { cityCode ->
            saveLocationToDataStore(mapOf("latitude" to latitude, "longitude" to longitude, "cityCode" to cityCode))
            rememberCityCode(cityCode)
            return cityCode
        }
        Log.error(TAG, "城市定位降级：尝试使用配置默认城市或最近成功城市")
        BaseModel.defaultCityCode.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return DataStore.get(LAST_KNOWN_CITY_CODE_KEY, String::class.java)?.takeIf { it.isNotBlank() }
    }

    private fun rememberCityCode(cityCode: String) {
        try {
            DataStore.put(LAST_KNOWN_CITY_CODE_KEY, cityCode)
        } catch (e: Exception) {
            Log.error(TAG, "保存城市码缓存失败: ${e.message}")
        }
    }

    private fun createAndSaveError(msg: String): JSONObject {
        val map = mapOf("status" to msg)
        saveLocationToDataStore(map)
        return JSONObject(map)
    }

    private fun saveLocationToDataStore(locationMap: Map<String, Any>) {
        try {
            DataStore.put(LOCATION_KEY, locationMap)
        } catch (e: Exception) {
            Log.error(TAG, "保存位置缓存失败: ${e.message}")
        }
    }
}

