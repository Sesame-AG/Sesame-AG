package io.github.aoguai.sesameag.ui.screen.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Agriculture
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.screen.components.MenuButton
import io.github.aoguai.sesameag.util.LogCatalog
import io.github.aoguai.sesameag.util.LogChannel

@Composable
fun LogsContent(
    onEvent: (MainActivity.MainUiEvent) -> Unit
) {
    val sections = LogCatalog.viewerSections()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        sections.forEach { section ->
            item(key = section.group.name) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = section.group.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = section.channels.joinToString(" · ") { it.displayName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(
                items = section.channels.chunked(2),
                key = { row -> row.joinToString("-") { it.loggerName } }
            ) { rowChannels ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowChannels.forEach { channel ->
                        MenuButton(
                            text = channel.displayName,
                            icon = iconFor(channel),
                            modifier = Modifier.weight(1f)
                        ) {
                            onEvent(MainActivity.MainUiEvent.OpenLog(channel))
                        }
                    }
                    if (rowChannels.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun iconFor(channel: LogChannel): ImageVector {
    return when (channel) {
        LogChannel.FOREST -> Icons.Rounded.Forest
        LogChannel.DODO -> Icons.Rounded.Description
        LogChannel.ORCHARD, LogChannel.FISHPOND, LogChannel.FARM, LogChannel.STALL -> Icons.Rounded.Agriculture
        LogChannel.GOLDEN_BEAN -> Icons.Rounded.EmojiEvents
        LogChannel.MEMBER, LogChannel.YOUTH_PRIVILEGE, LogChannel.SESAME_CREDIT -> Icons.Rounded.CardMembership
        LogChannel.SPORTS -> Icons.AutoMirrored.Rounded.DirectionsRun
        LogChannel.DEBUG, LogChannel.RUNTIME -> Icons.Rounded.BugReport
        LogChannel.ERROR -> Icons.Rounded.ErrorOutline
        LogChannel.CAPTURE -> Icons.Rounded.History
        else -> Icons.Rounded.Description
    }
}
