package io.github.aoguai.sesameag.util.maps

class ReserveaMap : IdMapManager() {
    protected override fun thisFileName(): String {
        return "ReserveaMap.json" // 保护地ID映射表
    }
}

