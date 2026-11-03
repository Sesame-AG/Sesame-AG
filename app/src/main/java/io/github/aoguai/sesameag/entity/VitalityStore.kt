package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.VitalityRewardsMap

class VitalityStore(i: String, n: String) : MapperEntity() {
    init {
        this.id = i
        this.name = n
    }

    enum class ExchangeStatus(val nickName: String) {
        NO_ENOUGH_POINT("活力值不足"),
        NO_ENOUGH_STOCK("库存量不足"),
        REACH_LIMIT("兑换达上限"),
        SECKILL_NOT_BEGIN("秒杀未开始"),
        SECKILL_HAS_END("秒杀已结束"),
        HAS_NEVER_EXPIRE_DRESS("不限时皮肤");
    }

    companion object {
        private var idNameMap: MutableMap<String?, String?>? = null

        @JvmStatic
        val list: MutableList<VitalityStore>
            get() {
                val list: MutableList<VitalityStore> = ArrayList()
                val instance = IdMapManager.getInstance(VitalityRewardsMap::class.java)
                val entries = instance?.map?.entries ?: emptySet()

                for (entry in entries) {
                    list.add(VitalityStore(entry.key!!, entry.value!!))
                }
                return list
            }

        @JvmStatic
        fun getNameById(id: String?): String? {
            if (idNameMap == null) {
                idNameMap = HashMap()
                for (store in list) {
                    idNameMap!!.put(store.id, store.name)
                }
            }
            return idNameMap!![id]
        }
    }
}

