package io.github.aoguai.sesameag.ui.screen.card


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator

@Composable
fun OneWordCard(
    oneWord: String,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                enabled = !isLoading,
                onClick = onClick,
            )
            .padding(16.dp)
    ) {
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "OneWordAnimation"
        ) { loading ->
            if (loading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    DelayedLoadingIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "本来无一物,何处惹尘..",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = oneWord,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
