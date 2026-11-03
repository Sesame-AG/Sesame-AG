package io.github.aoguai.sesameag.util.maps

/**
 * 沙滩ID映射工具类。
 */
class BeachMap : IdMapManager() {
    protected override fun thisFileName(): String {
        return "BeachMap.json" // 海洋ID映射文件
    }
}

