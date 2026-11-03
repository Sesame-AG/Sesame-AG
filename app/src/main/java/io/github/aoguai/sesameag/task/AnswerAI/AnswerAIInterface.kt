package io.github.aoguai.sesameag.task.AnswerAI

/**
 * AI答题服务接口
 * 定义了AI答题服务的基本操作，包括获取答案、设置模型等功能
 */
interface AnswerAIInterface {

    /**
     * 设置模型名称
     *
     * @param modelName 模型名称
     */
    fun setModelName(modelName: String) {
        // 默认空实现
    }

    /**
     * 获取模型名称
     *
     * @return 当前使用的模型名称
     */
    fun getModelName(): String {
        // 默认空实现
        return ""
    }

    /**
     * 获取AI回答结果
     *
     * @param text 问题内容
     * @return AI回答结果，如果获取失败返回空字符串
     */
    fun getAnswerStr(text: String): String

    /**
     * 获取AI回答结果，指定模型
     *
     * @param text  问题内容
     * @param model 模型名称
     * @return AI回答结果，如果获取失败返回空字符串
     */
    fun getAnswerStr(text: String, model: String): String

    /**
     * 获取AI答案
     *
     * @param title      问题标题
     * @param answerList 候选答案列表
     * @return 选中的答案索引，如果没有找到合适的答案返回-1
     */
    fun getAnswer(title: String, answerList: List<String>): Int?

    /**
     * 释放资源
     * 实现类应在此方法中清理所有使用的资源
     */
    fun release() {
        // 默认空实现
    }

    companion object {
        /**
         * 获取单例实例
         *
         * @return 默认的AI答题服务实现
         */
        @JvmStatic
        fun getInstance(): AnswerAIInterface = SingletonHolder.INSTANCE
    }

    /**
     * 单例持有者，延迟加载
     */
    private object SingletonHolder {
        val INSTANCE: AnswerAIInterface = object : AnswerAIInterface {
            override fun getAnswerStr(text: String): String = ""

            override fun getAnswerStr(text: String, model: String): String = ""

            override fun getAnswer(title: String, answerList: List<String>): Int? = -1
        }
    }
}

