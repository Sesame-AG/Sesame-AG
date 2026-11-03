package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.model.ModelField

/**
 * String类型字段类
 * 该类用于表示字符串值字段，点击按钮弹出编辑对话框
 */
open class StringModelField(code: String, name: String, value: String) : ModelField<String>(code, name, null) {

    init {
        // Avoid calling subclass normalization before subclass constructor properties are initialized.
        val initialValue = value.trim()
        defaultValue = initialValue
        this.value = initialValue
    }

    override fun getType(): String = "STRING"

    override fun getConfigValue(): String? = value

    protected open fun normalizeValue(rawValue: String): String {
        return rawValue.trim()
    }

    override fun setObjectValue(objectValue: Any?) {
        if (objectValue == null) {
            reset()
            return
        }
        value = normalizeValue(objectValue.toString())
    }

    override fun setConfigValue(configValue: String?) {
        setObjectValue(configValue)
    }

    class IntervalStringModelField(
        code: String,
        name: String,
        value: String,
        private val minLimit: Int,
        private val maxLimit: Int
    ) : StringModelField(code, name, value) {

        init {
            require(minLimit <= maxLimit) { "minLimit must be <= maxLimit" }
            setObjectValue(value)
        }

        override fun normalizeValue(rawValue: String): String {
            val trimmed = rawValue.trim()
            if (trimmed.isBlank()) {
                return defaultValue ?: maxLimit.toString()
            }

            val parts = trimmed.split("-")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            return when (parts.size) {
                1 -> clamp(parts[0].toIntOrNull() ?: maxLimit).toString()
                2 -> {
                    val first = clamp(parts[0].toIntOrNull() ?: minLimit)
                    val second = clamp(parts[1].toIntOrNull() ?: maxLimit)
                    val rangeMin = minOf(first, second)
                    val rangeMax = maxOf(first, second)
                    if (rangeMin == rangeMax) {
                        rangeMin.toString()
                    } else {
                        "$rangeMin-$rangeMax"
                    }
                }
                else -> defaultValue ?: maxLimit.toString()
            }
        }

        private fun clamp(value: Int): Int = value.coerceIn(minLimit, maxLimit)
    }
}

