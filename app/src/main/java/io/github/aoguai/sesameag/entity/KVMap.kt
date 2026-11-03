package io.github.aoguai.sesameag.entity

import java.io.Serial
import java.io.Serializable

open class KVMap<K, V> (var key: K, var value: V) : Serializable {

    companion object {
        @Serial
        const val serialVersionUID: Long = 1L
    }
}
