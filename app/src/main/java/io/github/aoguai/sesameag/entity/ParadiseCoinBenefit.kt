package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.ParadiseCoinBenefitIdMap

/**
 * 表示乐园币权益的实体类，包含 ID 和名称。
 *
 * @param i 权益的 ID
 * @param n 权益的名称
 */
class ParadiseCoinBenefit(i: String, n: String) : MapperEntity() {
    init {
        id = i
        name = n
    }

    companion object {
        /**
         * 获取包含所有乐园币权益的列表
         *
         * @return 包含所有 ParadiseCoinBenefit 对象的列表
         */
        @JvmStatic
        fun getList(): List<ParadiseCoinBenefit> {
            val idSet = IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java).map
            return idSet.entries.map { (key, value) ->
                ParadiseCoinBenefit(key, value)
            }
        }
    }
}

