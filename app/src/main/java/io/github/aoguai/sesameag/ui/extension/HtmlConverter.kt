package io.github.aoguai.sesameag.ui.extension


import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat

/**
 * 将 HTML 字符串转换为 Compose 可用的 AnnotatedString
 * 支持：颜色 <font color>, 加粗 <b>, 斜体 <i>, 下划线 <u>
 */
fun String.parseHtml(): AnnotatedString {
    // 1. 利用 Android 原生能力解析 HTML
    val spanned = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY)

    // 2. 将 Spanned 转换为 Compose AnnotatedString
    return buildAnnotatedString {
        append(spanned.toString())

        // 遍历所有的 Span 样式
        val spans = spanned.getSpans(0, spanned.length, Any::class.java)
        spans.forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)

            when (span) {
                // 处理颜色
                is ForegroundColorSpan -> {
                    addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                }
                // 处理加粗/斜体
                is StyleSpan -> {
                    when (span.style) {
                        Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                        Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                        Typeface.BOLD_ITALIC -> addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end)
                    }
                }
                // 处理下划线
                is UnderlineSpan -> {
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                }
            }
        }
    }
}
