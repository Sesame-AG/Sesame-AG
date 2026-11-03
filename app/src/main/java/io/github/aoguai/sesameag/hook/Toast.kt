package io.github.aoguai.sesameag.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.aoguai.sesameag.model.BaseModel.Companion.showToast
import io.github.aoguai.sesameag.model.BaseModel.Companion.toastPerfix
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ToastUtil

object Toast {
    private const val TAG: String = "Toast"


    /**
     * 显示 Toast 消息
     *
     * @param message 要显示的消息
     */
    @JvmOverloads
    fun show(message: String?, force: Boolean = false) {
        val context = ApplicationHook.appContext
        if (context == null) {
            Log.error(TAG, "Context is null, cannot show toast $message")
            return
        }
        var finalMessage = message
        val shouldShow = force || showToast.value == true
        val perfix = toastPerfix.value
        if (!perfix.isNullOrBlank() && perfix != "null") {
            finalMessage = "$perfix:$message"
        }
        if (shouldShow) {
            displayToast(context.applicationContext, finalMessage)
        }
    }

    /**
     * 显示 Toast 消息（确保在主线程中调用）
     *
     * @param context 上下文
     * @param message 要显示的消息
     */
    private fun displayToast(context: Context?, message: CharSequence?) {
        try {
            val mainHandler = Handler(Looper.getMainLooper())
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // 如果当前线程是主线程，直接显示
                createAndShowToast(context, message)
            } else {
                // 在非主线程，通过 Handler 切换到主线程
                mainHandler.post { createAndShowToast(context, message) }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "displayToast err:", t)
        }
    }

    /**
     * 创建并显示 Toast
     *
     * @param context 上下文
     * @param message 要显示的消息
     */
    private fun createAndShowToast(context: Context?, message: CharSequence?) {
        try {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "createAndShowToast err:", t)
        }
    }
}

