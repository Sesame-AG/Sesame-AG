package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.SesameGiftMap

class SesameGift(i: String, n: String) : MapperEntity() {
    init {
        id = i; name = n
    }

    companion object {
        fun getList(): List<SesameGift> {
            return IdMapManager.getInstance(SesameGiftMap::class.java).map
                .map { (key, value) -> SesameGift(key, value) }
        }
    }
}
