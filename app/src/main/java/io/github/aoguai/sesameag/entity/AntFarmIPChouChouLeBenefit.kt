package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.task.antFarm.ChouChouLe
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlin.collections.iterator

class AntFarmIPChouChouLeBenefit(i: String, n: String) : MapperEntity() {
    init {
        id = i
        name = n
    }

    var limitCount: Int = 0
    var cent: Int = 0

    companion object {
        @JvmStatic
        fun getList(): List<AntFarmIPChouChouLeBenefit> {
            val list: MutableList<AntFarmIPChouChouLeBenefit> = ArrayList()
            val uid = UserMap.currentUid
            if (uid.isNullOrEmpty()) return list
            
            val data = ChouChouLe.loadData(uid)
            for ((key, value) in data.shopItems) {
                // 解析存储格式 "名称|限购次数|所需碎片"
                val split = value.split("|")
                val rawName = split[0]
                val limitCount = split.getOrNull(1)?.toIntOrNull() ?: 0
                val cent = split.getOrNull(2)?.toIntOrNull() ?: 0
                val displayParts = listOf(
                    cent.takeIf { it > 0 }?.let { "${it}碎片" }.orEmpty(),
                    limitCount.takeIf { it > 0 }?.let { "限购${it}次" }.orEmpty()
                ).filter { it.isNotBlank() }
                val displayName = if (displayParts.isEmpty()) rawName else "$rawName[${displayParts.joinToString(" | ")}]"
                val benefit = AntFarmIPChouChouLeBenefit(key, displayName)
                if (split.size >= 3) {
                    benefit.limitCount = limitCount
                    benefit.cent = cent
                }
                list.add(benefit)
            }
            return list
        }
    }
}
