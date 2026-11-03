package io.github.aoguai.sesameag.util

import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.*

/**
 * 循环FIFO队列实现
 * 
 * 源码来自：Apache Commons Collections 4.4
 * 少量定制
 * 
 * @param E 元素类型
 * @property maxElements 队列容量
 */
class CircularFifoQueue<E>(
    private val maxElements: Int
) : AbstractCollection<E>(), Queue<E>, Serializable {

    /**
     * 底层存储数组
     */
    @Transient
    private var elements: Array<Any?>

    /**
     * 第一个（最旧）队列元素的数组索引
     */
    @Transient
    private var start = 0

    /**
     * 最后一个队列元素之后的数组位置的索引（模maxElements）
     */
    @Transient
    private var end = 0

    /**
     * 标志队列当前是否已满
     */
    @Transient
    private var full = false

    init {
        require(maxElements > 0) { "The size must be greater than 0" }
        @Suppress("UNCHECKED_CAST")
        elements = arrayOfNulls<Any>(maxElements)
    }

    // ========== 序列化支持 ==========

    @Throws(IOException::class)
    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        out.writeInt(size)
        for (e in this) {
            out.writeObject(e)
        }
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun readObject(`in`: ObjectInputStream) {
        `in`.defaultReadObject()
        elements = arrayOfNulls(maxElements)
        val size = `in`.readInt()
        for (i in 0 until size) {
            @Suppress("UNCHECKED_CAST")
            elements[i] = `in`.readObject() as E
        }
        start = 0
        full = size == maxElements
        end = if (full) 0 else size
    }

    // ========== Collection接口实现 ==========

    override val size: Int
        get() = when {
            end < start -> maxElements - start + end
            end == start -> if (full) maxElements else 0
            else -> end - start
        }

    override fun isEmpty(): Boolean = size == 0

    /**
     * 检查队列是否已达到容量限制
     * 
     * @return 如果队列已满返回true
     */
    fun isAtFullCapacity(): Boolean = size == maxElements

    override fun clear() {
        full = false
        start = 0
        end = 0
        elements.fill(null)
    }

    // ========== Queue接口实现 ==========

    /**
     * 添加元素到队列。如果队列已满，最早添加的元素将被丢弃。
     * 
     * @param element 要添加的元素
     * @return 被移除的旧元素，如果队列未满则返回null
     * @throws NullPointerException 如果元素为null
     */
    fun push(element: E): E? {
        requireNotNull(element) { "Attempted to add null object to queue" }
        
        val oldElement = if (isAtFullCapacity()) {
            remove()
        } else {
            null
        }
        
        // 安全检查：确保end索引在有效范围内
        if (end < 0 || end >= maxElements) {
            end = 0
        }
        
        elements[end] = element
        end++
        if (end >= maxElements) {
            end = 0
        }
        if (end == start) {
            full = true
        }
        
        return oldElement
    }

    override fun add(element: E): Boolean {
        requireNotNull(element) { "Attempted to add null object to queue" }
        
        if (isAtFullCapacity()) {
            remove()
        }
        
        elements[end++] = element
        if (end >= maxElements) {
            end = 0
        }
        if (end == start) {
            full = true
        }
        
        return true
    }

    override fun offer(element: E): Boolean = add(element)

    override fun poll(): E? = if (isEmpty()) null else remove()

    override fun element(): E {
        if (isEmpty()) {
            throw NoSuchElementException("queue is empty")
        }
        return peek() ?: throw NoSuchElementException("element is null")
    }

    override fun peek(): E? {
        if (isEmpty()) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return elements[start] as E?
    }

    override fun remove(): E {
        if (isEmpty()) {
            throw NoSuchElementException("queue is empty")
        }
        
        @Suppress("UNCHECKED_CAST")
        val element = elements[start] as E? ?: throw NoSuchElementException("element is null")
        elements[start++] = null
        
        if (start >= maxElements) {
            start = 0
        }
        full = false
        
        return element
    }

    // ========== 额外方法 ==========

    /**
     * 返回指定位置的元素
     * 
     * @param index 元素在队列中的位置
     * @return 位置index处的元素
     * @throws NoSuchElementException 如果请求的位置超出范围[0, size)
     */
    operator fun get(index: Int): E {
        val sz = size
        if (index < 0 || index >= sz) {
            throw NoSuchElementException(
                "The specified index ($index) is outside the available range [0, $sz)"
            )
        }
        val idx = (start + index) % maxElements
        @Suppress("UNCHECKED_CAST")
        return elements[idx] as E
    }

    // ========== 内部辅助方法 ==========

    private fun increment(index: Int): Int {
        var idx = index + 1
        if (idx >= maxElements) {
            idx = 0
        }
        return idx
    }

    private fun decrement(index: Int): Int {
        var idx = index - 1
        if (idx < 0) {
            idx = maxElements - 1
        }
        return idx
    }

    // ========== Iterator实现 ==========

    override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
        private var index = start
        private var lastReturnedIndex = -1
        private var isFirst = full

        override fun hasNext(): Boolean = isFirst || index != end

        override fun next(): E {
            if (!hasNext()) {
                throw NoSuchElementException()
            }
            isFirst = false
            lastReturnedIndex = index
            index = increment(index)
            @Suppress("UNCHECKED_CAST")
            return elements[lastReturnedIndex] as E
        }

        override fun remove() {
            if (lastReturnedIndex == -1) {
                throw IllegalStateException()
            }
            // 第一个元素可以快速移除
            if (lastReturnedIndex == start) {
                this@CircularFifoQueue.remove()
                lastReturnedIndex = -1
                return
            }
            var pos = lastReturnedIndex + 1
            if (start < lastReturnedIndex && pos < end) {
                // 在一部分中移动
                System.arraycopy(elements, pos, elements, lastReturnedIndex, end - pos)
            } else {
                // 其他元素需要移动后续元素
                while (pos != end) {
                    if (pos >= maxElements) {
                        elements[pos - 1] = elements[0]
                        pos = 0
                    } else {
                        elements[decrement(pos)] = elements[pos]
                        pos = increment(pos)
                    }
                }
            }
            lastReturnedIndex = -1
            end = decrement(end)
            elements[end] = null
            full = false
            index = decrement(index)
        }
    }

    companion object {
        /**
         * 序列化版本号
         */
        @Suppress("ConstPropertyName")
        private const val serialVersionUID = -8423413834657610406L
    }
}

