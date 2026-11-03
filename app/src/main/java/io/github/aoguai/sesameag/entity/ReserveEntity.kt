package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.ReserveaMap

/**
 * 表示保留项的实体类，包含 ID 和名称。
 *
 * @param i 保留项的 ID
 * @param n 保留项的名称
 */
class ReserveEntity(i: String, n: String) : MapperEntity() {
    init {
        id = i
        name = n
    }

    companion object {
        private class Snapshot(val items: List<ReserveEntity>? = null)
        private val list = java.util.concurrent.atomic.AtomicReference(Snapshot())

        /**
         * 获取包含所有保留项的列表，首次调用时从 ReserveIdMapUtil 初始化。
         * 完整构造后发布快照，读取不参与互斥。
         *
         * @return 包含所有 ReserveEntity 对象的不可变列表
         */
        /**
         * 获取保护地列表（作为MapperEntity列表）
         * 用于Java互操作
         */
        @JvmStatic
        fun getListAsMapperEntity(): List<MapperEntity> = getList()

        @JvmStatic
        fun getList(): List<ReserveEntity> {
            while (true) {
                val current = list.get()
                current.items?.let { return it }
                val items = IdMapManager.getInstance(ReserveaMap::class.java).map.entries.map { (key, value) ->
                    ReserveEntity(key, value)
                }
                if (list.compareAndSet(current, Snapshot(items))) return items
            }
        }

        /**
         * 根据给定的 ID 删除相应的 ReserveEntity 对象。
         * 首次调用 getList 方法以确保列表已初始化。
         *
         * @param id 要删除的保留项 ID
         */
        @JvmStatic
        fun invalidate() {
            list.set(Snapshot())
        }

        @JvmStatic
        fun remove(id: String) {
            while (true) {
                getList()
                val current = list.get()
                val items = current.items ?: continue
                if (list.compareAndSet(current, Snapshot(items.filter { it.id != id }))) return
            }
        }
    }
}

