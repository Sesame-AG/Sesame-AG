package io.github.aoguai.sesameag.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 表示一个用户实体，包含用户的基本信息。
 * 使用 data class 自动处理 getter/equals/hashCode
 */
data class UserEntity(
    /** 用户 ID */
    val userId: String?,
    /** 用户的账号 */
    val account: String?,
    /** 用户的好友状态 */
    val friendStatus: Int?,
    /** 用户的真实姓名 */
    val realName: String?,
    /** 用户的昵称 */
    val nickName: String?,
    /** 用户的备注名 */
    val remarkName: String?
) {
    /**
     * 用于显示的名字，优先使用备注名，若无则使用昵称
     * 优化：使用了 lazy 委托，只有在第一次访问时才计算，节省初始化性能（如果列表很长）
     * 或者直接作为属性初始化也可以。
     */
    val showName: String = if (!remarkName.isNullOrEmpty()) remarkName else (nickName ?: "")

    /**
     * 用于显示的遮掩名字，真实姓名首字母被遮掩
     */
    val maskName: String

    /**
     * 用户的全名，格式为：显示名字 | 真实姓名 (账号)
     */
    val fullName: String

    // 初始化块，处理复杂的格式化逻辑
//    init {
//        // 处理遮掩名称
//        // 修复逻辑：处理 realName 为 null 的情况，避免显示 "null"
//        val safeRealName = realName ?: ""
//        val maskNameTmp = if (safeRealName.length > 1) {
//            "*" + safeRealName.substring(1)
//        } else {
//            safeRealName
//        }
//
//        // 格式化输出
//        // 修复逻辑：如果 maskNameTmp 为空，不要拼接 "|"，或者根据你的需求保留格式
//        // 原逻辑是直接拼接，这里保持原逻辑但去除了 "null" 字符串
//        this.maskName = "$showName|$maskNameTmp"
//
//        // 修复逻辑：处理 account 为 null 的情况
//        val safeAccount = account ?: ""
//        this.fullName = "$showName|$safeRealName($safeAccount)"
//    }


    init {
        val safeRealName = realName ?: ""

        // 智能 MaskName：如果没实名，就不显示分隔符后面的东西
        this.maskName = if (safeRealName.isNotEmpty()) {
            val masked = if (safeRealName.length > 1) "*${safeRealName.substring(1)}" else safeRealName
            "$showName|$masked"
        } else {
            showName // 只有显示名
        }

        // 智能 FullName：如果没有账号或实名，调整格式
        val sb = StringBuilder(showName)
        if (safeRealName.isNotEmpty() || !account.isNullOrEmpty()) {
            sb.append("|")
            sb.append(safeRealName)
            if (!account.isNullOrEmpty()) {
                sb.append("($account)")
            }
        }
        this.fullName = sb.toString()
    }

    /**
     * 用户 DTO 类，用于传输数据的简化版本。
     * 加上 @JsonIgnoreProperties(ignoreUnknown = true) 增加鲁棒性
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserDto(
        var userId: String? = null,
        var account: String? = null,
        var friendStatus: Int? = null,
        var realName: String? = null,
        var nickName: String? = null,
        var remarkName: String? = null
    ) {
        /**
         * 将 UserDto 转换为 UserEntity 实体。
         */
        fun toEntity(): UserEntity {
            return UserEntity(
                userId = userId,
                account = account,
                friendStatus = friendStatus,
                realName = realName,
                nickName = nickName,
                remarkName = remarkName
            )
        }
    }
}
