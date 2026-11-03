package io.github.aoguai.sesameag.model

/**
 * 模型类型枚举
 *
 * @property code 类型代码
 * @property typeName 类型名称
 */
enum class ModelType(val code: Int, val typeName: String) {
    /** 普通模块 */
    NORMAL(0, "普通模块"),
    /** 任务模块 */
    TASK(1, "任务模块");

    companion object {
        private val MAP: Map<Int, ModelType> = values().associateBy { it.code }

        /**
         * 根据代码获取模型类型
         *
         * @param code 类型代码
         * @return 对应的模型类型，如果不存在则返回null
         */
        @JvmStatic
        fun getByCode(code: Int?): ModelType? = MAP[code]
    }
}

