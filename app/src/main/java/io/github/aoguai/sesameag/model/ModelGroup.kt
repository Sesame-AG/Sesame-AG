package io.github.aoguai.sesameag.model

/**
 * 模型分组枚举
 *
 * @property code 分组代码
 * @property groupName 分组名称
 * @property icon 图标路径
 */
enum class ModelGroup(
    val code: String,
    val groupName: String,
    val icon: String
) {
    /** 基础 */
    BASE("BASE", "基础", "svg/group/base.svg"),
    /** 森林 */
    FOREST("FOREST", "森林", "svg/group/forest.svg"),
    /** 神奇物种 */
    DODO("DODO", "神奇物种", "svg/group/forest.svg"),
    /** 庄园 */
    FARM("FARM", "庄园", "svg/group/farm.svg"),
    /** 新村 */
    STALL("STALL", "新村", "svg/group/stall.svg"),
    /** 农场 */
    ORCHARD("ORCHARD", "农场", "svg/group/orchard.svg"),
    /** 金豆夺宝 */
    GOLDEN_BEAN("GOLDEN_BEAN", "金豆夺宝", "svg/group/golden_bean.svg"),
    /** 福气鱼池 */
    FISHPOND("FISHPOND", "福气鱼池", "svg/group/orchard.svg"),
    /** 运动 */
    SPORTS("SPORTS", "运动", "svg/group/sports.svg"),
    /** 会员 */
    MEMBER("MEMBER", "会员", "svg/group/member.svg"),
    /** 网商银行 */
    MYBANK("MYBANK", "网商银行", "svg/group/member.svg"),
    /** 芝麻信用 */
    SESAME_CREDIT("SESAME_CREDIT", "芝麻信用", "svg/group/member.svg"),
    /** 其他 */
    OTHER("OTHER", "其他", "svg/group/other.svg");

    companion object {
        private val MAP: Map<String, ModelGroup> = values().associateBy { it.code }

        /**
         * 根据代码获取模型分组
         *
         * @param code 分组代码
         * @return 对应的模型分组，如果不存在则返回null
         */
        @JvmStatic
        fun getByCode(code: String?): ModelGroup? = MAP[code]

        /**
         * 根据代码获取分组名称
         *
         * @param code 分组代码
         * @return 分组名称，如果不存在则返回null
         */
        @JvmStatic
        fun getName(code: String?): String? = getByCode(code)?.groupName
    }

    /**
     * 获取分组名称（实例方法，用于Java互操作）
     */
    fun getName(): String = groupName
}

