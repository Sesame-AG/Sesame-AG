package io.github.aoguai.sesameag.hook.server

import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.SerialTaskQueue
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/**
 * HTTP 服务管理器 (单例)
 * 负责控制 Server 的启动和停止
 */
object ModuleHttpServerManager {
    private const val TAG = "ModuleHttpServerManager"

    /** 调试 token 键：每台设备随机生成并持久化，不再使用仓库里的固定字符串。 */
    private const val TOKEN_STORE_KEY = "debugHttpSecretToken"
    private const val TOKEN_BYTES = 24
    private const val TOKEN_MIN_LENGTH = 24

    // 持有 Server 实例
    private val lifecycle = SerialTaskQueue()
    private var server: ModuleHttpServer? = null

    /**
     * 启动服务 (如果尚未启动)
     */
    fun startIfNeeded(
        port: Int,
        currentProcessName: String, // 当前进程名
        mainProcessName: String     // 主进程包名
    ): Unit = lifecycle.execute {
        // 1. 安全检查：仅允许在主进程启动，避免多个进程抢占端口
        if (currentProcessName != mainProcessName) {
            return@execute
        }

        // 2. 如果已经运行，跳过
        if (server != null && server?.isAlive == true) {
            return@execute
        }

        val secretToken = ensureSecretToken()
        if (secretToken.isBlank()) {
            Log.error(TAG, "调试接口 token 不可用，拒绝启动 HTTP 服务")
            return@execute
        }

        // 3. 启动逻辑
        try {
            stopInternal() // 先尝试停止旧的（如果有）

            val newServer = ModuleHttpServer(port, secretToken)
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) // 启动 NanoHTTPD

            server = newServer
            // 服务只绑定 127.0.0.1；token 需要用户在本机调试时使用，只能从模块日志里读取。
            Log.record(TAG, "调试 HTTP 服务已启动: http://127.0.0.1:$port token=$secretToken")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "HTTP 服务启动失败", e)
        }
    }

    /**
     * 停止服务
     */
    fun stop(): Unit = lifecycle.execute { stopInternal() }

    /**
     * 读取或生成调试 token。持久化失败时返回空串，调用方据此拒绝启动（失败关闭）。
     */
    private fun ensureSecretToken(): String = runCatching {
        val existing = DataStore.get(TOKEN_STORE_KEY, String::class.java)?.trim().orEmpty()
        if (existing.length >= TOKEN_MIN_LENGTH) {
            return@runCatching existing
        }
        val generated = generateToken()
        DataStore.put(TOKEN_STORE_KEY, generated)
        val stored = DataStore.get(TOKEN_STORE_KEY, String::class.java)?.trim().orEmpty()
        stored.takeIf { it.length >= TOKEN_MIN_LENGTH } ?: ""
    }.getOrElse {
        Log.printStackTrace(TAG, "调试接口 token 持久化失败", it)
        ""
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(hex)
    }

    private fun stopInternal() {
        try {
            server?.stop()
            server = null
            Log.record(TAG, "HTTP 服务已停止")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "停止服务异常", e)
        }
    }
}
