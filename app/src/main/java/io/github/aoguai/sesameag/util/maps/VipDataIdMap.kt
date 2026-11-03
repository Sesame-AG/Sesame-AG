package io.github.aoguai.sesameag.util.maps

/**
 * 用于保存 VIP 抓包得到的私有数据（按用户隔离）。
 *
 * 每个用户一个 vipdata.json，内容为简单的 key-value 映射，例如：
 * {
 *   "AntFarmReferToken": "xxxx"
 * }
 */
class VipDataIdMap : IdMapManager() {
    protected override fun thisFileName(): String {
        return "vipdata.json"
    }
}

