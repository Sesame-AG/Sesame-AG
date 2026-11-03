package io.github.aoguai.sesameag.ui.extension

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

/**
 * 扩展函数：打开浏览器
 */

fun Context.openUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

