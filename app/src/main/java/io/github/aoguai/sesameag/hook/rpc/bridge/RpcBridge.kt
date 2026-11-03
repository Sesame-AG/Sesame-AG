package io.github.aoguai.sesameag.hook.rpc.bridge

import io.github.aoguai.sesameag.entity.RpcEntity

/**
 * RPC桥接接口
 */
interface RpcBridge {
    companion object {
        const val DEFAULT_TRY_COUNT: Int = 3
        const val DEFAULT_RETRY_INTERVAL: Int = -1
    }
    
    @Throws(Exception::class)
    fun load()
    
    fun unload()

    fun requestString(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): String?
    fun requestObject(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): RpcEntity?
}

