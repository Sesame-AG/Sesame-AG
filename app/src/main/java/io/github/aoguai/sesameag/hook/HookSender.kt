package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 用于发送Hook数据到DEBUG服务器
 */
object HookSender {
    private const val TAG = "HookSender"

    @Volatile
    var sendFlag: Boolean = true

    private val client = OkHttpClient()

    private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaType()

    fun sendHookData(jo: JSONObject, url: String) {
        // 使用刚刚修复的 GlobalThreadPools 启动协程
        // 这样就不需要使用 object : Callback (匿名内部类)，从而绕过 R8 的 Bug
        GlobalThreadPools.execute {
            try {
                val body: RequestBody = jo.toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                // 【关键修改】将 enqueue 改为 execute (同步执行)
                // 协程本身就在后台线程，所以这里可以直接阻塞
                client.newCall(request).execute().use { response ->
                    // use 函数会自动关闭 response body，防止内存泄漏
                    if (!response.isSuccessful) {
                        Log.error(TAG, "Failed to receive response code: ${response.code}")
                    } else {
                        // 如果需要成功日志，可以在这里打印
                        // Log.runtime(TAG, "Sent successfully")
                    }
                }
            } catch (e: Exception) {
                // 对应原来的 onFailure
                if (sendFlag) { // 避免过多冗余失败记录
                    Log.error(TAG, "Failed to send hook data: ${e.message}")
                    // 如果网络不通，后续可能都不通，暂停报错
                    sendFlag = false
                }
            }
        }
    }
}
