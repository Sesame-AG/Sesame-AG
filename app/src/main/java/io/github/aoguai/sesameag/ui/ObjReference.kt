package io.github.aoguai.sesameag.ui

/**
 * 非线程安全的引用包装器。
 * 提供了一个泛型对象的非线程安全访问和修改。
 * 
 * @param T 泛型类型参数
 * @property obj 被包装的对象
 */
class ObjReference<T>(
    private var obj: T? = null
) {
    /**
     * 检查对象是否存在。
     * @return 如果对象不为空，返回true
     */
    fun has(): Boolean = obj != null

    /**
     * 获取被包装的对象。
     * @return 被包装的对象
     */
    fun get(): T? = obj

    /**
     * 设置被包装的对象。
     * 如果当前对象与传入对象相同，或当前对象为null且传入对象不为null，则设置对象并返回true。
     * 
     * @param obj 新的对象
     * @return 如果设置成功，返回true
     */
    fun set(obj: T?): Boolean {
        if (this.obj == obj) {
            return true
        }
        if (this.obj != null) {
            return false
        }
        this.obj = obj
        return true
    }

    /**
     * 强制设置被包装的对象。
     * 无论当前对象是什么，都会设置为传入的对象。
     * 
     * @param obj 新的对象
     */
    fun setForce(obj: T?) {
        this.obj = obj
    }

    /**
     * 删除被包装的对象。
     */
    fun del() {
        this.obj = null
    }

    /**
     * 如果被包装的对象与传入对象相同，则删除。
     * 
     * @param obj 要比较的对象
     */
    fun delIfEquals(obj: T?) {
        if (this.obj == obj) {
            this.obj = null
        }
    }
}

