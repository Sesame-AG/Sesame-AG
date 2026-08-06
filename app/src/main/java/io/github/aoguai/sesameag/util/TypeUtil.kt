package io.github.aoguai.sesameag.util

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * 类型工具类
 * 提供了一系列方法来处理Java反射中的类型相关的操作
 */
internal object TypeUtil {
    
    /**
     * 获取泛型类型的指定索引位置的参数类型
     *
     * @param type  泛型类型
     * @param index 参数索引
     * @return 指定索引位置的参数类型
     */
    @JvmStatic
    fun getTypeArgument(type: Type?, index: Int): Type? {
        val typeArguments = getTypeArguments(type)
        return if (typeArguments != null && typeArguments.size > index) typeArguments[index] else null
    }
    
    /**
     * 获取泛型类型的所有参数类型
     *
     * @param type 泛型类型
     * @return 泛型类型的所有参数类型
     */
    private fun getTypeArguments(type: Type?): Array<Type>? {
        if (type == null) {
            return null
        }
        val parameterizedType = toParameterizedType(type)
        return parameterizedType?.actualTypeArguments
    }
    
    /**
     * 将类型转换为ParameterizedType
     *
     * @param type 泛型类型
     * @return ParameterizedType对象
     */
    private fun toParameterizedType(type: Type?): ParameterizedType? {
        return toParameterizedType(type, 0)
    }
    
    /**
     * 将类型转换为ParameterizedType，并指定接口索引
     *
     * @param type           泛型类型
     * @param interfaceIndex 接口索引
     * @return ParameterizedType对象
     */
    private fun toParameterizedType(type: Type?, interfaceIndex: Int): ParameterizedType? {
        return when (type) {
            is ParameterizedType -> type
            is Class<*> -> {
                val generics = getGenerics(type)
                if (generics.size > interfaceIndex) generics[interfaceIndex] else null
            }
            else -> null
        }
    }
    
    /**
     * 获取类的泛型类型
     *
     * @param clazz Class对象
     * @return 泛型类型数组
     */
    private fun getGenerics(clazz: Class<*>): Array<ParameterizedType> {
        val result = mutableListOf<ParameterizedType>()
        
        val genericSuper = clazz.genericSuperclass
        if (genericSuper != null && genericSuper != Any::class.java) {
            toParameterizedType(genericSuper)?.let { result.add(it) }
        }
        
        val genericInterfaces = clazz.genericInterfaces
        for (genericInterface in genericInterfaces) {
            toParameterizedType(genericInterface)?.let { result.add(it) }
        }
        
        return result.toTypedArray()
    }
    
}

