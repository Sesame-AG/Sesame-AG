package io.github.aoguai.sesameag.util.maps

/**
 * 合种ID映射工具类。
 */
class CooperateMap : IdMapManager() {
    protected override fun thisFileName(): String {
        return "cooperateMap.json" // 合种ID映射文件名
    }
}

