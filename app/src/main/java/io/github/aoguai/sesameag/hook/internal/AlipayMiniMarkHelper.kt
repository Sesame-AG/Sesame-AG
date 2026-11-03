package io.github.aoguai.sesameag.hook.internal

import io.github.aoguai.sesameag.util.Log

/**
 * 目标应用小程序游戏获取alipayminimark
 * 用于调用目标应用的 H5HttpUtils.getAlipayMiniMark 方法
 */
object AlipayMiniMarkHelper {
    private const val TAG = "AlipayMiniMarkHelper"
    private var classLoader: ClassLoader? = null

    /**
     * 初始化 AlipayMiniMarkHelper
     * @param loader 应用类加载器
     */
    fun init(loader: ClassLoader) {
        classLoader = loader
        Log.record(TAG, "AlipayMiniMarkHelper 初始化完成")
    }

    /**
     * 获取目标应用小程序标记
     * 通过调用 H5HttpUtils.getAlipayMiniMark 方法获取小程序标记
     *
     * @param str 游戏appid
     * @param str2 游戏版本号
     * @return 小程序标记字符串，失败返回空字符串
     */
    fun getAlipayMiniMark(str: String, str2: String): String {
        try {
            val loader = classLoader ?: return ""
            val h5HttpUtilsClass = Class.forName("com.alipay.mobile.nebula.util.H5HttpUtils", false, loader)
            val result = h5HttpUtilsClass.getDeclaredMethod(
                "getAlipayMiniMark",
                String::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }.invoke(null, str, str2) as? String
            return result ?: ""
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "获取alipayminimark失败: ${e.message}", e)
            return ""
        }
    }
}

