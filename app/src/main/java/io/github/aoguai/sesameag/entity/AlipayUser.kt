package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.friend.FriendSelectionResolver
import io.github.aoguai.sesameag.util.maps.UserMap

/**
 * 支付宝用户实体类
 *
 * **迁移说明**:
 * - 简化为Kotlin类，移除冗余代码
 * - 使用函数式接口和默认参数
 * - 优化异常处理
 *
 * @property id 用户ID
 * @property name 用户名称
 */
class AlipayUser(id: String, name: String) : MapperEntity() {

    init {
        this.id = id
        this.name = name
    }

    companion object {
        /**
         * 获取所有用户列表（无过滤）
         */
        @JvmStatic
        fun getList(): List<AlipayUser> {
            return getList { true }
        }

        /**
         * 获取仅互关且排除当前账号的好友列表。
         */
        @JvmStatic
        fun getFriendList(): List<AlipayUser> {
            return FriendSelectionResolver.availableFriendOptions()
        }

        /**
         * 获取符合过滤条件的用户列表
         *
         * @param filterFunc 过滤函数，用于筛选用户
         * @return 符合条件的用户列表
         */
        @JvmStatic
        fun getList(filterFunc: Filter): List<AlipayUser> {
            val list = ArrayList<AlipayUser>()
            val userIdMap = UserMap.getUserMap()
            
            for ((key, userEntity) in userIdMap) {
                try {
                    if (filterFunc.apply(userEntity)) {
                        list.add(AlipayUser(key, userEntity.fullName))
                    }
                } catch (t: Throwable) {
                    Log.printStackTrace(t)
                }
            }
            return list
        }
    }

    /**
     * 过滤接口，用于筛选符合条件的用户
     */
    @FunctionalInterface
    fun interface Filter {
        /**
         * 判断给定用户是否符合条件
         *
         * @param user 用户实体对象
         * @return 符合条件返回true，否则返回false
         */
        fun apply(user: UserEntity): Boolean
    }
}

