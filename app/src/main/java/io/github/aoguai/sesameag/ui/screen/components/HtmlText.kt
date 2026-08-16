package io.github.aoguai.sesameag.ui.screen.components

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val fontSize = MaterialTheme.typography.bodySmall.fontSize
    val fontSizePx = with(LocalDensity.current) { fontSize.toPx() }
    AndroidView(
        modifier = modifier,
        factory = { context -> TextView(context) },
        update = { textView ->
            textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.setTextColor(colorScheme.onSurface.toArgb())
            textView.setLinkTextColor(colorScheme.primary.toArgb())
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
        }
    )
}
